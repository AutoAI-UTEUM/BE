"""One OpenSSH attempt with the existing DEV ED25519 key; no trust update or retry."""
import argparse
import base64
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tempfile

PIN = 'SHA256:/Z0f0DNGFvMnV+S5IaSgrv4ZJ6jwSYAQsZKyDOHfXcE'
PUBLIC_KEY = 'ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIJJ2Uw2la9XCSYQ2Fk0AoOpUxDRTG/+63Gqq6WAeGMSR'
ALIAS = 'dev.uteum.com'
REMOTE_NAMES = ('NEW_MAIN_DIGEST', 'DEV_DEPLOY_PATH', 'GHCR_TOKEN', 'GHCR_USERNAME')
ASKPASS = '''#!/usr/bin/python3 -I
import os
import sys
if len(sys.argv) != 2 or not sys.argv[1].startswith("Enter passphrase for key "):
    raise SystemExit(1)
sys.stdout.buffer.write(os.environ.get("EDUPILOT_SSH_KEY_PASSPHRASE", "").encode("utf-8") + b"\\n")
'''


def require(value, code):
    if not value:
        raise ValueError(code)


def pinned_public_key(value=PUBLIC_KEY):
    kind, data = value.split(' ')
    blob = base64.b64decode(data, validate=True)
    require(kind == 'ssh-ed25519' and len(blob) == 51
            and blob[:19] == b'\x00\x00\x00\x0bssh-ed25519\x00\x00\x00\x20', 'ED25519_KEY_SHAPE')
    fingerprint = 'SHA256:' + base64.b64encode(hashlib.sha256(blob).digest()).decode('ascii').rstrip('=')
    require(fingerprint == PIN, 'EXISTING_ED25519_TRUST_MUST_NOT_CHANGE')
    return ALIAS + ' ' + value + '\n'


def target(host, user):
    require(isinstance(host, str) and 0 < len(host) <= 253, 'EXISTING_SINGLE_HOST_REQUIRED')
    try:
        address = ipaddress.ip_address(host)
    except ValueError:
        labels = host.split('.')
        require(len(labels) >= 2 and all(re.fullmatch(r'[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?', p)
                                        for p in labels), 'EXISTING_SINGLE_HOST_REQUIRED')
    else:
        require(address.is_global, 'EXISTING_PUBLIC_DEV_HOST_REQUIRED')
    require(isinstance(user, str) and re.fullmatch(r'[a-z_][a-z0-9_-]{0,31}', user), 'EXISTING_USER_REQUIRED')


def ssh_command(host, user, identity, known):
    target(host, user)
    options = (
        'HostName=' + host, 'HostKeyAlias=' + ALIAS, 'HostKeyAlgorithms=ssh-ed25519',
        'StrictHostKeyChecking=yes', 'UpdateHostKeys=no', 'CheckHostIP=no', 'VerifyHostKeyDNS=no',
        'UserKnownHostsFile="' + str(known) + '"', 'GlobalKnownHostsFile=/dev/null', 'KnownHostsCommand=none',
        'IdentitiesOnly=yes', 'IdentityAgent=none', 'AddKeysToAgent=no', 'PreferredAuthentications=publickey',
        'PasswordAuthentication=no', 'KbdInteractiveAuthentication=no', 'NumberOfPasswordPrompts=1',
        'BatchMode=no', 'ForwardAgent=no', 'ForwardX11=no', 'ClearAllForwardings=yes',
        'ProxyCommand=none', 'ProxyJump=none', 'ControlMaster=no', 'ControlPath=none',
        'CanonicalizeHostname=no', 'PermitLocalCommand=no', 'ConnectionAttempts=1',
        'ConnectTimeout=10', 'LogLevel=ERROR',
    )
    command = ['/usr/bin/ssh', '-F', '/dev/null', '-T', '-p', '22', '-l', user, '-i', str(identity)]
    for option in options:
        command += ['-o', option]
    return command + [ALIAS, 'sh -s']


def remote_input(script, env):
    require(isinstance(script, bytes) and 0 < len(script) <= 4 * 1024 * 1024
            and script.startswith(b"set -eu\npython3 -I -S -B - --remote <<'EDUPILOT_REVIEWED_MAIN_ONLY_PY'\n"),
            'EXISTING_REVIEWED_MAIN_ONLY_SCRIPT_REQUIRED')
    exports = ['set -eu']
    for name in REMOTE_NAMES:
        value = env.get(name)
        require(isinstance(value, str) and 0 < len(value) <= 16384
                and not any(c in value for c in '\x00\r\n'), 'EXISTING_REMOTE_INPUT_REQUIRED')
        exports.append('export ' + name + '=' + shlex.quote(value))
    return ('\n'.join(exports) + '\n').encode('utf-8') + script


def private_file(path, data, mode=0o600):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, mode)
    with os.fdopen(fd, 'wb') as stream:
        stream.write(data)


def remote_result(raw, returncode):
    require(isinstance(raw, bytes) and len(raw) <= 1024 * 1024, 'REMOTE_OUTPUT_UNCONFIRMED')
    value = json.loads(raw.decode('utf-8'))
    require(isinstance(value, dict) and value.get('automaticRetryOrRollback') is False, 'REMOTE_RESULT_UNCONFIRMED')
    status = value.get('status')
    if status == 'MAIN_ONLY_DEPLOYED_READBACK_PASSED':
        require(returncode == 0, 'SSH_EXIT_NOT_SUCCESS')
        allowed = {'status', 'approvedSha', 'mainImageId', 'mainRepoDigest', 'mainContainerId',
                   'excludedContainerIdsPreserved', 'mainRecreationRequested', 'nginxReloadRequested',
                   'mysqlAiNginxImagesChanged', 'automaticRetryOrRollback'}
        require(set(value) == allowed and value['mainRecreationRequested'] is True
                and value['nginxReloadRequested'] is True and value['excludedContainerIdsPreserved'] is True
                and value['mysqlAiNginxImagesChanged'] is False
                and re.fullmatch(r'[a-f0-9]{40}', value['approvedSha'])
                and re.fullmatch(r'sha256:[a-f0-9]{64}', value['mainImageId'])
                and re.fullmatch(r'[a-f0-9]{64}', value['mainContainerId'])
                and re.fullmatch(r'ghcr\.io/autoai-uteum/main-service@sha256:[a-f0-9]{64}', value['mainRepoDigest']),
                'REMOTE_SUCCESS_SHAPE')
    elif status == 'BLOCKED_STOP_NO_AUTOMATIC_RETRY_OR_ROLLBACK':
        require(set(value) == {'status', 'mainRecreationRequested', 'nginxReloadRequested', 'automaticRetryOrRollback'}
                and type(value['mainRecreationRequested']) is bool and type(value['nginxReloadRequested']) is bool,
                'REMOTE_BLOCKED_SHAPE')
    else:
        require(status == 'BLOCKED_INPUT_OR_READ_FAILED_NO_ACTION'
                and set(value) == {'status', 'automaticRetryOrRollback'}, 'REMOTE_RESULT_STATUS')
    return value


def attempt(script, env):
    # No raw key, passphrase, token, hostname, exception or process stderr is logged.
    require(env.get('VERIFIED_HOST_FINGERPRINT') == PIN, 'EXISTING_ED25519_TRUST_MUST_NOT_CHANGE')
    known_line = pinned_public_key()
    host, user = env.get('DEV_SSH_HOST'), env.get('DEV_SSH_USER')
    target(host, user)
    data = remote_input(script, env)
    key, passphrase = env.get('DEV_SSH_KEY'), env.get('DEV_SSH_PASSPHRASE', '')
    require(isinstance(key, str) and 0 < len(key) <= 1024 * 1024 and '\x00' not in key
            and isinstance(passphrase, str) and len(passphrase) <= 16384 and '\x00' not in passphrase,
            'EXISTING_AUTH_INPUT_REQUIRED')
    runner_temp = Path(env.get('RUNNER_TEMP', ''))
    require(runner_temp.is_absolute() and runner_temp.is_dir(), 'EXISTING_RUNNER_TEMP_REQUIRED')
    with tempfile.TemporaryDirectory(prefix='edupilot-dev-ssh-', dir=runner_temp) as directory:
        temp = Path(directory)
        identity, known, askpass = temp / 'identity', temp / 'known_hosts', temp / 'askpass'
        private_file(identity, (key.replace('\r\n', '\n').rstrip('\n') + '\n').encode('utf-8'))
        private_file(known, known_line.encode('ascii'))
        private_file(askpass, ASKPASS.encode('ascii'), 0o700)
        child_env = {'PATH': '/usr/bin:/bin', 'LANG': 'C', 'LC_ALL': 'C',
                     'SSH_ASKPASS': str(askpass), 'SSH_ASKPASS_REQUIRE': 'force',
                     'EDUPILOT_SSH_KEY_PASSPHRASE': passphrase}
        process = subprocess.run(ssh_command(host, user, identity, known), input=data,
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=child_env, timeout=480)
    result = remote_result(process.stdout, process.returncode)
    result.update(sshHostKeyAlgorithm='ssh-ed25519', sshVerifiedFingerprint=PIN,
                  existingServerIdentityVerified=True, hostTrustChanged=False)
    return result


def run():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--script', required=True, type=Path)
    args = parser.parse_args()
    require(sys.platform.startswith('linux'), 'EXISTING_LINUX_RUNNER_REQUIRED')
    require(args.script.is_absolute() and args.script.is_file() and not args.script.is_symlink(),
            'EXISTING_REVIEWED_SCRIPT_REQUIRED')
    result = attempt(args.script.read_bytes(), dict(os.environ))
    print(json.dumps(result, ensure_ascii=True), flush=True)
    return 0 if result['status'] == 'MAIN_ONLY_DEPLOYED_READBACK_PASSED' else 2


def entry():
    try:
        return run()
    except Exception:
        # A lost SSH connection can occur after a remote change. Preserve uncertainty.
        print(json.dumps({'status': 'BLOCKED_SSH_OR_REMOTE_UNKNOWN_NO_RETRY',
                          'mainRecreationRequested': 'UNKNOWN', 'remoteCommandStarted': 'UNKNOWN',
                          'hostVerificationOutcome': 'NOT_CONFIRMED', 'automaticRetryOrRollback': False}), flush=True)
        return 2


if __name__ == '__main__':
    raise SystemExit(entry())
