#!/usr/bin/env bash
# Preparation defaults to metadata-only plan. Execution requires a separately approved manifest hash.
set -euo pipefail
export LC_ALL=C
umask 077

blocked() { printf 'SES_TRIAL_WRAPPER %s\n' "$1"; exit 2; }
if [[ $# == 2 ]]; then
  mode=plan; manifest=$1; artifact=$2; approved_hash=
elif [[ $# == 3 || $# == 4 ]]; then
  mode=$1; manifest=$2; artifact=$3; approved_hash=${4:-}
else
  blocked BLOCKED_ARGUMENTS
fi
[[ $mode == plan || $mode == identity || $mode == execute ]] || blocked BLOCKED_MODE
[[ $mode != plan || $# != 4 ]] || blocked BLOCKED_ARGUMENTS
[[ -f $manifest && -f $artifact ]] || blocked BLOCKED_FILES
[[ $(wc -c < "$manifest") -le 16384 ]] || blocked BLOCKED_MANIFEST_SIZE

declare -A fields=()
while IFS= read -r line || [[ -n $line ]]; do
  line=${line%$'\r'}
  [[ -z $line || $line == \#* ]] && continue
  [[ $line == *=* && $line != *[!\ -\~]* ]] || blocked BLOCKED_MANIFEST_SYNTAX
  key=${line%%=*}; value=${line#*=}
  case "$key" in
    schema|trialId|environment|sourceSha|artifactSha256|containerId|imageId|composeProject|awsAccountId|awsIdentityEvidenceRef|from|recipient|recipientAlias|region|maxMessages|stateDirectory|authorizationRef|observedProvider|observedEnabled|approvedOperation) ;;
    *) blocked BLOCKED_MANIFEST_KEY ;;
  esac
  [[ ! -v fields[$key] ]] || blocked BLOCKED_DUPLICATE_KEY
  fields[$key]=$value
done < "$manifest"
[[ ${#fields[@]} == 20 ]] || blocked BLOCKED_MISSING_KEY
[[ ${fields[schema]} == 1 && ${fields[environment]} == dev ]] || blocked BLOCKED_SCOPE
[[ ${fields[from]} == no-reply@uteum.com && ${fields[region]} == ap-northeast-2 ]] || blocked BLOCKED_SCOPE
[[ ${fields[recipientAlias]} == APPROVED_INBOX_1 ]] || blocked BLOCKED_BUDGET
case "${fields[approvedOperation]}" in
  NONE|IDENTITY) [[ ${fields[maxMessages]} == 0 ]] || blocked BLOCKED_BUDGET ;;
  SEND) [[ ${fields[maxMessages]} == 1 ]] || blocked BLOCKED_BUDGET ;;
  *) blocked BLOCKED_OPERATION ;;
esac
[[ ${fields[observedProvider]} == logging && ${fields[observedEnabled]} == true ]] || blocked BLOCKED_PROVIDER
[[ ${fields[trialId]} =~ ^[a-z0-9][a-z0-9-]{0,63}$ ]] || blocked BLOCKED_TRIAL_ID
[[ ${fields[sourceSha]} =~ ^[a-f0-9]{40}$ ]] || blocked BLOCKED_SOURCE_SHA
[[ ${fields[artifactSha256]} =~ ^[a-f0-9]{64}$ ]] || blocked BLOCKED_ARTIFACT_SHA
[[ ${fields[containerId]} =~ ^[a-f0-9]{64}$ ]] || blocked BLOCKED_CONTAINER_ID
[[ ${fields[imageId]} =~ ^sha256:[a-f0-9]{64}$ ]] || blocked BLOCKED_IMAGE_ID
[[ ${fields[composeProject]} =~ ^[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}$ ]] || blocked BLOCKED_PROJECT
[[ ${fields[recipient]} =~ ^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,63}$ ]] || blocked BLOCKED_RECIPIENT
[[ ${fields[stateDirectory]} =~ ^/[a-zA-Z0-9_/-]+$ && ${fields[stateDirectory]} != / && ${fields[stateDirectory]} != *//* ]] || blocked BLOCKED_STATE_PATH

hash_file() { local result; result=$(sha256sum -- "$1"); printf '%s' "${result%% *}"; }
manifest_hash=$(hash_file "$manifest")
[[ $(hash_file "$artifact") == "${fields[artifactSha256]}" ]] || blocked BLOCKED_ARTIFACT_MISMATCH
if [[ $mode != plan ]]; then
  [[ $approved_hash =~ ^[a-f0-9]{64}$ && $approved_hash == "$manifest_hash" ]] || blocked BLOCKED_APPROVED_HASH
  [[ ${fields[awsAccountId]} =~ ^[0-9]{12}$ ]] || blocked BLOCKED_ACCOUNT_ATTESTATION
  [[ ${fields[authorizationRef]} =~ ^[a-zA-Z0-9:_/.-]{1,200}$ ]] || blocked BLOCKED_AUTHORIZATION
  if [[ $mode == identity ]]; then
    [[ ${fields[approvedOperation]} == IDENTITY ]] || blocked BLOCKED_OPERATION
  else
    [[ ${fields[approvedOperation]} == SEND && ${fields[awsIdentityEvidenceRef]} =~ ^[a-zA-Z0-9:_/.-]{1,200}$ ]] || blocked BLOCKED_AUTHORIZATION
  fi
fi

# Inspect selected metadata only. Never dump Config.Env, whole inspect, or credentials.
metadata=$(docker inspect --format '{{.Id}}|{{.Image}}|{{index .Config.Labels "com.docker.compose.project"}}|{{index .Config.Labels "com.docker.compose.service"}}|{{.State.Running}}' "${fields[containerId]}" 2>/dev/null) || blocked BLOCKED_CONTAINER_INSPECT
expected="${fields[containerId]}|${fields[imageId]}|${fields[composeProject]}|main-service|true"
[[ $metadata == "$expected" ]] || blocked BLOCKED_CONTAINER_METADATA
if [[ $mode == plan ]]; then
  printf 'SES_TRIAL_WRAPPER PLAN_NO_SEND metadataMatched=true runtimeEnvironmentUnchecked=true\n'
  exit 0
fi

# This pre-approved HOST directory must persist independently of Docker containers/volumes.
# Neither the wrapper nor the command creates the state root or removes an existing claim.
state_dir=${fields[stateDirectory]}
[[ -d $state_dir && ! -L $state_dir && -w $state_dir ]] || blocked BLOCKED_STATE_DIRECTORY
canonical=$(cd -- "$state_dir" && pwd -P)
[[ $canonical == "$state_dir" ]] || blocked BLOCKED_STATE_CANONICAL_PATH
claim_suffix=consumed
command_mode=--execute
if [[ $mode == identity ]]; then claim_suffix=identity-read; command_mode=--identity; fi
claim="$state_dir/${fields[trialId]}.$claim_suffix"
mkdir -- "$claim" 2>/dev/null || blocked BLOCKED_CONSUMED_BUDGET
printf 'manifestSha256=%s\nsourceSha=%s\ncontainerId=%s\nstatus=CONSUMED_BEFORE_SDK\n' \
  "$manifest_hash" "${fields[sourceSha]}" "${fields[containerId]}" > "$claim/scope.txt"

# All failures after this point consume the one-send budget, including transport uncertainty.
# Rechecking hashes narrows input races; the Java command independently hashes its JAR/stdin.
[[ $(hash_file "$manifest") == "$manifest_hash" && $(hash_file "$artifact") == "${fields[artifactSha256]}" ]] || blocked BLOCKED_INPUT_CHANGED_BUDGET_CONSUMED
staged="/tmp/uteum-ses-trial-${fields[trialId]}.jar"
if ! docker cp -- "$artifact" "${fields[containerId]}:$staged" >/dev/null 2>&1; then
  printf 'SES_TRIAL_WRAPPER STAGE_FAILED_BUDGET_CONSUMED\n'
  exit 4
fi

# Environment options affect this separate child process only. The running main service,
# provider, credentials, scheduler, users, database and outbox are never reconfigured.
# Pin PropertiesLauncher inputs; inherited Java/loader options must not select another main.
set +e
docker exec -i \
  -e "EDUPILOT_SES_TRIAL_HOST_CLAIM=$manifest_hash" \
  -e LOADER_MAIN=io.edupilot.mail.IsolatedSesTrial -e LOADER_PATH= -e LOADER_HOME=/tmp \
  -e LOADER_CONFIG_LOCATION=classpath:uteum-ses-trial-no-external-config.properties \
  -e LOADER_CONFIG_NAME=uteum-ses-trial-no-external-config -e LOADER_SYSTEM=false \
  -e 'LOADER_ARGS= ' -e LOADER_DEBUG=false -e JAVA_TOOL_OPTIONS= -e JDK_JAVA_OPTIONS= -e _JAVA_OPTIONS= \
  "${fields[containerId]}" java -XX:-UsePerfData -Djava.io.tmpdir=/tmp -cp "$staged" \
  org.springframework.boot.loader.launch.PropertiesLauncher \
  "$command_mode" --manifest-sha256 "$manifest_hash" < "$manifest"
result=$?
set -e
printf 'commandExitCode=%s\n' "$result" >> "$claim/scope.txt"
if [[ $mode != identity ]]; then
  printf 'SES_TRIAL_WRAPPER BUDGET_CONSUMED commandExitCode=%s\n' "$result"
fi
exit "$result"
