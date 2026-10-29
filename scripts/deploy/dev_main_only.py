"""Reviewed DEV Main-only deployment. No automatic trigger, retry or rollback."""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
import urllib.request
import zlib

CONFIG = {}  # REVIEWED_CONFIG
SERVICES = ("main-service", "ai-service", "mysql", "nginx")
FILES = ("docker-compose.yml", "docker-compose.prod.yml", "docker-compose.dev.yml", "infra/nginx/edupilot.conf")
HEX = r"[a-f0-9]{64}"
GUARDIAN = ("EDUPILOT_GUARDIAN_TEAM_ENABLED", "EDUPILOT_GUARDIAN_TEAM_POLICY_CONFIRMED", "EDUPILOT_GUARDIAN_WEB_ENABLED")
TRIAL = ("EDUPILOT_MAIL_OUTBOX_DISPATCH_DELIVERY_IDS", "EDUPILOT_MAIL_OUTBOX_DISPATCH_RECIPIENT")
OVERRIDES = ("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "SPRING_APPLICATION_JSON",
             "SPRING_CONFIG_LOCATION", "SPRING_CONFIG_ADDITIONAL_LOCATION", "SPRING_CONFIG_IMPORT")


def require(condition, code):
    if not condition:
        raise ValueError(code)


def shape(value, fields):
    require(isinstance(value, dict) and set(value) == set(fields), "METADATA_SHAPE")


def is_hex(value, pattern=HEX):
    return isinstance(value, str) and re.fullmatch(pattern, value) is not None


def repo_digest(name, value):
    repo = "ghcr.io/autoai-uteum/" + name if name.endswith("-service") else name
    prefixes = (repo, "docker.io/library/" + repo) if name in ("mysql", "nginx") else (repo,)
    return isinstance(value, str) and any(re.fullmatch(re.escape(p) + "@sha256:" + HEX, value) for p in prefixes)


def fresh(timestamp, now=None):
    require(isinstance(timestamp, str), "PREFLIGHT_TIME_REQUIRED")
    checked = dt.datetime.fromisoformat(timestamp.replace("Z", "+00:00"))
    require(checked.tzinfo is not None, "PREFLIGHT_TIMEZONE_REQUIRED")
    age = ((now or dt.datetime.now(dt.timezone.utc)) - checked).total_seconds()
    require(0 <= age <= 1800, "REFRESH_READONLY_PREFLIGHT_AFTER_30_MINUTES")


def validate_manifest(value, expected_sha):
    shape(value, ("schemaVersion", "approvedSha", "preflightReceiptSha256", "checkedAtUtc", "services",
                  "configSha256", "flywayCatalogSha256", "rollback", "budget", "ci"))
    require(type(value["schemaVersion"]) is int and value["schemaVersion"] == 1 and is_hex(expected_sha, r"[a-f0-9]{40}")
            and value["approvedSha"] == expected_sha, "APPROVED_SHA_MUST_EQUAL_DISPATCH_SHA")
    fresh(value["checkedAtUtc"])
    require(is_hex(value["preflightReceiptSha256"]) and is_hex(value["flywayCatalogSha256"]), "PREFLIGHT_HASH_REQUIRED")
    shape(value["services"], SERVICES)
    for name, service in value["services"].items():
        shape(service, ("id", "imageId", "repoDigest"))
        require(is_hex(service["id"]) and is_hex(service["imageId"], "sha256:" + HEX)
                and repo_digest(name, service["repoDigest"]), "PINNED_RUNTIME_IDENTITIES_REQUIRED")
    shape(value["configSha256"], FILES)
    require(all(is_hex(d) for d in value["configSha256"].values()), "CONFIG_HASHES_REQUIRED")
    rollback = value["rollback"]
    shape(rollback, ("mainRepoDigest", "registryAvailabilityReviewed"))
    require(rollback["mainRepoDigest"] == value["services"]["main-service"]["repoDigest"]
            and rollback["registryAvailabilityReviewed"] is True,
            "REVIEWED_MAIN_ROLLBACK_REQUIRED")
    budget = value["budget"]
    shape(budget, ("confirmed", "reserved", "limit", "currentMainContainerId", "evidenceSha256"))
    require(all(type(budget[k]) is int for k in ("confirmed", "reserved", "limit"))
            and budget["confirmed"] >= 4 and budget["reserved"] >= 0 and budget["limit"] == 7
            and budget["confirmed"] + budget["reserved"] + 2 <= budget["limit"]
            and budget["currentMainContainerId"] == value["services"]["main-service"]["id"]
            and is_hex(budget["evidenceSha256"]), "CURRENT_BUDGET_MUST_COVER_MAIN_AND_MANUAL_RECOVERY")
    ci = value["ci"]
    shape(ci, ("headSha", "mainRunId", "aiRunId", "reviewedSuccess"))
    require(ci["headSha"] == expected_sha and ci["reviewedSuccess"] is True
            and all(type(ci[k]) is int and ci[k] > 0 for k in ("mainRunId", "aiRunId")),
            "EXACT_COMBINED_HEAD_MAIN_AI_CI_REVIEW_REQUIRED")
    return value


def sha_json(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def source_catalog(root):
    catalog = {}
    for path in (root / "main-service/src/main/resources/db/migration").glob("V*__*.sql"):
        version = re.fullmatch(r"V(\d+)__.*\.sql", path.name)[1]
        require(version not in catalog, "DUPLICATE_MIGRATION_VERSION")
        crc = 0
        for line in path.read_text(encoding="utf-8-sig").splitlines():
            crc = zlib.crc32(line.encode("utf-8"), crc)
        catalog[version] = crc if crc < 2**31 else crc - 2**32
    require(set(catalog) == {str(v) for v in range(1, 65)} and catalog["64"] == -1987207835,
            "V64_ONLY_RELEASE_EXPECTED")
    return catalog


def prepare(root, output, value, expected_sha, fingerprint):
    validate_manifest(value, expected_sha)
    require(isinstance(fingerprint, str) and re.fullmatch(r"SHA256:[A-Za-z0-9+/]{43}", fingerprint),
            "EXISTING_VERIFIED_HOST_FINGERPRINT_REQUIRED")
    catalog = source_catalog(root)
    require(value["flywayCatalogSha256"] == sha_json(catalog), "REVIEWED_CATALOG_MISMATCH")
    require(value["configSha256"] == {p: hashlib.sha256((root / p).read_bytes()).hexdigest() for p in FILES},
            "REVIEWED_HOST_CONFIG_MUST_MATCH_CHECKOUT")
    source = Path(__file__).read_text(encoding="utf-8")
    marker = "CONFIG = {}" + "  # REVIEWED_CONFIG"
    require(source.count(marker) == 1, "REMOTE_CONFIG_MARKER_REQUIRED")
    source = source.replace(marker, "CONFIG = " + repr({"manifest": value, "catalog": catalog}), 1)
    compile(source, "<reviewed-dev-main-only>", "exec")
    delimiter = "EDUPILOT_REVIEWED_MAIN_ONLY_PY"
    require(delimiter not in source.splitlines(), "HEREDOC_DELIMITER_COLLISION")
    script = "set -eu\npython3 -I -S -B - --remote <<'" + delimiter + "'\n" + source + "\n" + delimiter + "\n"
    with output.open("x", encoding="utf-8", newline="\n") as stream:
        stream.write(script)


class Deployment:
    def __init__(self, root, manifest, catalog, new_digest):
        self.root, self.manifest, self.catalog = root, manifest, catalog
        require(repo_digest("main-service", new_digest), "NEW_MAIN_REGISTRY_DIGEST_REQUIRED")
        require(new_digest != manifest["services"]["main-service"]["repoDigest"], "NEW_MAIN_IMAGE_ALREADY_CURRENT")
        self.new_digest = new_digest
        self.main_recreation_requested = False
        self.nginx_reload_requested = False

    def command(self, args, *, data=None, timeout=20):
        # No command/exception/Env/raw stdout is logged, including on failure.
        result = subprocess.run(args, cwd=self.root, input=data, stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, timeout=timeout)
        require(result.returncode == 0 and len(result.stdout) <= 4 * 1024 * 1024, "COMMAND_FAILED_STOP_NO_RETRY")
        return result.stdout.decode("utf-8")

    def docker(self, *args, data=None, timeout=20):
        return self.command(["docker", *args], data=data, timeout=timeout)

    def identities(self):
        services = {}
        for name in SERVICES:
            ids = self.docker("ps", "--all", "--quiet", "--no-trunc", "--filter",
                              "label=com.docker.compose.project=edupilot", "--filter",
                              "label=com.docker.compose.service=" + name).split()
            require(len(ids) == 1 and is_hex(ids[0]), "CURRENT_SERVICE_IDENTITY_AMBIGUOUS")
            fmt = ('{"imageId":{{json .Image}},"running":{{.State.Running}},'
                   '"health":{{if .State.Health}}{{json .State.Health.Status}}{{else}}null{{end}}}')
            info = json.loads(self.docker("inspect", "--format", fmt, ids[0]))
            require(info["running"] is True, "CURRENT_SERVICE_NOT_RUNNING")
            digests = json.loads(self.docker("image", "inspect", "--format", "{{json .RepoDigests}}", info["imageId"])) or []
            expected = self.manifest["services"][name]
            services[name] = {"id": ids[0], "imageId": info["imageId"], "repoDigests": digests, "health": info["health"]}
            require(is_hex(info["imageId"], "sha256:" + HEX), "IMAGE_ID_SHAPE")
        return services

    def main_environment(self, cid):
        # Raw Env is compared only in memory; it never enters a receipt or logs.
        lines = json.loads(self.docker("inspect", "--format", "{{json .Config.Env}}", cid))
        env = {}
        for line in lines:
            key, sep, value = line.partition("=")
            require(sep and key not in env, "CURRENT_ENV_AMBIGUOUS")
            env[key] = value
        return env

    def nginx_signup_pause(self, cid):
        # The pause setting belongs to Nginx, not Main (docker-compose.prod.yml).
        template = '{{range .Config.Env}}{{if eq (index (split . "=") 0) "EDUPILOT_SIGNUP_PAUSED"}}{{println .}}{{end}}{{end}}'
        lines = [line for line in self.docker("inspect", "--format", template, cid).splitlines() if line]
        require(len(lines) == 1 and lines[0] in ("EDUPILOT_SIGNUP_PAUSED=true", "EDUPILOT_SIGNUP_PAUSED=false"),
                "NGINX_SIGNUP_PAUSE_INPUT_UNCONFIRMED")
        return lines[0].split("=", 1)[1]

    def verify_readonly(self):
        fresh(self.manifest["checkedAtUtc"])
        require(all(hashlib.sha256((self.root / p).read_bytes()).hexdigest() == self.manifest["configSha256"][p] for p in FILES),
                "HOST_CONFIG_CHANGED_SINCE_PREFLIGHT")
        before = self.identities()
        for name in SERVICES:
            expected = self.manifest["services"][name]
            require(before[name]["id"] == expected["id"] and before[name]["imageId"] == expected["imageId"]
                    and expected["repoDigest"] in before[name]["repoDigests"], "RUNTIME_CHANGED_SINCE_PREFLIGHT")
        require(all(before[n]["health"] == "healthy" for n in ("main-service", "ai-service", "mysql")), "CURRENT_SERVICES_NOT_HEALTHY")
        env = self.main_environment(before["main-service"]["id"])
        entry = json.loads(self.docker("inspect", "--format", '{"entrypoint":{{json .Config.Entrypoint}},"cmd":{{json .Config.Cmd}}}', before["main-service"]["id"]))
        require(entry["entrypoint"] == ["java", "-jar", "/app/app.jar"] and not entry["cmd"], "CURRENT_MAIN_COMMAND_UNREVIEWED")
        require(env.get("SPRING_PROFILES_ACTIVE") == "dev" and env.get("EDUPILOT_MAIL_PROVIDER") == "logging"
                and env.get("EDUPILOT_MAIL_OUTBOX_DISPATCH_MODE") == "PAUSED"
                and all(env.get(k) == "false" for k in GUARDIAN)
                and all(not env.get(k) and not os.environ.get(k) for k in (*TRIAL, *OVERRIDES)), "MAIL_GUARDIAN_OR_OVERRIDE_DRIFT")
        marker = "true" if (self.root / ".dev-signup-paused").is_file() else "false"
        require(self.nginx_signup_pause(before["nginx"]["id"]) == marker, "NGINX_SIGNUP_PAUSE_INPUT_DRIFT")
        require(env.get("EDUPILOT_POLICY_SIGNUP_CONSENT_REQUIRED") in ("true", "false"), "CONSENT_INPUT_UNCONFIRMED")
        for line in (self.root / ".env").read_text(encoding="utf-8-sig").splitlines():
            match = re.match(r"^\s*(?:export\s+)?(" + "|".join(TRIAL) + r")\s*[=:]\s*(.*)$", line)
            require(not match or match[2].strip() in ("", "''", '""'), "DOTENV_TRIAL_INPUTS_NOT_EMPTY")
        sql = ("SET SESSION TRANSACTION READ ONLY;\nSTART TRANSACTION READ ONLY;\n"
               "SELECT version, checksum, success FROM flyway_schema_history ORDER BY installed_rank;\nROLLBACK;\n")
        mysql = 'MYSQL_PWD="${MYSQL_PASSWORD:?}" exec mysql --user="${MYSQL_USER:?}" --database="${MYSQL_DATABASE:?}" --batch --raw --skip-column-names --connect-timeout=5'
        rows = self.docker("exec", "-i", before["mysql"]["id"], "sh", "-c", mysql, data=sql.encode()).splitlines()
        actual = {}
        for row in rows:
            parts = row.split("\t")
            require(len(parts) == 3 and parts[0] in self.catalog and parts[0] not in actual and parts[2] == "1", "FLYWAY_HISTORY_DRIFT")
            actual[parts[0]] = int(parts[1])
        require(actual == self.catalog, "FLYWAY_NOT_EXACT_V64_STOP_NO_REPAIR")
        self.docker("exec", before["nginx"]["id"], "nginx", "-t")
        return before, env, marker

    def apply(self):
        before, old_env, marker = self.verify_readonly()
        os.environ["EDUPILOT_SIGNUP_PAUSED"] = marker
        os.environ["ENVIRONMENT"] = "dev"
        os.environ["TAG"] = self.manifest["approvedSha"]
        # Existing Docker credentials/.env/Compose files are never overwritten.
        with tempfile.TemporaryDirectory(prefix="edupilot-dev-main-only-") as directory:
            temp = Path(directory)
            override = temp / "reviewed-images.yml"
            images = {n: {"image": self.new_digest if n == "main-service" else self.manifest["services"][n]["repoDigest"]} for n in SERVICES}
            with override.open("x", encoding="utf-8") as stream:
                json.dump({"services": images}, stream)
            compose = ["--config", directory, "compose", "--env-file", ".env",
                       "-f", "docker-compose.yml", "-f", "docker-compose.prod.yml", "-f", "docker-compose.dev.yml", "-f", str(override)]
            resolved = json.loads(self.docker(*compose, "config", "--format", "json"))
            require(all(resolved["services"][n]["image"] == images[n]["image"] for n in SERVICES), "DIGEST_OVERRIDE_NOT_EFFECTIVE")
            desired_env = resolved["services"]["main-service"]["environment"]
            require(all(old_env.get(k) == str(v) for k, v in desired_env.items()), "MAIN_SETTINGS_OR_CREDENTIALS_WOULD_CHANGE")
            protected = ("EDUPILOT_", "SPRING_", "AWS_", "GOOGLE_", "DB_")
            require(all(k in desired_env for k in old_env if k.startswith(protected)), "CURRENT_APPLICATION_SETTING_WOULD_BE_DROPPED")
            token = os.environ.pop("GHCR_TOKEN", "")
            actor = os.environ.get("GHCR_USERNAME", "")
            require(token and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,100}", actor), "EXISTING_JOB_GHCR_AUTH_REQUIRED")
            self.docker("--config", directory, "login", "ghcr.io", "--username", actor, "--password-stdin", data=token.encode())
            token = None
            self.docker("--config", directory, "manifest", "inspect", self.manifest["rollback"]["mainRepoDigest"])
            # Only the new Main digest is pulled. Infrastructure/AI images are never pulled.
            self.docker("--config", directory, "pull", self.new_digest, timeout=180)
            target_id = self.docker("image", "inspect", "--format", "{{.Id}}", self.new_digest).strip()
            require(is_hex(target_id, "sha256:" + HEX), "NEW_MAIN_IMAGE_ID_UNCONFIRMED")
            target = json.loads(self.docker("image", "inspect", "--format", "{{json .Config}}", self.new_digest))
            require(target.get("Entrypoint") == ["java", "-jar", "/app/app.jar"] and not target.get("Cmd"), "NEW_MAIN_ENTRYPOINT_MISMATCH")
            target_env = dict(line.split("=", 1) for line in target.get("Env", []))
            require(all(not target_env.get(k) for k in (*TRIAL, *OVERRIDES)), "NEW_IMAGE_ENV_OVERRIDE_UNREVIEWED")
            require(all(k in desired_env or k in target_env for k in old_env), "CURRENT_EXTRA_ENV_WOULD_BE_DROPPED")
            # Repeat all read-only guards immediately before the single allowed replacement.
            current, current_env, current_marker = self.verify_readonly()
            require(current == before and current_env == old_env and current_marker == marker, "PREFLIGHT_CHANGED_BEFORE_MAIN_REPLACEMENT")
            self.main_recreation_requested = True
            self.docker(*compose, "up", "-d", "--no-deps", "--no-build", "--pull", "never", "main-service", timeout=120)
            after = None
            for _ in range(60):
                after = self.identities()
                require(all(after[n]["id"] == before[n]["id"] and after[n]["imageId"] == before[n]["imageId"] for n in SERVICES if n != "main-service"), "EXCLUDED_SERVICE_DRIFT_STOP_NO_ROLLBACK")
                require(after["main-service"]["imageId"] == target_id, "MAIN_TARGET_IMAGE_MISMATCH")
                if after["main-service"]["health"] == "healthy":
                    break
                time.sleep(2)
            require(after is not None and after["main-service"]["health"] == "healthy", "MAIN_HEALTH_FAILED_MANUAL_RECOVERY_REQUIRED")
            require(after["main-service"]["id"] != before["main-service"]["id"]
                    and self.new_digest in after["main-service"]["repoDigests"], "MAIN_REPLACEMENT_NOT_ATTESTED")
            new_env = self.main_environment(after["main-service"]["id"])
            require(all(new_env.get(k) == old_env.get(k) for k in desired_env)
                    and all(not new_env.get(k) for k in (*TRIAL, *OVERRIDES)), "POST_MAIN_SETTINGS_OR_CREDENTIAL_DRIFT")
            # New Main IP must be resolved without recreating/restarting Nginx.
            self.docker("exec", before["nginx"]["id"], "nginx", "-t")
            self.nginx_reload_requested = True
            self.docker("exec", before["nginx"]["id"], "nginx", "-s", "reload")
            for path in ("/api/health", "/api/health/ready"):
                with urllib.request.urlopen("https://dev.uteum.com" + path, timeout=15) as response:
                    health = json.loads(response.read(65536))
                if path.endswith("ready"):
                    require(health.get("status") == "UP" and health.get("checks") == {"db": "UP", "aiService": "UP"},
                            "READINESS_FAILED_NO_AI_RESTART")
                else:
                    require(health.get("data", {}).get("status") == "UP", "HEALTH_FAILED_MANUAL_RECOVERY_REQUIRED")
            final = self.identities()
            require(final == after, "CONTAINERS_CHANGED_DURING_FINAL_READ")
            return {"status": "MAIN_ONLY_DEPLOYED_READBACK_PASSED", "approvedSha": self.manifest["approvedSha"],
                    "mainImageId": target_id, "mainRepoDigest": self.new_digest,
                    "mainContainerId": final["main-service"]["id"], "excludedContainerIdsPreserved": True,
                    "mainRecreationRequested": True, "nginxReloadRequested": True,
                    "mysqlAiNginxImagesChanged": False, "automaticRetryOrRollback": False}


def run():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--prepare", action="store_true")
    mode.add_argument("--remote", action="store_true")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.prepare:
        require(os.environ.get("GITHUB_EVENT_NAME") == "workflow_dispatch" and os.environ.get("GITHUB_REF") == "refs/heads/develop", "MANUAL_DEVELOP_DISPATCH_ONLY")
        value = json.loads(os.environ.get("REVIEWED_MANIFEST", ""))
        require(args.output is not None, "OUTPUT_SCRIPT_REQUIRED")
        prepare(Path.cwd(), args.output, value, os.environ.get("GITHUB_SHA"), os.environ.get("VERIFIED_HOST_FINGERPRINT"))
        print(json.dumps({"status": "REVIEWED_MAIN_ONLY_PAYLOAD_PREPARED", "serverConnections": 0}))
        return 0
    manifest = CONFIG.get("manifest", {})
    validate_manifest(manifest, manifest.get("approvedSha"))
    root = Path(os.environ.get("DEV_DEPLOY_PATH", ""))
    require(root.is_absolute() and root != Path("/") and root.is_dir() and root.resolve() == root, "EXISTING_CANONICAL_DEPLOY_PATH_REQUIRED")
    controller = Deployment(root, manifest, CONFIG["catalog"], os.environ.get("NEW_MAIN_DIGEST", ""))
    try:
        result = controller.apply()
    except Exception:
        result = {"status": "BLOCKED_STOP_NO_AUTOMATIC_RETRY_OR_ROLLBACK",
                  "mainRecreationRequested": controller.main_recreation_requested,
                  "nginxReloadRequested": controller.nginx_reload_requested,
                  "automaticRetryOrRollback": False}
    print(json.dumps(result, ensure_ascii=True), flush=True)
    return 0 if result["status"] == "MAIN_ONLY_DEPLOYED_READBACK_PASSED" else 2


if __name__ == "__main__":
    try:
        raise SystemExit(run())
    except Exception:
        print(json.dumps({"status": "BLOCKED_INPUT_OR_READ_FAILED_NO_ACTION", "automaticRetryOrRollback": False}))
        raise SystemExit(2)
