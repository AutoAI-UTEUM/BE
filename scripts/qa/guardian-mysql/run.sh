#!/usr/bin/env bash
set -euo pipefail

# This script is only for GitHub-hosted Linux; never probes local Docker/Windows.
test "${GITHUB_ACTIONS:-}" = true
test "${RUNNER_OS:-}" = Linux
test "${RUNNER_ENVIRONMENT:-}" = github-hosted
[[ "${MYSQL_SERVICE_ID:-}" =~ ^[0-9a-f]{64}$ ]]
[[ "${GITHUB_RUN_ID:-}" =~ ^[0-9]+$ ]]
[[ "${GITHUB_RUN_ATTEMPT:-}" =~ ^[0-9]+$ ]]
network="guardian-mysql-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}"
test_container="guardian-tests-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}"
gradle_home="$RUNNER_TEMP/guardian-gradle"
image=eclipse-temurin:21-jdk-jammy

cleanup() {
  local result=$?
  trap - EXIT
  if docker container inspect "$test_container" >/dev/null 2>&1; then
    docker rm --force "$test_container" || result=1
  fi
  if docker network inspect "$network" >/dev/null 2>&1; then
    docker network disconnect --force "$network" "$MYSQL_SERVICE_ID" || result=1
    docker network rm "$network" || result=1
  fi
  echo "GUARDIAN_ISOLATION_CLEANUP test_container_and_network_removed result=$result"
  # GitHub's post-job Stop containers removes the MySQL service and its tmpfs.
  exit "$result"
}
trap cleanup EXIT

# Fail before any SQL if this is not the exact service created for this job.
docker inspect "$MYSQL_SERVICE_ID" > "$RUNNER_TEMP/guardian-mysql-inspect.json"
python3 -I -S -B - "$RUNNER_TEMP/guardian-mysql-inspect.json" <<'PY'
import json, sys
service = json.load(open(sys.argv[1], encoding="utf-8"))[0]
assert service["Config"]["Image"] == "mysql:8.4.11"
assert service["State"]["Health"]["Status"] == "healthy"
assert not service["HostConfig"].get("PortBindings")
assert "/var/lib/mysql" in service["HostConfig"].get("Tmpfs", {})
assert all(m["Type"] == "tmpfs" for m in service["Mounts"])
print("GUARDIAN_SERVICE verified image=mysql:8.4.11 host_ports=none datadir=tmpfs")
PY

mkdir -p "$gradle_home"
docker pull "$image"
# Resolve dependencies while online, without executing tests or passing any app credentials.
docker run --rm --user "$(id -u):$(id -g)" --cap-drop ALL --security-opt no-new-privileges \
  --mount "type=bind,src=$GITHUB_WORKSPACE/main-service,dst=/workspace" \
  --mount "type=bind,src=$gradle_home,dst=/gradle-home" \
  --env GRADLE_USER_HOME=/gradle-home --workdir /workspace "$image" \
  sh ./gradlew --no-daemon --max-workers=2 guardianMysqlPrepare

docker network create --internal "$network"
docker network connect --alias guardian-mysql "$network" "$MYSQL_SERVICE_ID"
# The MySQL service too is reachable only on this internal network during tests.
for original_network in $(docker inspect --format '{{range $name, $_ := .NetworkSettings.Networks}}{{println $name}}{{end}}' "$MYSQL_SERVICE_ID"); do
  if [[ "$original_network" != "$network" ]]; then
    docker network disconnect "$original_network" "$MYSQL_SERVICE_ID"
  fi
done
test "$(docker network inspect --format '{{.Internal}}' "$network")" = true

# docker exec selects the job service by container ID; it cannot follow an external DB URL.
server_uuid="$(docker exec --env MYSQL_PWD=guardian-job-only "$MYSQL_SERVICE_ID" \
  mysql -uroot --batch --skip-column-names -e 'select @@server_uuid')"
[[ "$server_uuid" =~ ^[0-9a-f-]{36}$ ]]
docker exec --env MYSQL_PWD=guardian-job-only "$MYSQL_SERVICE_ID" mysql -uroot -e '
  CREATE DATABASE guardian_consent_fence_mysql_synthetic;
  CREATE DATABASE guardian_admin_lock_jpa_synthetic;
  CREATE DATABASE guardian_team_full_migration_synthetic;
  CREATE DATABASE guardian_turn_persistence_mysql_synthetic;'
echo "GUARDIAN_IDENTITY server_uuid=$server_uuid datadir=/var/lib/mysql/ server_port=3306 schemas=4"

# No host networking, Docker socket, home directory, secrets, tokens or env-file is mounted/passed.
docker create --name "$test_container" --network "$network" --user "$(id -u):$(id -g)" \
  --cap-drop ALL --security-opt no-new-privileges \
  --mount "type=bind,src=$GITHUB_WORKSPACE/main-service,dst=/workspace" \
  --mount "type=bind,src=$gradle_home,dst=/gradle-home" \
  --env GRADLE_USER_HOME=/gradle-home --env GITHUB_ACTIONS=true \
  --env GUARDIAN_MYSQL_ISOLATED=true --env "GUARDIAN_MYSQL_SERVER_UUID=$server_uuid" \
  --workdir /workspace "$image" \
  sh ./gradlew --offline --no-daemon --max-workers=2 guardianMysqlTest
test "$(docker inspect --format '{{.HostConfig.NetworkMode}}' "$test_container")" = "$network"
echo 'GUARDIAN_ISOLATION internal_network=true offline_gradle=true endpoint=guardian-mysql:3306'
docker start --attach "$test_container"
test "$(docker inspect --format '{{.State.ExitCode}}' "$test_container")" = 0
