import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, readFileSync, realpathSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, isAbsolute, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const fixtureParent = realpathSync(tmpdir());
const fixture = mkdtempSync(join(fixtureParent, 'uteum-compose-preservation-'));
const dockerConfig = join(fixture, 'docker-config');
mkdirSync(dockerConfig);
const executionEnv = Object.fromEntries(
  ['PATH', 'Path', 'SystemRoot', 'WINDIR', 'PATHEXT', 'TEMP', 'TMP']
    .filter((key) => process.env[key] !== undefined)
    .map((key) => [key, process.env[key]]),
);
executionEnv.DOCKER_CONFIG = dockerConfig;
const executable = process.env.UTEUM_LOCAL_COMPOSE_BIN || 'docker';
const prefix = process.env.UTEUM_LOCAL_COMPOSE_BIN ? [] : ['compose'];

test.after(() => {
  const target = realpathSync(fixture);
  const child = relative(fixtureParent, target);
  assert.ok(child && !isAbsolute(child) && child !== '..' && !child.startsWith(`..${sep}`));
  assert.ok(child.startsWith('uteum-compose-preservation-'));
  rmSync(target, { recursive: true });
});

function compose({ dev = true, provider = 'ses', dispatch = 'NORMAL' } = {}) {
  const envFile = join(fixture, 'synthetic.env');
  writeFileSync(envFile, [
    'MYSQL_ROOT_PASSWORD=synthetic-root-password',
    'MYSQL_PASSWORD=synthetic-app-password',
    'EDUPILOT_JWT_SECRET=synthetic-local-only-secret-with-thirty-two-bytes',
    'EDUPILOT_INTERNAL_TOKEN=synthetic-local-only-internal-token',
    'EDUPILOT_DOMAIN=dev.fixture.invalid',
    'ENVIRONMENT=dev',
    'TAG=local-validation',
    `EDUPILOT_MAIL_PROVIDER=${provider}`,
    `EDUPILOT_MAIL_OUTBOX_DISPATCH_MODE=${dispatch}`,
    'EDUPILOT_GUARDIAN_TEAM_ENABLED=true',
    'EDUPILOT_GUARDIAN_TEAM_POLICY_CONFIRMED=true',
    'EDUPILOT_GUARDIAN_WEB_ENABLED=true',
    'EDUPILOT_POLICY_SIGNUP_CONSENT_REQUIRED=true',
    'EDUPILOT_SIGNUP_PAUSED=true',
    '',
  ].join('\n'));
  const args = [...prefix, '--env-file', envFile, '-f', 'docker-compose.yml', '-f', 'docker-compose.prod.yml'];
  if (dev) args.push('-f', 'docker-compose.dev.yml');
  args.push('config', '--format', 'json');
  const result = spawnSync(executable, args, {
    cwd: root, env: executionEnv, encoding: 'utf8', timeout: 15000,
    maxBuffer: 2 * 1024 * 1024, windowsHide: true,
  });
  assert.equal(result.error, undefined, `local Compose config error: ${result.error?.code}`);
  assert.equal(result.status, 0, 'local Compose config failed; no daemon or deployment command was requested');
  return JSON.parse(result.stdout);
}

for (const dispatch of ['NORMAL', 'ISOLATED_TRIAL', 'PAUSED']) {
  test(`DEV keeps logging/PAUSED and guardian disabled with ${dispatch} in synthetic inputs`, () => {
    const config = compose({ dispatch });
    const env = config.services['main-service'].environment;
    assert.equal(env.EDUPILOT_MAIL_PROVIDER, 'logging');
    assert.equal(env.EDUPILOT_MAIL_OUTBOX_DISPATCH_MODE, 'PAUSED');
    for (const key of ['EDUPILOT_GUARDIAN_TEAM_ENABLED', 'EDUPILOT_GUARDIAN_TEAM_POLICY_CONFIRMED', 'EDUPILOT_GUARDIAN_WEB_ENABLED']) {
      assert.equal(env[key], 'false');
    }
    assert.equal(env.EDUPILOT_POLICY_SIGNUP_CONSENT_REQUIRED, 'true');
    assert.equal(config.services.nginx.environment.EDUPILOT_SIGNUP_PAUSED, 'true');
    assert.equal(config.services['main-service'].image, 'ghcr.io/autoai-uteum/main-service:local-validation');
    assert.equal(config.services['main-service'].environment.SPRING_PROFILES_ACTIVE, 'dev');
    const template = config.services.nginx.volumes.find((v) => v.target === '/etc/nginx/templates/default.conf.template');
    assert.ok(template.read_only);
    assert.equal(template.source, resolve(root, 'infra/nginx/edupilot.conf'));
  });
}

test('the production Compose combination retains its input provider and dispatch mode', () => {
  const env = compose({ dev: false }).services['main-service'].environment;
  assert.equal(env.EDUPILOT_MAIL_PROVIDER, 'ses');
  assert.equal(env.EDUPILOT_MAIL_OUTBOX_DISPATCH_MODE, 'NORMAL');
});

test('every DEV Compose operation uses and copies the final DEV override', () => {
  const workflow = readFileSync(join(root, '.github/workflows/deploy-dev.yml'), 'utf8').replace(/\\\r?\n\s*/g, ' ');
  assert.match(workflow, /source: [^\r\n]*docker-compose\.dev\.yml/);
  const commands = workflow.split(/\r?\n/).filter((line) => /docker .*compose --env-file \.env/.test(line));
  assert.ok(commands.length > 0);
  for (const command of commands) {
    assert.match(command, /-f docker-compose\.yml -f docker-compose\.prod\.yml -f docker-compose\.dev\.yml /);
  }
  assert.doesNotMatch(readFileSync(join(root, '.github/workflows/deploy-prod.yml'), 'utf8'), /docker-compose\.dev\.yml/);
});

test('guardian SPA location accepts only the two entry paths and their optional trailing slash', () => {
  const nginx = readFileSync(join(root, 'infra/nginx/edupilot.conf'), 'utf8');
  const location = nginx.match(/location\s+~\*\s+(\S*guardian-request\S*)\s*\{([\s\S]*?)\n\s*\}/);
  assert.ok(location, 'guardian entry location is absent');
  const matchPath = new RegExp(location[1], 'i');
  for (const path of ['/guardian-request', '/guardian-request/', '/GUARDIAN-REQUEST', '/guardian-consent', '/guardian-consent/', '/Guardian-Consent/']) {
    assert.ok(matchPath.test(path), `entry path rejected: ${path}`);
  }
  for (const path of ['/guardian-request/child', '/guardian-consent/child', '/guardian-request-extra', '/guardian-consent-extra', '/x/guardian-request', '/guardian-consent//']) {
    assert.ok(!matchPath.test(path), `unreviewed path accepted: ${path}`);
  }
  assert.match(location[2], /try_files\s+\/index\.html\s+=404;/);
  assert.match(location[2], /Cache-Control\s+"no-cache, no-store, must-revalidate"\s+always;/);
});
