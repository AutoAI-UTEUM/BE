/** Real local Nginx, synthetic static files and loopback upstream only. */
import assert from 'node:assert/strict';
import { execFileSync, spawn, spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import http from 'node:http';
import https from 'node:https';
import net from 'node:net';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import { after, before, test } from 'node:test';
import { fileURLToPath } from 'node:url';

const repo = resolve(fileURLToPath(new URL('../../', import.meta.url)));
const binary = process.env.NGINX_TEST_BINARY;
const openssl = process.env.NGINX_TEST_OPENSSL ?? 'openssl';
assert.ok(binary, 'Set NGINX_TEST_BINARY to an installed or isolated trusted Nginx executable.');
const baselineSha = '4e578c5f811d0084de0b80ee72b5ed7c72ba84c6';
const candidate = readFileSync(resolve(repo, 'infra/nginx/edupilot.conf'), 'utf8');
const baseline = execFileSync('git', ['-C', repo, '-c', `safe.directory=${repo.replaceAll('\\', '/')}`,
  'show', `${baselineSha}:infra/nginx/edupilot.conf`], { encoding: 'utf8' });
const root = mkdtempSync(resolve(tmpdir(), 'uteum-nginx-email-link-'));
const slash = value => value.replaceAll('\\', '/');
const sleep = ms => new Promise(resolvePromise => setTimeout(resolvePromise, ms));
const secret = 'SYNTHETIC_EMAIL_LINK_'.padEnd(43, 'A');
const refererSecret = 'SYNTHETIC_REFERER_ONLY';
const entry = '<!doctype html><html><head><meta name="referrer" content="no-referrer"></head><body>SYNTHETIC_SPA_ONLY</body></html>';
const servers = [];
const observations = [];
let upstream;
let beforeServer;
let afterServer;
let errorLogContainsSyntheticQuery = false;

async function freePort() {
  const reservation = net.createServer();
  await new Promise((resolvePromise, reject) => {
    reservation.once('error', reject);
    reservation.listen(0, '127.0.0.1', resolvePromise);
  });
  const { port } = reservation.address();
  await new Promise(resolvePromise => reservation.close(resolvePromise));
  return port;
}

function request(server, path, method = 'GET', plain = false) {
  const client = plain ? http : https;
  return new Promise((resolvePromise, reject) => {
    const req = client.request({ hostname: '127.0.0.1', port: plain ? server.httpPort : server.httpsPort,
      path, method, rejectUnauthorized: false, headers: { Host: 'localhost', Referer: `https://local.invalid/?token=${refererSecret}` } }, res => {
      let body = '';
      res.setEncoding('utf8');
      res.on('data', chunk => { body += chunk; });
      res.on('end', () => resolvePromise({ status: res.statusCode, headers: res.headers, body }));
    });
    req.setTimeout(2000, () => req.destroy(new Error('Local Nginx request timed out')));
    req.once('error', reject);
    req.end();
  });
}

async function start(label, source) {
  const dir = resolve(root, label);
  const html = resolve(dir, 'html');
  mkdirSync(resolve(dir, 'logs'), { recursive: true });
  mkdirSync(resolve(dir, 'temp'), { recursive: true });
  mkdirSync(resolve(html, 'assets'), { recursive: true });
  writeFileSync(resolve(html, 'index.html'), entry);
  writeFileSync(resolve(html, '404.html'), '<html>SYNTHETIC_NOT_FOUND</html>');
  writeFileSync(resolve(html, 'assets/test.js'), '/* synthetic asset */');
  const key = resolve(dir, 'local-test.key');
  const cert = resolve(dir, 'local-test.crt');
  execFileSync(openssl, ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1',
    '-subj', '/CN=localhost', '-keyout', key, '-out', cert], { stdio: 'ignore', windowsHide: true });
  const httpPort = await freePort();
  const httpsPort = await freePort();
  const access = slash(resolve(dir, 'logs/access.log'));
  let adjusted = source.replaceAll('${EDUPILOT_DOMAIN}', 'localhost')
    .replace('listen 80;', `listen 127.0.0.1:${httpPort};`)
    .replace('listen [::]:80;', '# IPv6 disabled in isolated local test')
    .replace('listen 443 ssl;', `listen 127.0.0.1:${httpsPort} ssl;`)
    .replace('listen [::]:443 ssl;', '# IPv6 disabled in isolated local test')
    .replaceAll('/usr/share/nginx/html', slash(html))
    .replace('/etc/letsencrypt/live/edupilot/fullchain.pem', slash(cert))
    .replace('/etc/letsencrypt/live/edupilot/privkey.pem', slash(key))
    .replaceAll('/var/log/nginx/access.log', access)
    .replaceAll('http://main-service:8080', `http://127.0.0.1:${upstream.address().port}`);
  assert.ok(!/listen\s+(?:80|443|\[::\])\b/.test(adjusted), 'Never bind deployed ports/interfaces');
  assert.ok(!adjusted.includes('http://main-service'), 'Never address a real upstream');
  adjusted = `worker_processes 1;\npid logs/nginx.pid;\nerror_log logs/error.log notice;\nevents { worker_connections 128; }\nhttp {\naccess_log ${access} combined;\n${adjusted}\n}\n`;
  const config = resolve(dir, 'nginx.conf');
  writeFileSync(config, adjusted);
  const args = ['-p', slash(dir) + '/', '-c', slash(config)];
  execFileSync(binary, [...args, '-t'], { stdio: 'pipe', windowsHide: true });
  const child = spawn(binary, [...args, '-g', 'daemon off;'], { windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
  const state = { label, dir, args, child, httpPort, httpsPort, access };
  servers.push(state);
  const deadline = Date.now() + 10000;
  while (Date.now() < deadline) {
    try {
      const ready = await request(state, '/');
      if (ready.status === 200) {
        const pid = Number(readFileSync(resolve(dir, 'logs/nginx.pid'), 'utf8').trim());
        assert.equal(pid, child.pid, 'Only the owned local master may be stopped');
        return state;
      }
    } catch { /* Wait for this new loopback instance only. */ }
    if (child.exitCode !== null) throw new Error(`Local ${label} Nginx exited before readiness`);
    await sleep(50);
  }
  throw new Error(`Local ${label} Nginx did not become ready`);
}

before(async () => {
  upstream = http.createServer((req, res) => {
    if (req.url.startsWith('/api/local-synthetic-error')) { res.destroy(); return; }
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end('{"source":"SYNTHETIC_UPSTREAM_ONLY"}');
  });
  await new Promise(resolvePromise => upstream.listen(0, '127.0.0.1', resolvePromise));
  beforeServer = await start('baseline', baseline);
  afterServer = await start('candidate', candidate);
});

after(async () => {
  const stopped = [];
  for (const server of servers) {
    let clean = false;
    const pidFile = resolve(server.dir, 'logs/nginx.pid');
    if (existsSync(pidFile)) {
      assert.equal(Number(readFileSync(pidFile, 'utf8').trim()), server.child.pid);
      execFileSync(binary, [...server.args, '-s', 'quit'], { stdio: 'pipe', windowsHide: true });
    }
    const deadline = Date.now() + 5000;
    while (Date.now() < deadline && server.child.exitCode === null) await sleep(50);
    clean = server.child.exitCode !== null && !existsSync(pidFile);
    if (!clean) { server.child.kill(); throw new Error(`Owned ${server.label} Nginx did not stop cleanly`); }
    stopped.push({ label: server.label, cleanly_stopped: clean });
  }
  if (upstream) await new Promise(resolvePromise => upstream.close(resolvePromise));
  const version = spawnSync(binary, ['-v'], { encoding: 'utf8', windowsHide: true });
  assert.equal(version.status, 0);
  const result = { baseline_sha: baselineSha, nginx_version: (version.stderr + version.stdout).trim(),
    environment: 'LOCAL_LOOPBACK_SYNTHETIC_NGINX', root: slash(root), real_dev: false,
    public_network_requests: 0, paid_calls: 0, operational_logs_searched: false,
    observations, synthetic_error_log_query_exposure_observed: errorLogContainsSyntheticQuery,
    stopped, frontend_ui_or_real_mail_acceptance: false };
  if (process.env.NGINX_TEST_RESULTS) writeFileSync(process.env.NGINX_TEST_RESULTS, JSON.stringify(result, null, 2) + '\n');
});

test('baseline reproduces direct email-link 404 and combined access query/Referer exposure with synthetic markers', async () => {
  const result = await request(beforeServer, `/verify-email?token=${secret}`);
  assert.equal(result.status, 404);
  await sleep(50);
  const log = readFileSync(beforeServer.access, 'utf8');
  assert.ok(log.includes(secret), 'Expected synthetic baseline query exposure');
  assert.ok(log.includes(refererSecret), 'Expected synthetic baseline Referer exposure');
  observations.push({ case: 'baseline', status: 404, synthetic_query_in_access_log: true, synthetic_referer_in_access_log: true });
});

for (const path of ['/verify-email', '/verify-email/', '/VERIFY-EMAIL', '/%76erify%2Demail']) {
  test(`candidate direct ${path} serves the synthetic SPA entry without consuming anything`, async () => {
    const response = await request(afterServer, `${path}?token=${secret}`);
    assert.equal(response.status, 200);
    assert.equal(response.body, entry);
    assert.match(response.headers['cache-control'], /no-store/);
    observations.push({ case: path, status: 200, evidence: 'LOCAL_STATIC_SPA_ONLY' });
  });
}

for (const path of ['/verify-email/unknown', '/unknown', '/.env', '/assets/missing.js']) {
  test(`candidate keeps real 404 for ${path}`, async () => {
    const response = await request(afterServer, `${path}?token=${secret}`);
    assert.equal(response.status, 404);
    assert.ok(!response.body.includes('SYNTHETIC_SPA_ONLY'));
    observations.push({ case: path, status: 404 });
  });
}

test('HEAD has no body and route POST is not an email confirmation operation', async () => {
  const head = await request(afterServer, `/verify-email?token=${secret}`, 'HEAD');
  assert.equal(head.status, 200);
  assert.equal(head.body, '');
  const post = await request(afterServer, '/verify-email', 'POST');
  assert.equal(post.status, 405);
  observations.push({ case: 'HEAD/POST landing', head: 200, post: 405 });
});

test('existing known SPA, asset and API routes retain their different responses', async () => {
  assert.equal((await request(afterServer, '/login')).body, entry);
  assert.equal((await request(afterServer, '/assets/test.js')).body, '/* synthetic asset */');
  const api = await request(afterServer, '/api/health');
  assert.equal(api.status, 200);
  assert.equal(JSON.parse(api.body).source, 'SYNTHETIC_UPSTREAM_ONLY');
  observations.push({ case: 'existing routes', login: 'SPA', asset: 'STATIC', api: 'LOCAL_FAKE_UPSTREAM' });
});

test('HTTP redirect preserves link arguments for the browser while access logging omits them', async () => {
  const response = await request(afterServer, `/verify-email?token=${secret}`, 'GET', true);
  assert.equal(response.status, 301);
  assert.equal(response.headers.location, `https://localhost/verify-email?token=${secret}`);
  observations.push({ case: 'HTTP redirect', status: 301, location_retains_synthetic_query: true });
});

test('proxy failure stays 502 and access log is safe; error-log query scope is recorded separately', async () => {
  const response = await request(afterServer, `/api/local-synthetic-error?token=${secret}`);
  assert.equal(response.status, 502);
  await sleep(50);
  errorLogContainsSyntheticQuery = readFileSync(resolve(afterServer.dir, 'logs/error.log'), 'utf8').includes(secret);
  observations.push({ case: 'local fake upstream failure', status: 502,
    synthetic_query_in_error_log: errorLogContainsSyntheticQuery });
});

test('both candidate listeners omit query/Referer in valid JSON access records, including fallback and errors', async () => {
  await sleep(50);
  const log = readFileSync(afterServer.access, 'utf8');
  assert.ok(!log.includes(secret), 'Synthetic query must be absent from access log');
  assert.ok(!log.includes(refererSecret), 'Synthetic Referer must be absent from access log');
  const rows = log.trim().split(/\r?\n/).map(line => JSON.parse(line));
  assert.ok(rows.some(row => row.status === 301));
  assert.ok(rows.some(row => row.status === 502));
  assert.ok(rows.some(row => row.status === 200 && row.uri === '/index.html'));
  assert.ok(rows.every(row => !row.uri.includes('?') && !('referer' in row) && !('request' in row)));
  observations.push({ case: 'access log', json_records: rows.length, query_absent: true, referer_absent: true,
    spa_logged_uri: '/index.html', original_route_not_preserved: true });
});
