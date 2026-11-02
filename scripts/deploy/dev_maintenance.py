"""Explicit DEV maintenance stages. Default is local validation, never SSH or deployment."""

import argparse
import datetime as dt
import hashlib
import importlib.util
import json
import os
import signal
import sys
import time
from contextlib import contextmanager
from pathlib import Path

HERE = Path(__file__).resolve().parent
SOURCE = HERE.parents[1]
spec = importlib.util.spec_from_file_location(
    "reviewed_main_only", HERE / "dev_main_only.py"
)
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)
require = base.require
ACTIONS = ("begin", "confirm-drain", "install", "reopen", "status")


def utc():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def timestamp(value):
    require(isinstance(value, str), "MAINTENANCE_TIMESTAMP_REQUIRED")
    result = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    require(result.tzinfo is not None, "MAINTENANCE_TIMEZONE_REQUIRED")
    return result


def plan_binding(plan):
    # These two fields describe this action's fresh operator review, not a new baseline.
    return {
        k: v
        for k, v in plan.items()
        if k not in ("checkedAtUtc", "preflightReceiptSha256")
    }


def validate_plan(plan, sha, catalog):
    fields = (
        "schemaVersion",
        "approvedSha",
        "preflightReceiptSha256",
        "checkedAtUtc",
        "services",
        "configSha256",
        "flywayCatalogSha256",
        "rollback",
        "budget",
        "ci",
        "maintenance",
    )
    base.shape(plan, fields)
    require(
        type(plan["schemaVersion"]) is int and plan["schemaVersion"] == 3,
        "MAINTENANCE_PLAN_SCHEMA_REQUIRED",
    )
    common = {k: v for k, v in plan.items() if k != "maintenance"}
    common["schemaVersion"] = 1
    base.validate_manifest(common, sha)
    require(
        base.sha_json(catalog)
        == plan["flywayCatalogSha256"]
        == base.V65_CATALOG_SHA256,
        "EXACT_REVIEWED_V65_CATALOG_REQUIRED",
    )
    extra = plan["maintenance"]
    base.shape(
        extra,
        (
            "sessionId",
            "fromVersion",
            "newMainRepoDigest",
            "guardianMysql",
            "operatorPlanSha256",
            "writerInventoryReceiptSha256",
            "isolatedRecoveryPlanSha256",
        ),
    )
    require(
        base.is_hex(extra["sessionId"], r"[a-f0-9]{32}")
        and type(extra["fromVersion"]) is int
        and extra["fromVersion"] in (64, 65)
        and base.repo_digest("main-service", extra["newMainRepoDigest"])
        and extra["newMainRepoDigest"] != plan["services"]["main-service"]["repoDigest"]
        and all(
            base.is_hex(extra[k])
            for k in (
                "operatorPlanSha256",
                "writerInventoryReceiptSha256",
                "isolatedRecoveryPlanSha256",
            )
        ),
        "REVIEWED_MAINTENANCE_PLAN_REQUIRED",
    )
    ci = extra["guardianMysql"]
    base.shape(ci, ("sourceSha", "catalogSha256", "runId", "reviewedSuccess"))
    require(
        base.is_hex(ci["sourceSha"], r"[a-f0-9]{40}")
        and ci["catalogSha256"] == base.V65_CATALOG_SHA256
        and type(ci["runId"]) is int
        and ci["runId"] > 0
        and ci["reviewedSuccess"] is True,
        "REVIEWED_V65_CATALOG_MYSQL_CI_REQUIRED",
    )


def private_read(path):
    require(
        path.is_file()
        and not path.is_symlink()
        and path.stat().st_size <= 128 * 1024
        and (os.name != "posix" or path.stat().st_mode & 0o077 == 0),
        "PRIVATE_MAINTENANCE_FILE_REQUIRED",
    )
    return json.loads(path.read_text(encoding="utf-8"))


def private_write(path, value):
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0)
    with os.fdopen(
        os.open(path, flags, 0o600), "w", encoding="utf-8", newline="\n"
    ) as stream:
        json.dump(value, stream, sort_keys=True, separators=(",", ":"))
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())
    if os.name == "posix":
        directory = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)


class Journal:
    """Private, exclusive, append-only intent log; uncertain operations are never replayed."""

    def __init__(self, root, session):
        require(base.is_hex(session, r"[a-f0-9]{32}"), "MAINTENANCE_SESSION_REQUIRED")
        self.root, self.session = root / ".dev-maintenance", session
        self.directory = self.root / session
        self.active = self.root / "active.json"

    def secure_directory(self, path, create=False):
        if create and not path.exists():
            path.mkdir(mode=0o700)
        require(
            path.is_dir()
            and not path.is_symlink()
            and path.resolve() == path
            and (os.name != "posix" or path.stat().st_mode & 0o077 == 0),
            "PRIVATE_MAINTENANCE_DIRECTORY_REQUIRED",
        )

    @contextmanager
    def locked(self):
        self.secure_directory(self.root, create=True)
        path = self.root / "action.lock"
        # A hard kill leaves this file behind. No automatic stale-lock removal.
        fd = os.open(
            path,
            os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0),
            0o600,
        )
        identity = os.fstat(fd)
        try:
            yield
        finally:
            os.close(fd)
            current = path.stat()
            require(
                (identity.st_dev, identity.st_ino) == (current.st_dev, current.st_ino),
                "ACTION_LOCK_CHANGED",
            )
            path.unlink()

    def create(self):
        require(
            not self.directory.exists() and not self.active.exists(),
            "EXISTING_OR_ACTIVE_MAINTENANCE_SESSION",
        )
        self.directory.mkdir(mode=0o700)
        private_write(self.active, {"sessionId": self.session})

    def read(self):
        self.secure_directory(self.root)
        self.secure_directory(self.directory)
        events, previous = [], "0" * 64
        files = sorted(self.directory.iterdir())
        require(files, "MAINTENANCE_BASELINE_MISSING")
        for i, path in enumerate(files):
            require(path.name == f"{i:04}.json", "MAINTENANCE_JOURNAL_GAP")
            event = private_read(path)
            base.shape(
                event, ("index", "phase", "atUtc", "previousSha256", "data", "sha256")
            )
            digest = base.sha_json({k: v for k, v in event.items() if k != "sha256"})
            require(
                event["index"] == i
                and event["previousSha256"] == previous
                and event["sha256"] == digest,
                "MAINTENANCE_JOURNAL_CHANGED",
            )
            require(
                isinstance(event["data"], dict) and isinstance(event["phase"], str),
                "MAINTENANCE_JOURNAL_SHAPE",
            )
            require(
                not events
                or timestamp(event["atUtc"]) >= timestamp(events[-1]["atUtc"]),
                "MAINTENANCE_CLOCK_REVERSED",
            )
            events.append(event)
            previous = digest
        return events

    def append(self, phase, data):
        events = self.read() if any(self.directory.iterdir()) else []
        event = {
            "index": len(events),
            "phase": phase,
            "atUtc": utc(),
            "previousSha256": events[-1]["sha256"] if events else "0" * 64,
            "data": data,
        }
        event["sha256"] = base.sha_json(event)
        private_write(self.directory / f"{len(events):04}.json", event)
        return event

    def require_active(self):
        require(
            private_read(self.active) == {"sessionId": self.session},
            "OTHER_OR_FINISHED_MAINTENANCE_SESSION",
        )


class BaselineChecks(base.V65Deployment):
    """Healthy pre-stop checks. A backup cannot exist for this new quiescence yet."""

    def __init__(self, root, plan, catalog):
        base.Deployment.__init__(
            self, root, plan, catalog, plan["maintenance"]["newMainRepoDigest"]
        )
        self.baseline = {
            v: c
            for v, c in catalog.items()
            if int(v) <= plan["maintenance"]["fromVersion"]
        }

    def verify_extra_readonly(self, before, env, marker):
        self.prior_policy_off(env)
        self.prior_policy_off(os.environ)
        require(marker == "true", "EXISTING_SIGNUP_PAUSE_REQUIRED_FOR_V65")

    def verify_database(self, mysql_id):
        require(
            self.read_flyway(mysql_id) == self.baseline,
            "V65_BASELINE_HISTORY_MISMATCH_STOP_NO_REPAIR",
        )
        self.verify_v65_columns(
            mysql_id, self.manifest["maintenance"]["fromVersion"] == 65
        )


class Maintenance:
    def __init__(self, root, plan, catalog):
        self.root, self.plan, self.catalog = root, plan, catalog
        self.session = plan["maintenance"]["sessionId"]
        self.journal = Journal(root, self.session)
        self.checks = BaselineChecks(root, plan, catalog)

    def capture(self):
        result = {}
        for name in base.SERVICES:
            ids = self.checks.docker(
                "ps",
                "--all",
                "--quiet",
                "--no-trunc",
                "--filter",
                "label=com.docker.compose.project=edupilot",
                "--filter",
                "label=com.docker.compose.service=" + name,
            ).split()
            require(
                len(ids) == 1 and base.is_hex(ids[0]),
                "CURRENT_SERVICE_IDENTITY_AMBIGUOUS",
            )
            fmt = (
                '{"imageId":{{json .Image}},"running":{{.State.Running}},"paused":{{.State.Paused}},'
                '"restarting":{{.State.Restarting}},"status":{{json .State.Status}},'
                '"startedAt":{{json .State.StartedAt}},"finishedAt":{{json .State.FinishedAt}},'
                '"exitCode":{{.State.ExitCode}},"oomKilled":{{.State.OOMKilled}},"restartCount":{{.RestartCount}},'
                '"health":{{with (index .State "Health")}}{{json .Status}}{{else}}null{{end}},'
                '"ports":{{json .HostConfig.PortBindings}},"networkMode":{{json .HostConfig.NetworkMode}},'
                '"restartPolicy":{{json .HostConfig.RestartPolicy.Name}},"stopSignal":{{json (index .Config "StopSignal")}}}'
            )
            item = json.loads(self.checks.docker("inspect", "--format", fmt, ids[0]))
            item["id"] = ids[0]
            require(
                item["networkMode"] not in ("host", "none", "default", "bridge")
                and not item["networkMode"].startswith("container:")
                and item["restartPolicy"] == "unless-stopped"
                and not item["paused"]
                and not item["restarting"],
                "UNREVIEWED_NETWORK_OR_RESTART_STATE",
            )
            ports = item["ports"] or {}
            if name != "nginx":
                require(
                    not any(ports.values()),
                    "DIRECT_SERVICE_PUBLISHED_PORT_BLOCKS_QUIESCENCE",
                )
            else:
                require(
                    set(ports) == {"80/tcp", "443/tcp"}
                    and all(
                        isinstance(v, list)
                        and v
                        and all(b.get("HostPort") == port.split("/")[0] for b in v)
                        for port, v in ports.items()
                    ),
                    "NGINX_INGRESS_BINDINGS_UNREVIEWED",
                )
            result[name] = item
        return result

    def stable(self, current, baseline, names=base.SERVICES):
        keys = (
            "id",
            "imageId",
            "ports",
            "networkMode",
            "restartPolicy",
            "stopSignal",
            "startedAt",
            "restartCount",
        )
        require(
            all(all(current[n][k] == baseline[n][k] for k in keys) for n in names),
            "MAINTENANCE_RUNTIME_DRIFT",
        )

    def held(self, events, candidate=False):
        first = events[0]["data"]
        require(
            first["planBindingSha256"] == base.sha_json(plan_binding(self.plan)),
            "MAINTENANCE_PLAN_CHANGED",
        )
        require(
            all(
                hashlib.sha256((self.root / p).read_bytes()).hexdigest()
                == self.plan["configSha256"][p]
                for p in base.FILES
            ),
            "HOST_CONFIG_CHANGED_SINCE_PREFLIGHT",
        )
        current = self.capture()
        self.stable(
            current,
            first["snapshot"],
            ("ai-service", "mysql", "nginx") if candidate else base.SERVICES,
        )
        for name in ("ai-service", "mysql"):
            require(
                current[name]["running"] is True
                and current[name]["health"] == "healthy",
                "EXCLUDED_SERVICE_NOT_HEALTHY",
            )
        stopped = ("nginx",) if candidate else ("nginx", "main-service")
        for name in stopped:
            requested = next(
                e
                for e in events
                if e["phase"]
                == (
                    "INGRESS_STOP_REQUESTED"
                    if name == "nginx"
                    else "MAIN_STOP_REQUESTED"
                )
            )
            state = current[name]
            require(
                state["running"] is False
                and state["status"] == "exited"
                and not state["oomKilled"]
                and type(state["exitCode"]) is int
                and state["exitCode"] in (0, 143)
                and timestamp(requested["atUtc"])
                <= timestamp(state["finishedAt"])
                <= timestamp(utc()),
                "INTENDED_GRACEFUL_STOP_NOT_CONFIRMED",
            )
        return current

    def begin(self):
        require(
            not self.journal.directory.exists() and not self.journal.active.exists(),
            "EXISTING_OR_ACTIVE_MAINTENANCE_SESSION",
        )
        before, env, marker = self.checks.verify_readonly()
        with self.checks.prepared_main(env, marker) as (_, target_id, _):
            again, again_env, again_marker = self.checks.verify_readonly()
            require(
                (again, again_env, again_marker) == (before, env, marker),
                "PREFLIGHT_CHANGED_BEFORE_MAINTENANCE",
            )
            snapshot = self.capture()
            require(
                all(
                    snapshot[n]["id"] == before[n]["id"]
                    and snapshot[n]["imageId"] == before[n]["imageId"]
                    and snapshot[n]["running"] is True
                    and snapshot[n]["health"] == before[n]["health"]
                    for n in base.SERVICES
                ),
                "HEALTHY_BASELINE_CHANGED",
            )
            require(
                snapshot["main-service"]["stopSignal"] in (None, "", "SIGTERM", "15")
                and snapshot["nginx"]["stopSignal"] in ("SIGQUIT", "3"),
                "GRACEFUL_STOP_SIGNAL_UNREVIEWED",
            )
            self.journal.create()
            self.journal.append(
                "BASELINE",
                {
                    "planBindingSha256": base.sha_json(plan_binding(self.plan)),
                    "snapshot": snapshot,
                    "targetImageId": target_id,
                    "preflightReceiptSha256": self.plan["preflightReceiptSha256"],
                },
            )
            for name, phase in (("nginx", "INGRESS"), ("main-service", "MAIN")):
                current = self.capture()
                self.stable(current, snapshot)
                require(
                    all(
                        current[n]["running"] is True
                        and current[n]["health"] == "healthy"
                        for n in (
                            ("ai-service", "mysql", "main-service")
                            if name == "nginx"
                            else ("ai-service", "mysql")
                        )
                    ),
                    "HEALTHY_BASELINE_CHANGED",
                )
                require(
                    current["nginx"]["running"] is (name == "nginx")
                    and current["main-service"]["running"] is True
                    and current["main-service"]["health"] == "healthy",
                    "PRE_STOP_STATE_CHANGED",
                )
                request = self.journal.append(
                    phase + "_STOP_REQUESTED", {"containerId": snapshot[name]["id"]}
                )
                # No SIGKILL deadline. A client timeout has an uncertain daemon outcome and is not retried.
                self.checks.docker(
                    "stop", "--timeout", "-1", snapshot[name]["id"], timeout=120
                )
                observed = self.capture()
                self.stable(observed, snapshot)
                state = observed[name]
                require(
                    state["running"] is False
                    and state["status"] == "exited"
                    and not state["oomKilled"]
                    and state["exitCode"] in (0, 143)
                    and timestamp(request["atUtc"])
                    <= timestamp(state["finishedAt"])
                    <= timestamp(utc()),
                    "GRACEFUL_STOP_OUTCOME_UNCONFIRMED",
                )
                if name == "main-service":
                    self.held(self.journal.read())
                self.journal.append(
                    phase + "_STOPPED",
                    {"containerId": state["id"], "finishedAtUtc": state["finishedAt"]},
                )
        return self.result("MAIN_STOPPED_AWAITING_DRAIN_REVIEW")

    def confirm_drain(self, review):
        events = self.events("MAIN_STOPPED")
        base.shape(
            review,
            (
                "sessionId",
                "mainStoppedReceiptSha256",
                "checkedAtUtc",
                "activeRequests",
                "activeAiJobs",
                "independentWriters",
                "writerInventoryReceiptSha256",
                "drainEvidenceSha256",
            ),
        )
        base.fresh(review["checkedAtUtc"])
        require(
            review["sessionId"] == self.session
            and review["mainStoppedReceiptSha256"] == events[-1]["sha256"]
            and review["writerInventoryReceiptSha256"]
            == self.plan["maintenance"]["writerInventoryReceiptSha256"]
            and base.is_hex(review["drainEvidenceSha256"])
            and all(
                type(review[k]) is int and review[k] == 0
                for k in ("activeRequests", "activeAiJobs", "independentWriters")
            )
            and timestamp(review["checkedAtUtc"]) >= timestamp(events[-1]["atUtc"]),
            "EXPLICIT_CURRENT_DRAIN_REVIEW_REQUIRED",
        )
        self.held(events)
        self.journal.append("QUIESCED", {"drainReview": review})
        return self.result("QUIESCED_BACKUP_AND_ISOLATED_RESTORE_REQUIRED")

    def events(self, phase):
        self.journal.require_active()
        events = self.journal.read()
        require(
            events[-1]["phase"] == phase,
            "STAGE_ALREADY_USED_OR_UNCERTAIN_MANUAL_RECONCILIATION_REQUIRED",
        )
        require(
            events[0]["data"]["planBindingSha256"]
            == base.sha_json(plan_binding(self.plan)),
            "MAINTENANCE_PLAN_CHANGED",
        )
        return events

    def install(self, manifest):
        events = self.events("QUIESCED")
        base.validate_v65_manifest(manifest, self.plan["approvedSha"])
        common = {k: v for k, v in manifest.items() if k != "v65"}
        common["schemaVersion"] = 3
        common["maintenance"] = self.plan["maintenance"]
        require(
            plan_binding(common) == plan_binding(self.plan),
            "INSTALL_MANIFEST_NOT_BOUND_TO_BASELINE",
        )
        extra = manifest["v65"]
        require(
            extra["fromVersion"] == self.plan["maintenance"]["fromVersion"]
            and extra["guardianMysql"] == self.plan["maintenance"]["guardianMysql"]
            and extra["writeQuiescence"]["startedAtUtc"] == events[-1]["atUtc"]
            and extra["writeQuiescence"]["receiptSha256"] == events[-1]["sha256"],
            "BACKUP_NOT_BOUND_TO_ACTUAL_QUIESCENCE",
        )
        base.fresh(events[0]["atUtc"])
        controller = base.V65Deployment(
            self.root,
            manifest,
            self.catalog,
            self.plan["maintenance"]["newMainRepoDigest"],
        )
        self.journal.append(
            "INSTALL_VALIDATION_STARTED", {"manifestSha256": base.sha_json(manifest)}
        )

        def validate_held():
            current = self.held(self.journal.read())
            env, marker = controller.verify_main_inputs(current)
            controller.verify_extra_readonly(current, env, marker)
            controller.verify_database(current["mysql"]["id"])
            return current, env, marker

        before, env, marker = validate_held()
        with controller.prepared_main(env, marker) as (compose, image_id, desired_env):
            require(
                image_id == events[0]["data"]["targetImageId"],
                "STAGED_IMAGE_ID_CHANGED",
            )
            current, current_env, current_marker = validate_held()
            require(
                (current, current_env, current_marker) == (before, env, marker),
                "PREFLIGHT_CHANGED_BEFORE_MAIN_REPLACEMENT",
            )
            # New JVM startup releases worker quiescence. Ingress remains stopped until a separate reviewed reopen.
            request = self.journal.append(
                "MAIN_START_REQUESTED",
                {"workersMayHaveRun": True, "targetImageId": image_id},
            )
            controller.main_recreation_requested = True
            controller.docker(
                *compose,
                "up",
                "-d",
                "--no-deps",
                "--no-build",
                "--pull",
                "never",
                "main-service",
                timeout=120,
            )
            after = None
            for _ in range(60):
                after = self.held(self.journal.read(), candidate=True)
                main = after["main-service"]
                require(
                    main["id"] != before["main-service"]["id"]
                    and main["imageId"] == image_id
                    and main["running"] is True
                    and main["restartCount"] == 0
                    and timestamp(main["startedAt"]) >= timestamp(request["atUtc"]),
                    "NEW_MAIN_IDENTITY_UNCONFIRMED",
                )
                if main["health"] == "healthy":
                    break
                time.sleep(2)
            require(
                after["main-service"]["health"] == "healthy",
                "MAIN_HEALTH_FAILED_MANUAL_RECOVERY_REQUIRED",
            )
            new_env = controller.main_environment(after["main-service"]["id"])
            require(
                all(new_env.get(k) == env.get(k) for k in desired_env)
                and all(not new_env.get(k) for k in (*base.TRIAL, *base.OVERRIDES)),
                "POST_MAIN_SETTINGS_OR_CREDENTIAL_DRIFT",
            )
            controller.verify_post_migration(after)
            self.private_health(after["main-service"]["id"])
            final = self.held(self.journal.read(), candidate=True)
            require(final == after, "CONTAINERS_CHANGED_DURING_FINAL_READ")
            self.journal.append(
                "VERIFIED_HELD",
                {
                    "snapshot": after,
                    "manifestSha256": base.sha_json(manifest),
                    "workersMayHaveRun": True,
                    "mainRecreationsConfirmed": 1,
                },
            )
        return self.result("MAIN_V65_VERIFIED_INGRESS_HELD")

    def private_health(self, cid):
        for path in ("/api/health", "/api/health/ready"):
            response = json.loads(
                self.checks.docker(
                    "exec",
                    cid,
                    "curl",
                    "--fail",
                    "--silent",
                    "--show-error",
                    "http://127.0.0.1:8080" + path,
                )
            )
            if path.endswith("ready"):
                require(
                    response.get("status") == "UP"
                    and response.get("checks") == {"db": "UP", "aiService": "UP"},
                    "PRIVATE_READINESS_FAILED",
                )
            else:
                require(
                    response.get("data", {}).get("status") == "UP",
                    "PRIVATE_HEALTH_FAILED",
                )

    def reopen(self, review):
        events = self.events("VERIFIED_HELD")
        base.shape(
            review,
            (
                "sessionId",
                "verifiedReceiptSha256",
                "checkedAtUtc",
                "allowIngress",
                "workersMayHaveRunAcknowledged",
                "budget",
                "evidenceSha256",
            ),
        )
        base.fresh(review["checkedAtUtc"])
        budget, original = review["budget"], self.plan["budget"]
        base.shape(
            budget,
            (
                "confirmed",
                "reserved",
                "limit",
                "currentMainContainerId",
                "evidenceSha256",
            ),
        )
        require(
            review["sessionId"] == self.session
            and review["verifiedReceiptSha256"] == events[-1]["sha256"]
            and review["allowIngress"] is True
            and review["workersMayHaveRunAcknowledged"] is True
            and timestamp(review["checkedAtUtc"]) >= timestamp(events[-1]["atUtc"])
            and base.is_hex(review["evidenceSha256"])
            and all(type(budget[k]) is int for k in ("confirmed", "reserved", "limit"))
            and budget["confirmed"] == original["confirmed"] + 1
            and budget["reserved"] == original["reserved"]
            and budget["limit"] == 7
            and budget["confirmed"] + budget["reserved"] + 1 <= 7
            and budget["currentMainContainerId"]
            == events[-1]["data"]["snapshot"]["main-service"]["id"]
            and base.is_hex(budget["evidenceSha256"]),
            "FRESH_REOPEN_AND_CURRENT_BUDGET_REVIEW_REQUIRED",
        )
        before = self.held(events, candidate=True)
        require(before == events[-1]["data"]["snapshot"], "VERIFIED_CANDIDATE_CHANGED")
        env, marker = self.checks.verify_main_inputs(before)
        self.checks.verify_extra_readonly(before, env, marker)
        self.checks.verify_post_migration(before)
        self.private_health(before["main-service"]["id"])
        self.journal.append("REOPEN_REQUESTED", {"review": review})
        try:
            self.checks.docker("start", before["nginx"]["id"])
            after = self.capture()
            self.stable(after, before, ("main-service", "ai-service", "mysql"))
            require(
                after["nginx"]["id"] == before["nginx"]["id"]
                and after["nginx"]["imageId"] == before["nginx"]["imageId"]
                and after["nginx"]["running"] is True
                and after["nginx"]["restartCount"] == before["nginx"]["restartCount"]
                and all(
                    after[n]["running"] is True and after[n]["health"] == "healthy"
                    for n in ("main-service", "ai-service", "mysql")
                ),
                "REOPENED_NGINX_IDENTITY_UNCONFIRMED",
            )
            self.checks.docker("exec", before["nginx"]["id"], "nginx", "-t")
            for path in ("/api/health", "/api/health/ready"):
                with base.urllib.request.urlopen(
                    "https://dev.uteum.com" + path, timeout=15
                ) as response:
                    health = json.loads(response.read(65536))
                if path.endswith("ready"):
                    require(
                        health.get("status") == "UP"
                        and health.get("checks") == {"db": "UP", "aiService": "UP"},
                        "PUBLIC_READINESS_FAILED",
                    )
                else:
                    require(
                        health.get("data", {}).get("status") == "UP",
                        "PUBLIC_HEALTH_FAILED",
                    )
            require(self.capture() == after, "CONTAINERS_CHANGED_DURING_REOPEN")
            self.journal.append(
                "REOPENED", {"snapshot": after, "workersMayHaveRun": True}
            )
            self.journal.active.unlink()
        except BaseException:
            # One compensating close, never a retry of start/up, and never a stop of an unknown container.
            # A log I/O failure must not skip the compensating close.
            intent_logged = True
            try:
                self.journal.append(
                    "REBLOCK_REQUESTED", {"containerId": before["nginx"]["id"]}
                )
            except (OSError, ValueError):
                intent_logged = False
            # Do not let an unrelated unhealthy AI/MySQL prevent closing the pinned ingress.
            cid = before["nginx"]["id"]
            fmt = '{"id":{{json .Id}},"imageId":{{json .Image}},"running":{{.State.Running}}}'
            current = json.loads(self.checks.docker("inspect", "--format", fmt, cid))
            require(
                current["id"] == cid
                and current["imageId"] == before["nginx"]["imageId"],
                "REBLOCK_TARGET_CHANGED_ACCESS_UNKNOWN",
            )
            if current["running"]:
                self.checks.docker("stop", "--timeout", "-1", cid, timeout=120)
            current = json.loads(self.checks.docker("inspect", "--format", fmt, cid))
            require(
                current["id"] == cid
                and current["imageId"] == before["nginx"]["imageId"]
                and current["running"] is False,
                "REBLOCK_OUTCOME_UNKNOWN",
            )
            self.journal.append(
                "REBLOCKED", {"workersMayHaveRun": True, "intentLogged": intent_logged}
            )
            raise
        return self.result("MAINTENANCE_REOPENED_READBACK_PASSED")

    def result(self, status):
        events = self.journal.read()
        workers = any(e["phase"] == "MAIN_START_REQUESTED" for e in events)
        return {
            "status": status,
            "sessionId": self.session,
            "phase": events[-1]["phase"],
            "receiptSha256": events[-1]["sha256"],
            "atUtc": events[-1]["atUtc"],
            "workersMayHaveRun": workers,
            "automaticRetryOrRollback": False,
        }

    def perform(self, action, evidence=None):
        require(action in ACTIONS, "EXPLICIT_MAINTENANCE_ACTION_REQUIRED")
        if action == "status":
            return self.result("JOURNAL_ONLY_LIVE_STATE_NOT_ATTESTED")
        validate_plan(self.plan, self.plan["approvedSha"], self.catalog)
        require(
            evidence is None if action == "begin" else isinstance(evidence, dict),
            "ACTION_EVIDENCE_REQUIRED",
        )
        with self.journal.locked():
            if action == "begin":
                return self.begin()
            self.journal.require_active()
            return {
                "confirm-drain": self.confirm_drain,
                "install": self.install,
                "reopen": self.reopen,
            }[action](evidence)


def interrupt(signum, frame):
    raise InterruptedError("MAINTENANCE_INTERRUPTED")


def run():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--action", choices=ACTIONS, required=True)
    parser.add_argument("--plan", type=Path, required=True)
    parser.add_argument("--approved-sha", required=True)
    parser.add_argument("--root", type=Path)
    parser.add_argument("--evidence", type=Path)
    parser.add_argument(
        "--execute",
        action="store_true",
        help="Explicit approved Linux operator action; default performs no operations",
    )
    args = parser.parse_args()
    plan = private_read(args.plan)
    catalog = base.source_catalog_v65(SOURCE)
    if args.action == "status":
        require(
            plan.get("approvedSha") == args.approved_sha
            and base.is_hex(args.approved_sha, r"[a-f0-9]{40}"),
            "EXACT_REVIEWED_STATUS_SOURCE_REQUIRED",
        )
    else:
        validate_plan(plan, args.approved_sha, catalog)
    require(
        plan["configSha256"]
        == {
            p: hashlib.sha256((SOURCE / p).read_bytes()).hexdigest() for p in base.FILES
        },
        "REVIEWED_HOST_CONFIG_MUST_MATCH_CHECKOUT",
    )
    evidence = private_read(args.evidence) if args.evidence else None
    require(
        evidence is None
        if args.action in ("begin", "status")
        else isinstance(evidence, dict),
        "ACTION_EVIDENCE_REQUIRED",
    )
    if args.action == "install":
        base.validate_v65_manifest(evidence, args.approved_sha)
    if not args.execute:
        print(
            json.dumps(
                {
                    "status": "LOCAL_PLAN_VALIDATED_NO_ACTION",
                    "serverConnections": 0,
                    "automaticRetryOrRollback": False,
                }
            )
        )
        return 0
    require(sys.platform.startswith("linux"), "EXPLICIT_LINUX_OPERATOR_ONLY")
    head = (
        base.subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=SOURCE, capture_output=True, check=True
        )
        .stdout.decode()
        .strip()
    )
    dirty = base.subprocess.run(
        ["git", "status", "--porcelain", "--untracked-files=all"],
        cwd=SOURCE,
        capture_output=True,
        check=True,
    ).stdout
    require(
        head == args.approved_sha and not dirty,
        "CLEAN_EXACT_REVIEWED_SOURCE_CHECKOUT_REQUIRED",
    )
    root = args.root
    require(
        root is not None
        and root.is_absolute()
        and root == Path("/opt/edupilot")
        and root.is_dir()
        and root.resolve() == root,
        "EXISTING_CANONICAL_DEV_DEPLOY_PATH_REQUIRED",
    )
    # Use an already authenticated, verified DEV operator session. This tool never authenticates or dispatches.
    require(
        base.subprocess.run(["hostname"], capture_output=True, check=True)
        .stdout.decode()
        .strip()
        == "ip-172-31-40-63",
        "EXACT_REVIEWED_DEV_HOST_REQUIRED",
    )
    for signum in (signal.SIGTERM, signal.SIGHUP):
        signal.signal(signum, interrupt)
    controller = Maintenance(root, plan, catalog)
    try:
        result = controller.perform(args.action, evidence)
    except BaseException:  # noqa: BLE001 - report interruption without leaking command or credential exceptions
        try:
            result = controller.result(
                "BLOCKED_INSPECT_JOURNAL_NO_RETRY_ACCESS_NOT_ATTESTED"
            )
        except Exception:  # noqa: BLE001 - corrupted/unavailable journal must retain uncertainty without raw errors
            result = {
                "status": "BLOCKED_BEFORE_JOURNAL_OR_OUTCOME_UNKNOWN",
                "automaticRetryOrRollback": False,
            }
        print(json.dumps(result), flush=True)
        return 2
    print(json.dumps(result), flush=True)
    return 0


def entry():
    try:
        return run()
    except Exception:  # noqa: BLE001 - never print metadata, command stderr, credentials, or raw exceptions
        # An output failure can happen after a completed mutation. Never claim no action.
        try:
            print(
                json.dumps(
                    {
                        "status": "BLOCKED_INPUT_READ_OR_OUTPUT_FAILED_OUTCOME_UNCONFIRMED",
                        "automaticRetryOrRollback": False,
                    }
                ),
                flush=True,
            )
        except OSError:
            return 2
        return 2


if __name__ == "__main__":
    raise SystemExit(entry())
