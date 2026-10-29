import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const script = path.join(path.dirname(fileURLToPath(import.meta.url)), 'run-once.sh');
const bash = process.platform === 'win32' ? 'C:/Program Files/Git/bin/bash.exe' : 'bash';
const posix = value => process.platform === 'win32'
  ? value.replaceAll('\\', '/').replace(/^([A-Za-z]):/, (_, drive) => `/${drive.toLowerCase()}`)
  : value;
const sha = value => createHash('sha256').update(value).digest('hex');

async function fixture(t, overrides = {}) {
  const tempRoot = path.resolve(os.tmpdir());
  const dir = await fs.mkdtemp(path.join(tempRoot, 'ses-trial-test-'));
  t.after(async () => {
    const target = path.resolve(dir);
    assert.ok(target.startsWith(tempRoot + path.sep) && path.basename(target).startsWith('ses-trial-test-'));
    await fs.rm(target, { recursive: true, force: true });
  });
  const bin = path.join(dir, 'bin');
  const state = path.join(dir, 'state');
  await fs.mkdir(bin); await fs.mkdir(state);
  const artifact = path.join(dir, 'synthetic.jar');
  const manifest = path.join(dir, 'private.properties');
  const ledger = path.join(dir, 'calls.txt');
  await fs.writeFile(artifact, 'This is only a fake artifact for wrapper tests.');
  const fields = {
    schema: '1', trialId: 'ses-component-20261005', environment: 'dev', sourceSha: 'a'.repeat(40),
    artifactSha256: sha(await fs.readFile(artifact)), containerId: 'c'.repeat(64), imageId: 'sha256:' + 'd'.repeat(64),
    composeProject: 'uteum-dev', awsAccountId: '123456789012', awsIdentityEvidenceRef: 'test-only:identity',
    from: 'no-reply@uteum.com', recipient: 'owner@example.com', recipientAlias: 'APPROVED_INBOX_1',
    region: 'ap-northeast-2', maxMessages: '1', approvedOperation: 'SEND', stateDirectory: posix(state),
    authorizationRef: 'test-only:authorization', observedProvider: 'logging', observedEnabled: 'true', ...overrides
  };
  const contents = Object.entries(fields).map(([key, value]) => `${key}=${value}\n`).join('');
  await fs.writeFile(manifest, contents, { mode: 0o600 });
  // This fake Docker exists only inside this test's temporary PATH. It performs no Docker/AWS action.
  await fs.writeFile(path.join(bin, 'docker'), `#!/usr/bin/env bash
set -euo pipefail
printf '%s\\n' "$1" >> "$FAKE_LEDGER"
case "$1" in
  inspect)
    [[ "$*" != *Config.Env* ]] || exit 99
    printf '%s\\n' "$FAKE_METADATA"
    ;;
  cp) exit "$FAKE_STAGE_EXIT" ;;
  exec)
    [[ "$*" == *EDUPILOT_SES_TRIAL_HOST_CLAIM=* && "$*" == *LOADER_MAIN=io.edupilot.mail.IsolatedSesTrial* ]] || exit 99
    [[ "$*" == *JAVA_TOOL_OPTIONS=* && "$*" == *JDK_JAVA_OPTIONS=* && "$*" == *_JAVA_OPTIONS=* ]] || exit 99
    [[ "$*" != *EDUPILOT_MAIL_PROVIDER=* && "$*" != *SPRING_PROFILES_ACTIVE=* ]] || exit 99
    cat >/dev/null
    printf 'TEST_FAKE_EXECUTION_ONLY\\n'
    exit "$FAKE_EXEC_EXIT"
    ;;
  *) exit 99 ;;
esac
`, { mode: 0o755 });
  const env = { ...process.env, PATH: bin + path.delimiter + process.env.PATH,
    FAKE_LEDGER: posix(ledger), FAKE_STAGE_EXIT: '0', FAKE_EXEC_EXIT: '0',
    FAKE_METADATA: `${fields.containerId}|${fields.imageId}|${fields.composeProject}|main-service|true` };
  return {
    dir, state, fields, artifact, manifest, hash: sha(contents), env,
    async calls() { return (await fs.readFile(ledger, 'utf8').catch(() => '')).trim().split('\n').filter(Boolean); }
  };
}

function run(f, mode = 'default', env = {}, hash = f.hash) {
  const args = mode === 'default' ? [posix(f.manifest), posix(f.artifact)]
    : (mode === 'execute' || mode === 'identity') ? [mode, posix(f.manifest), posix(f.artifact), hash]
    : [mode, posix(f.manifest), posix(f.artifact)];
  return new Promise((resolve, reject) => {
    const child = spawn(bash, [posix(script), ...args], { env: { ...f.env, ...env }, windowsHide: true });
    let stdout = '', stderr = '';
    child.stdout.on('data', data => stdout += data);
    child.stderr.on('data', data => stderr += data);
    child.on('error', reject);
    child.on('close', code => resolve({ code, stdout, stderr }));
  });
}

test('default and explicit plan only inspect metadata, without staging, execution or claims', async t => {
  const f = await fixture(t, { authorizationRef: '', awsAccountId: '', awsIdentityEvidenceRef: '' });
  for (const mode of ['default', 'plan']) {
    const result = await run(f, mode);
    assert.equal(result.code, 0, result.stderr + result.stdout);
    assert.match(result.stdout, /PLAN_NO_SEND.*runtimeEnvironmentUnchecked=true/);
  }
  assert.deepEqual(await f.calls(), ['inspect', 'inspect']);
  assert.deepEqual(await fs.readdir(f.state), []);
});

test('one execution consumes persistent host budget; a new wrapper process cannot resend', async t => {
  const f = await fixture(t);
  assert.equal((await run(f, 'execute')).code, 0);
  const repeat = await run(f, 'execute');
  assert.equal(repeat.code, 2); assert.match(repeat.stdout, /BLOCKED_CONSUMED_BUDGET/);
  assert.deepEqual(await f.calls(), ['inspect', 'cp', 'exec', 'inspect']);
  const receipt = await fs.readFile(path.join(f.state, f.fields.trialId + '.consumed', 'scope.txt'), 'utf8');
  assert.match(receipt, /CONSUMED_BEFORE_SDK/);
  assert.ok(!receipt.includes('owner@example.com'));
});

for (const exitCode of [3, 4, 137]) {
  test(`rejection, uncertainty or killed process (${exitCode}) never reopens budget`, async t => {
    const f = await fixture(t);
    assert.equal((await run(f, 'execute', { FAKE_EXEC_EXIT: String(exitCode) })).code, exitCode);
    assert.equal((await run(f, 'execute')).code, 2);
    assert.equal((await f.calls()).filter(call => call === 'exec').length, 1);
  });
}

test('staging failure consumes budget before any invocation', async t => {
  const f = await fixture(t);
  assert.equal((await run(f, 'execute', { FAKE_STAGE_EXIT: '1' })).code, 4);
  assert.equal((await run(f, 'execute')).code, 2);
  assert.equal((await f.calls()).filter(call => call === 'cp').length, 1);
  assert.equal((await f.calls()).filter(call => call === 'exec').length, 0);
});

test('eight concurrent wrapper processes stage and invoke only once', async t => {
  const f = await fixture(t);
  const results = await Promise.all(Array.from({ length: 8 }, () => run(f, 'execute')));
  assert.equal(results.filter(result => result.code === 0).length, 1);
  assert.equal((await f.calls()).filter(call => call === 'cp').length, 1);
  assert.equal((await f.calls()).filter(call => call === 'exec').length, 1);
});

test('unapproved manifest or modified artifact fails before claims or staging', async t => {
  const f = await fixture(t);
  assert.equal((await run(f, 'execute', {}, 'e'.repeat(64))).code, 2);
  await fs.appendFile(f.artifact, 'changed');
  assert.equal((await run(f, 'execute')).code, 2);
  assert.deepEqual(await f.calls(), []);
  assert.deepEqual(await fs.readdir(f.state), []);
});

const invalid = [
  ['environment', 'prod'], ['from', 'other@uteum.com'], ['region', 'us-east-1'],
  ['maxMessages', '2'], ['observedProvider', 'ses'], ['observedEnabled', 'false'],
  ['trialId', '../new-trial'], ['recipient', 'owner@example.com,other@example.com'],
  ['recipient', 'Owner <owner@example.com>'], ['authorizationRef', ''], ['awsAccountId', ''],
  ['awsIdentityEvidenceRef', ''], ['stateDirectory', '/'], ['stateDirectory', '/tmp/../another']
];
for (const [key, value] of invalid) {
  test(`invalid ${key} fails closed without Docker staging or execution`, async t => {
    const f = await fixture(t, { [key]: value });
    const result = await run(f, 'execute');
    assert.equal(result.code, 2);
    assert.ok(!result.stdout.includes('owner@example.com'));
    assert.ok(!(await f.calls()).includes('cp'));
    assert.ok(!(await f.calls()).includes('exec'));
    assert.deepEqual(await fs.readdir(f.state), []);
  });
}

for (const suffix of ['other-service|true', 'main-service|false']) {
  test(`container service or running state mismatch (${suffix}) blocks before mutation`, async t => {
    const f = await fixture(t);
    const metadata = `${f.fields.containerId}|${f.fields.imageId}|${f.fields.composeProject}|${suffix}`;
    assert.equal((await run(f, 'execute', { FAKE_METADATA: metadata })).code, 2);
    assert.deepEqual(await f.calls(), ['inspect']);
    assert.deepEqual(await fs.readdir(f.state), []);
  });
}

test('duplicate keys, extra keys and non-ASCII values are rejected without Docker action', async t => {
  for (const suffix of ['recipient=other@example.com\n', 'bcc=other@example.com\n', 'region=서울\n']) {
    const f = await fixture(t);
    await fs.appendFile(f.manifest, suffix);
    assert.equal((await run(f, 'plan')).code, 2);
    assert.deepEqual(await f.calls(), []);
  }
});

test('identity approval stages only one read and cannot authorize a send or consume send budget', async t => {
  const f = await fixture(t, { approvedOperation: 'IDENTITY', maxMessages: '0', awsIdentityEvidenceRef: '' });
  assert.equal((await run(f, 'execute')).code, 2);
  const identity = await run(f, 'identity');
  assert.equal(identity.code, 0);
  assert.equal(identity.stdout, 'TEST_FAKE_EXECUTION_ONLY\n');
  assert.equal((await run(f, 'identity')).code, 2);
  assert.equal((await f.calls()).filter(call => call === 'exec').length, 1);
  assert.deepEqual(await fs.readdir(f.state), [f.fields.trialId + '.identity-read']);
});

test('send approval cannot run the identity command', async t => {
  const f = await fixture(t);
  assert.equal((await run(f, 'identity')).code, 2);
  assert.deepEqual(await f.calls(), []);
});
