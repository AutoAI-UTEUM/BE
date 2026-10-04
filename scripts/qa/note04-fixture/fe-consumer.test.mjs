/**
 * Read-only FE source replay with a fake AuthenticatedRequest backed by synthetic SQLite.
 * Executes actual repository functions and the extracted useEffect callback; no React,
 * DOM, browser, auth client, network, packages, DEV adapter, or FE writes.
 */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
import { resolve } from 'node:path';
import { test } from 'node:test';
import vm from 'node:vm';
import { CREATED_AT, mockList, openLocalDb, seedFixture } from './fixture.mjs';

const FE_HEAD = '1b6987d8472a064a080a00bd43232993e9bd41eb';
const sourceRoot = process.env.NOTE04_FE_SOURCE_ROOT;
assert.ok(sourceRoot, 'Set NOTE04_FE_SOURCE_ROOT to the independent read-only FE checkout.');
const root = resolve(sourceRoot);
const paths = {
  repository: 'src/features/sessions/sessionsRepository.ts',
  collection: 'src/app/pages/learner/LearnerReviewQuizzesPage.tsx',
  error: 'src/shared/api/apiClientError.ts',
};
// Trust only the explicitly selected read-only checkout for these two read commands.
// Avoid changing the user's global Git configuration on Windows sandbox identities.
const git = args => execFileSync('git', ['-c', `safe.directory=${root.replaceAll('\\', '/')}`, '-C', root, ...args],
  { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
assert.equal(git(['rev-parse', 'HEAD']), FE_HEAD, 'FE head changed; review the source before changing this contract.');
assert.equal(git(['diff', '--no-ext-diff', '--no-textconv', '--name-only', 'HEAD', '--', ...Object.values(paths)]), '',
  'FE source must match the pinned head.');
const source = Object.fromEntries(Object.entries(paths).map(([key, path]) => [key, readFileSync(resolve(root, path), 'utf8')]));
const strip = code => stripTypeScriptTypes(code, { mode: 'strip' });
const removeExports = code => code.replace(/^export (?=(?:function|class|const|let)\b)/gm, '');
const ApiClientError = vm.runInNewContext(`${removeExports(strip(source.error))}\nApiClientError`);
const repositoryCode = removeExports(strip(source.repository)).replace(/^import\b[^\n]*\n/gm, '');
assert.ok(!/^import\b/m.test(repositoryCode), 'Unexpected import; never resolve the real FE HTTP dependencies.');
const createSessionsRepository = vm.runInNewContext(`${repositoryCode}\ncreateSessionsRepository`, {
  ApiClientError, URLSearchParams, AbortController,
  isApiCapabilityEnabled: () => false,
  consumeSseStream: () => { throw new Error('SSE is forbidden in this offline replay.'); },
});

// Extract the existing callback, replacing only its unawaited invocation with an observer.
// React render/scheduling and the JSX are deliberately not evaluated.
const effectStart = '  useEffect(() => {';
const effectEnd = '  }, [repository, attempt])';
assert.equal(source.collection.split(effectStart).length, 2);
const start = source.collection.indexOf(effectStart) + effectStart.length;
const end = source.collection.indexOf(effectEnd, start);
assert.ok(end > start);
const originalEffect = source.collection.slice(start, end);
assert.equal(originalEffect.split('void load()').length, 2);
const invokeEffect = vm.runInNewContext(strip(`(repository, cache, attempt, setResult, onLoad) => {
  const getRequestErrorMessage = error => String(error.message);
  ${originalEffect.replace('void load()', 'onLoad(load())')}
}`), { AbortController });
const helpersStart = source.collection.indexOf('function flattenAndSortQuizzes(');
const helpersEnd = source.collection.indexOf('function getQuizStatus(', helpersStart);
assert.ok(helpersStart > 0 && helpersEnd > helpersStart);
const flattenAndSortQuizzes = vm.runInNewContext(`${strip(source.collection.slice(helpersStart, helpersEnd))}\nflattenAndSortQuizzes`);

function startEffect(repository, cache = { current: null }, attempt = 0) {
  const published = [];
  let settled;
  const cleanup = invokeEffect(repository, cache, attempt, value => published.push(value), promise => { settled = promise; });
  return { published, cleanup, settled, cache };
}

function deferred() {
  let resolvePromise;
  const promise = new Promise(resolve => { resolvePromise = resolve; });
  return { promise, resolve: resolvePromise };
}

function setup(actorAlias = 'A', intercept = null) {
  const db = openLocalDb();
  const { manifest } = seedFixture(db, { dryRun: false });
  const actor = manifest.learners.find(row => row.alias === actorAlias);
  const resources = manifest.resources.filter(row => row.userId === actor.userId);
  const calls = [];
  const request = async (path, options = {}) => {
    assert.ok(options.method === undefined || options.method === 'GET', 'Only fake GET reads are permitted.');
    const url = new URL(path, 'https://local-mock.invalid'); // Parse only; no HTTP client.
    const resource = resources.find(row => url.pathname === `/api/sessions/${row.sessionId}/quizzes`);
    const page = Number(url.searchParams.get('page') ?? 0);
    calls.push({ path, alias: resource?.alias, page, signal: options.signal });
    if (url.pathname === '/api/sessions') {
      return { success: true, data: { items: resources.map(row => ({ sessionId: row.sessionId,
        materialId: row.materialId, materialTitle: `Synthetic ${row.alias}`, currentPage: 7, status: 'ACTIVE' })),
      page: 0, size: 20, totalElements: resources.length, totalPages: 1 }, message: 'LOCAL_MOCK_ONLY' };
    }
    assert.ok(resource, 'Unexpected scope: only manifest-owned session IDs are allowed.');
    if (intercept) await intercept({ resource, page, options, calls });
    const response = mockList(db, actor.userId, resource.sessionId, page, Number(url.searchParams.get('size') ?? 100));
    assert.equal(response.status, 200);
    return response.body;
  };
  return { db, manifest, actor, resources, calls, repository: createSessionsRepository(request), close: () => db.close() };
}

const ids = batches => Array.from(flattenAndSortQuizzes(batches), row => row.quiz.quizId);
const aliases = result => Array.from(result.failed, row => row.id);

test('source is pinned and clean; collection uses automatic history, four workers, failed-only retry, account key', () => {
  assert.match(source.repository, /async listQuizHistory/);
  assert.match(originalEffect, /await repository\.listQuizHistory\(session\.id, signal\)/);
  assert.match(originalEffect, /await Promise\.all/);
  assert.match(originalEffect, /Math\.min\(4, sessions\.length\)/);
  assert.match(originalEffect, /\? previous\.failed/);
  assert.match(originalEffect, /\[\.\.\.previous\.batches\]/);
  assert.match(source.collection, /ReviewQuizCollection key=/);
  assert.ok(!source.collection.includes('더 보기'));
});

test('actual FE repository automatically reads queryless page0 then page1; never asks page2 after hasNext=false', async () => {
  const s = setup();
  try {
    const result = await s.repository.listQuizHistory(String(s.resources[0].sessionId));
    assert.equal(result.length, 101);
    assert.equal(new Set(Array.from(result, row => row.quizId)).size, 101);
    assert.deepEqual(s.calls.map(row => row.page), [0, 1]);
    assert.ok(!s.calls[0].path.includes('?'));
    assert.ok(s.calls[1].path.endsWith('?page=1&size=100'));
  } finally { s.close(); }
});

test('actual collection publishes nothing from a partial A1 load; final A account collection has A1=101 plus A2=1', async () => {
  const entered = deferred();
  const release = deferred();
  const s = setup('A', async ({ resource, page }) => {
    if (resource.alias === 'A1' && page === 1) { entered.resolve(); await release.promise; }
  });
  try {
    const run = startEffect(s.repository);
    await entered.promise;
    assert.equal(run.published.length, 0);
    release.resolve();
    await run.settled;
    const result = run.published[0];
    assert.equal(result.failed.length, 0);
    assert.deepEqual(Array.from(result.batches, batch => batch.quizzes.length).sort((a, b) => a - b), [1, 101]);
    assert.equal(ids(result.batches).length, 102);
    run.cleanup();
  } finally { release.resolve(); s.close(); }
});

for (const status of [500, 429]) {
  test(`mock HTTP${status} on A1 page1 discards A1 partial results, preserves A2, retries A1 from page0 only`, async () => {
    let fail = true;
    const s = setup('A', async ({ resource, page }) => {
      if (resource.alias === 'A1' && page === 1 && fail) {
        throw new ApiClientError({ code: status === 500 ? 'SERVER_ERROR' : 'RATE_LIMITED', message: 'Synthetic only', status });
      }
    });
    try {
      const initial = startEffect(s.repository);
      await initial.settled;
      const first = initial.published[0];
      assert.deepEqual(aliases(first), [String(s.resources[0].sessionId)]);
      assert.equal(first.batches.length, 1);
      assert.deepEqual(ids(first.batches), [String(s.resources[1].quizIds[0])]);
      initial.cleanup();
      fail = false;
      const retry = startEffect(s.repository, initial.cache, 1);
      await retry.settled;
      assert.equal(retry.published[0].failed.length, 0);
      assert.equal(new Set(ids(retry.published[0].batches)).size, 102);
      assert.deepEqual(s.calls.filter(row => row.alias === 'A1').map(row => row.page), [0, 1, 0, 1]);
      assert.equal(s.calls.filter(row => row.alias === 'A2').length, 1);
      assert.equal(s.calls.filter(row => row.alias === undefined).length, 1);
      retry.cleanup();
    } finally { s.close(); }
  });
}

test('late old-account page1 response after abort publishes no A result; fresh B collection contains only B1', async () => {
  const entered = deferred();
  const release = deferred();
  const old = setup('A', async ({ resource, page }) => {
    if (resource.alias === 'A1' && page === 1) { entered.resolve(); await release.promise; }
  });
  const next = setup('B');
  try {
    const a = startEffect(old.repository);
    await entered.promise;
    a.cleanup();
    assert.equal(old.calls.find(row => row.alias === 'A1' && row.page === 1).signal.aborted, true);
    const b = startEffect(next.repository);
    await b.settled;
    assert.deepEqual(ids(b.published[0].batches), [String(next.resources[0].quizIds[0])]);
    release.resolve();
    await a.settled;
    assert.equal(a.published.length, 0);
    b.cleanup();
  } finally { release.resolve(); old.close(); next.close(); }
});

test('actual repository de-duplicates synthetic repeated quiz IDs across page payloads', async () => {
  const s = setup();
  try {
    const a1 = s.resources[0];
    const first = mockList(s.db, s.actor.userId, a1.sessionId).body;
    const last = structuredClone(mockList(s.db, s.actor.userId, a1.sessionId, 1).body);
    last.data.quizzes.unshift(first.data.quizzes[0]);
    let count = 0;
    const repository = createSessionsRepository(async () => count++ === 0 ? first : last);
    const result = await repository.listQuizHistory(String(a1.sessionId));
    assert.equal(result.length, 101);
    assert.equal(count, 2);
  } finally { s.close(); }
});

test('metadata-free history and legacy listQuizzes both stay single queryless reads', async () => {
  const s = setup();
  try {
    const data = mockList(s.db, s.actor.userId, s.resources[0].sessionId).body.data;
    const pathsSeen = [];
    const repository = createSessionsRepository(async path => {
      pathsSeen.push(path);
      return { success: true, data: { quizzes: data.quizzes }, message: 'LOCAL_MOCK_ONLY' };
    });
    assert.equal((await repository.listQuizHistory(String(s.resources[0].sessionId))).length, 100);
    assert.equal((await repository.listQuizzes(String(s.resources[0].sessionId))).length, 100);
    assert.equal(pathsSeen.length, 2);
    assert.ok(pathsSeen.every(path => !path.includes('?')));
  } finally { s.close(); }
});

test('actual collection helpers order equal timestamps by integer-string ID without precision loss', () => {
  const batches = [{ session: { id: '1' }, quizzes: ['2', '9007199254740992', '10', '9007199254740993']
    .map(quizId => ({ quizId, createdAt: CREATED_AT })) }];
  assert.deepEqual(ids(batches), ['9007199254740993', '9007199254740992', '10', '2']);
});
