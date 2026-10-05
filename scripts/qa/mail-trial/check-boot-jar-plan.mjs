import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';

// Native packaging check, with synthetic inputs only. It never authorizes a send.
const artifact = path.resolve(process.argv[2] ?? 'main-service/build/libs/main-service-0.0.1-SNAPSHOT.jar');
const artifactHash = createHash('sha256').update(await fs.readFile(artifact)).digest('hex');
const tempRoot = path.resolve(os.tmpdir());
const dir = await fs.mkdtemp(path.join(tempRoot, 'ses-trial-plan-'));
const java = process.platform === 'win32' ? 'C:/Program Files/Java/jdk-21/bin/java.exe' : 'java';
const fields = {
  schema: '1', trialId: 'ses-component-20261005', environment: 'dev', sourceSha: 'a'.repeat(40),
  artifactSha256: artifactHash, containerId: 'c'.repeat(64), imageId: 'sha256:' + 'd'.repeat(64),
  composeProject: 'uteum-dev', awsAccountId: '', awsIdentityEvidenceRef: '',
  from: 'no-reply@uteum.com', recipient: 'owner@example.com', recipientAlias: 'APPROVED_INBOX_1',
  region: 'ap-northeast-2', maxMessages: '1', stateDirectory: '/var/lib/uteum-mail-trial',
  authorizationRef: '', observedProvider: 'logging', observedEnabled: 'true'
};
const manifest = values => Object.entries({ ...fields, ...values }).map(([key, value]) => `${key}=${value}\n`).join('');
const env = {
  PATH: process.env.PATH, SystemRoot: process.env.SystemRoot ?? '', TEMP: dir, TMP: dir,
  SPRING_PROFILES_ACTIVE: 'dev', EDUPILOT_MAIL_PROVIDER: 'logging', EDUPILOT_MAIL_ENABLED: 'true',
  EDUPILOT_MAIL_FROM: 'no-reply@uteum.com', AWS_REGION: 'ap-northeast-2',
  LOADER_MAIN: 'io.edupilot.mail.IsolatedSesTrial', LOADER_PATH: '', LOADER_HOME: dir,
  LOADER_CONFIG_LOCATION: 'classpath:uteum-ses-trial-no-external-config.properties',
  LOADER_CONFIG_NAME: 'uteum-ses-trial-no-external-config', LOADER_SYSTEM: 'false',
  // One whitespace splits into zero prepended arguments; an empty string would prepend one empty argument.
  LOADER_ARGS: ' ', LOADER_DEBUG: 'false', JAVA_TOOL_OPTIONS: '', JDK_JAVA_OPTIONS: '', _JAVA_OPTIONS: ''
};

async function run(args, input, changes = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(java, ['-XX:-UsePerfData', `-Djava.io.tmpdir=${dir}`, '-cp', artifact,
      'org.springframework.boot.loader.launch.PropertiesLauncher', ...args],
    { env: { ...env, ...changes }, windowsHide: true });
    let stdout = '', stderr = '';
    child.stdout.on('data', data => stdout += data);
    child.stderr.on('data', data => stderr += data);
    child.on('error', reject);
    child.on('close', code => resolve({ code, stdout: stdout.replaceAll('\r\n', '\n'), stderr }));
    child.stdin.end(input);
  });
}

try {
  let passed = 0;
  for (const args of [[], ['--plan']]) {
    const result = await run(args, manifest({}));
    assert.equal(result.code, 0, result.stderr + result.stdout);
    assert.equal(result.stdout, 'SES_TRIAL PLAN_NO_SEND alias=APPROVED_INBOX_1 maxMessages=1\n');
    assert.ok(!result.stderr.includes('owner@example.com'));
    passed++;
  }
  for (const [args, input, changes] of [
    [['--execute', '--manifest-sha256', 'e'.repeat(64)], manifest({}), {}],
    [['--plan'], manifest({ artifactSha256: 'f'.repeat(64) }), {}],
    [['--plan'], manifest({}), { EDUPILOT_MAIL_PROVIDER: 'ses' }]
  ]) {
    const result = await run(args, input, changes);
    assert.equal(result.code, 2, result.stderr + result.stdout);
    assert.equal(result.stdout, 'SES_TRIAL BLOCKED_INPUT\n');
    passed++;
  }
  assert.deepEqual(await fs.readdir(dir), []); // No container claim or Spring/DB work in plan/rejected input.
  process.stdout.write(JSON.stringify({ check: 'native-boot-jar-plan', passed, failed: 0,
    artifactSha256: artifactHash, mode: 'synthetic-plan-or-blocked-input', sends: 0,
    springStarted: false, identityAttested: false, executionAuthorized: false }) + '\n');
} finally {
  const target = path.resolve(dir);
  assert.ok(target.startsWith(tempRoot + path.sep) && path.basename(target).startsWith('ses-trial-plan-'));
  await fs.rm(target, { recursive: true, force: true });
}
