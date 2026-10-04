import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { DatabaseSync } from 'node:sqlite';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';
import {
  BASE_COMMIT, CREATED_AT, MANIFEST_ID, RUN_LABEL, cleanupFixture, fingerprint,
  mockList, openLocalDb, parseArgs, runLocal, seedFixture, snapshot, verifyManifest,
} from './fixture.mjs';

function withDb(work) {
  const db = openLocalDb();
  try { return work(db); } finally { db.close(); }
}

function expectCode(work, code) {
  assert.throws(work, error => error.code === code);
}

function committed(db) {
  return seedFixture(db, { dryRun: false });
}

test('default CLI options are a dry-run and contain no external target', () => {
  assert.deepEqual(parseArgs([]), { mode: 'dry-run', includeMockResponses: false, help: false });
  assert.equal(runLocal().seedRolledBack, true);
});

test('manifest scopes 2 learners, 3 materials, 3 sessions, and precisely 103 local OX IDs', () => withDb(db => {
  const { manifest } = committed(db);
  assert.equal(manifest.runLabel, RUN_LABEL);
  assert.equal(manifest.manifestId, MANIFEST_ID);
  assert.equal(manifest.baseCommit, BASE_COMMIT);
  assert.equal(manifest.idsAreDevIds, false);
  assert.equal(manifest.devExecuted, false);
  assert.ok(Object.values(manifest.approval).every(value => value === false));
  assert.equal(manifest.tables.fixture_users.rowCount, 2);
  assert.equal(manifest.tables.fixture_materials.rowCount, 3);
  assert.equal(manifest.tables.fixture_sessions.rowCount, 3);
  const ids = manifest.tables.fixture_quizzes.ids;
  assert.equal(ids.length, 103);
  assert.equal(new Set(ids).size, 103);
  assert.deepEqual(manifest.resources.map(row => row.quizIds.length), [101, 1, 1]);
  assert.equal(verifyManifest(db, manifest), true);
}));

test('A1 stable tie ordering, unsubmitted state, PDF page, and 100 -> 1 -> 0 metadata', () => withDb(db => {
  const { manifest, mockResponses } = committed(db);
  const data = ['A1_page0', 'A1_page1', 'A1_page2'].map(name => mockResponses[name].body.data);
  assert.deepEqual(data.map(page => page.quizzes.length), [100, 1, 0]);
  assert.deepEqual(data.map(page => page.page), [0, 1, 2]);
  assert.deepEqual(data.map(page => page.size), [100, 100, 100]);
  assert.deepEqual(data.map(page => page.totalElements), [101, 101, 101]);
  assert.deepEqual(data.map(page => page.totalPages), [2, 2, 2]);
  assert.deepEqual(data.map(page => page.hasNext), [true, false, false]);
  const all = [...data[0].quizzes, ...data[1].quizzes];
  const expectedIds = [...manifest.resources[0].quizIds].sort((left, right) => right - left);
  assert.deepEqual(all.map(quiz => quiz.quizId), expectedIds);
  assert.equal(new Set(all.map(quiz => quiz.createdAt)).size, 1);
  assert.ok(all.every(quiz => quiz.createdAt === CREATED_AT && quiz.page === 7 && quiz.quizType === 'OX'
    && quiz.questionCount === 1 && !quiz.submitted && quiz.score === null && quiz.maxScore === null
    && quiz.passed === null));
  assert.notEqual(data[0].page, all[0].page);
}));

test('sentinels stay isolated; A cannot read B1 and B cannot read A1/A2', () => withDb(db => {
  const { manifest, mockResponses } = committed(db);
  const a = manifest.learners[0].userId;
  const b = manifest.learners[1].userId;
  const [a1, a2, b1] = manifest.resources;
  const list = (actor, session) => mockList(db, actor, session.sessionId);
  assert.deepEqual(list(a, a2).body.data.quizzes.map(row => row.quizId), a2.quizIds);
  assert.deepEqual(list(b, b1).body.data.quizzes.map(row => row.quizId), b1.quizIds);
  for (const response of [list(a, b1), list(b, a1), list(b, a2), list(a, { sessionId: 9999 })]) {
    assert.equal(response.status, 404);
    assert.equal(response.body.error.code, 'SESSION_NOT_FOUND');
    assert.equal(response.body.data, undefined);
  }
  const a1Ids = new Set([...mockResponses.A1_page0.body.data.quizzes,
    ...mockResponses.A1_page1.body.data.quizzes].map(row => row.quizId));
  assert.ok([...a2.quizIds, ...b1.quizIds].every(id => !a1Ids.has(id)));
}));

test('page and size boundary errors; minimum size and deleted session model', () => withDb(db => {
  const { manifest } = committed(db);
  const a1 = manifest.resources[0];
  for (const [page, size] of [[-1, 100], [0, 0], [0, 101], [0.5, 100], [0, 1.5]]) {
    const response = mockList(db, a1.userId, a1.sessionId, page, size);
    assert.equal(response.status, 400);
    assert.equal(response.body.error.code, 'VALIDATION_FAILED');
  }
  assert.equal(mockList(db, a1.userId, a1.sessionId, 0, 1).body.data.totalPages, 101);
  db.prepare('UPDATE fixture_sessions SET status = ? WHERE id = ?').run('DELETED', a1.sessionId);
  assert.equal(mockList(db, a1.userId, a1.sessionId).status, 404);
}));

test('same page retry produces identical IDs and does not mutate any local rows', () => withDb(db => {
  const { manifest } = committed(db);
  const a1 = manifest.resources[0];
  const before = fingerprint(snapshot(db));
  const first = mockList(db, a1.userId, a1.sessionId, 1, 100);
  const retry = mockList(db, a1.userId, a1.sessionId, 1, 100);
  assert.deepEqual(retry, first);
  assert.equal(fingerprint(snapshot(db)), before);
}));

test('reports contain no private answers, credentials, or fabricated real-response evidence', () => {
  const report = runLocal({ includeMockResponses: true });
  const serialized = JSON.stringify(report);
  for (const field of ['private_answer_json', 'answerValue', 'explanation', 'password', 'accessToken', 'refreshToken']) {
    assert.ok(!serialized.includes(field), field);
  }
  assert.equal(report.networkCalls, 0);
  assert.equal(report.devExecuted, false);
  assert.ok(Object.values(report.mockResponses).every(response => response.evidence === 'LOCAL_MOCK_ONLY'));
});

test('seed dry-run rolls back all scoped rows and preserves synthetic observer rows', () => withDb(db => {
  const before = snapshot(db);
  const { manifest } = seedFixture(db);
  assert.equal(manifest.localCommitted, false);
  assert.deepEqual(snapshot(db), before);
  expectCode(() => cleanupFixture(db, manifest), 'LOCAL_MANIFEST_REQUIRED');
}));

for (const stage of ['after-users', 'after-materials', 'after-sessions', 'after-quizzes']) {
  test(`injected ${stage} seed failure rolls back the entire transaction`, () => withDb(db => {
    const before = snapshot(db);
    expectCode(() => seedFixture(db, { dryRun: false, injectFault: stage }), 'INJECTED_LOCAL_FAILURE');
    assert.deepEqual(snapshot(db), before);
    assert.equal(committed(db).manifest.tables.fixture_quizzes.rowCount, 103);
  }));
}

test('accidental outside-row mutation is detected before commit and rolled back', () => withDb(db => {
  const before = snapshot(db);
  expectCode(() => seedFixture(db, { dryRun: false, injectFault: 'outside-row' }), 'OUTSIDE_ROWS_CHANGED');
  assert.deepEqual(snapshot(db), before);
}));

test('duplicate run is rejected without changing the existing fixture', () => withDb(db => {
  committed(db);
  const before = snapshot(db);
  expectCode(() => committed(db), 'DUPLICATE_RUN');
  assert.deepEqual(snapshot(db), before);
}));

test('cleanup defaults to dry-run, deletes only manifest IDs, then restores all rows', () => withDb(db => {
  const { manifest } = committed(db);
  const before = snapshot(db);
  const result = cleanupFixture(db, manifest);
  assert.deepEqual(result, { deletedLocalRows: 111, dryRun: true,
    outsideRowsUnchanged: true, runLedgerRetained: true });
  assert.deepEqual(snapshot(db), before);
  assert.equal(verifyManifest(db, manifest), true);
}));

test('interrupted cleanup rolls back prior deletes', () => withDb(db => {
  const { manifest } = committed(db);
  const before = snapshot(db);
  expectCode(() => cleanupFixture(db, manifest, { dryRun: false, confirmation: 'LOCAL_MEMORY_ONLY',
    injectFault: 'after-first-delete' }), 'INJECTED_LOCAL_FAILURE');
  assert.deepEqual(snapshot(db), before);
}));

test('local cleanup commit preserves outside rows and retains duplicate-run tombstone', () => withDb(db => {
  const { manifest } = committed(db);
  expectCode(() => cleanupFixture(db, manifest, { dryRun: false }), 'LOCAL_CLEANUP_CONFIRMATION_REQUIRED');
  const before = snapshot(db);
  const result = cleanupFixture(db, manifest, { dryRun: false, confirmation: 'LOCAL_MEMORY_ONLY' });
  assert.equal(result.deletedLocalRows, 111);
  const after = snapshot(db);
  for (const table of Object.keys(manifest.tables)) {
    assert.deepEqual(after[table], before[table].filter(row => row.run_label !== RUN_LABEL));
  }
  assert.equal(db.prepare('SELECT state FROM fixture_runs WHERE run_label = ?').get(RUN_LABEL).state, 'CLEANED_LOCAL');
  const cleaned = snapshot(db);
  expectCode(() => committed(db), 'DUPLICATE_RUN');
  expectCode(() => cleanupFixture(db, manifest), 'MANIFEST_BINDING_MISMATCH');
  assert.deepEqual(snapshot(db), cleaned);
}));

test('tampered manifest cannot broaden cleanup IDs or rebind ownership', () => withDb(db => {
  const { manifest } = committed(db);
  const before = snapshot(db);
  for (const mutate of [
    value => value.tables.fixture_users.ids.push(1),
    value => { value.resources[0].userId = value.learners[1].userId; },
    value => { value.tables.fixture_quizzes.ids.pop(); },
  ]) {
    const changed = structuredClone(manifest);
    mutate(changed);
    expectCode(() => cleanupFixture(db, changed), 'MANIFEST_BINDING_MISMATCH');
    assert.deepEqual(snapshot(db), before);
  }
}));

for (const [name, mutation, code] of [
  ['ownership', (db, manifest) => db.prepare('UPDATE fixture_sessions SET user_id = ? WHERE id = ?')
    .run(manifest.learners[1].userId, manifest.resources[0].sessionId), 'OWNERSHIP_MISMATCH'],
  ['material status', (db, manifest) => db.prepare('UPDATE fixture_materials SET status = ? WHERE id = ?')
    .run('DELETED', manifest.resources[0].materialId), 'RESOURCE_STATE_MISMATCH'],
  ['missing quiz', (db, manifest) => db.prepare('DELETE FROM fixture_quizzes WHERE id = ?')
    .run(manifest.resources[0].quizIds[0]), 'FIXTURE_ROW_COUNTS_MISMATCH'],
  ['created_at tie', (db, manifest) => db.prepare('UPDATE fixture_quizzes SET created_at = ? WHERE id = ?')
    .run('2026-10-04T00:00:01.000Z', manifest.resources[0].quizIds[0]), 'QUIZ_PRECONDITION_MISMATCH'],
  ['quiz row image', (db, manifest) => db.prepare('UPDATE fixture_quizzes SET title = ? WHERE id = ?')
    .run('changed', manifest.resources[0].quizIds[0]), 'MANIFEST_ROWS_CHANGED'],
  ['submission', (db, manifest) => db.prepare(`INSERT INTO fixture_submissions
      (run_label, quiz_id, user_id, score, max_score, passed) VALUES (?, ?, ?, 10, 10, 1)`)
    .run(RUN_LABEL, manifest.resources[0].quizIds[0], manifest.learners[0].userId), 'QUIZ_MUST_BE_UNSUBMITTED'],
  ['unmanifested dependent', (db, manifest) => db.prepare(`INSERT INTO fixture_dependents
      (run_label, session_id, kind) VALUES (?, ?, ?)`)
    .run(RUN_LABEL, manifest.resources[0].sessionId, 'synthetic-note'), 'UNEXPECTED_DEPENDENT_ROWS'],
  ['outside observer', db => db.prepare('UPDATE fixture_users SET alias = ? WHERE id = 1')
    .run('changed'), 'OUTSIDE_ROWS_CHANGED'],
]) {
  test(`${name} precondition mismatch rejects cleanup without further mutations`, () => withDb(db => {
    const { manifest } = committed(db);
    mutation(db, manifest);
    const beforeAttempt = snapshot(db);
    expectCode(() => cleanupFixture(db, manifest, { dryRun: false, confirmation: 'LOCAL_MEMORY_ONLY' }), code);
    assert.deepEqual(snapshot(db), beforeAttempt);
  }));
}

test('factory, connection branding, attachment, and foreign-key guards refuse misuse', () => {
  expectCode(() => openLocalDb('fixture.sqlite'), 'DATABASE_TARGET_ARGUMENT_FORBIDDEN');
  const other = new DatabaseSync(':memory:');
  try { expectCode(() => seedFixture(other), 'LOCAL_MEMORY_DATABASE_REQUIRED'); } finally { other.close(); }
  withDb(db => {
    db.exec("ATTACH DATABASE ':memory:' AS forbidden");
    expectCode(() => seedFixture(db), 'ATTACHED_OR_FILE_DATABASE_FORBIDDEN');
  });
  withDb(db => {
    db.exec('PRAGMA foreign_keys = OFF');
    expectCode(() => seedFixture(db), 'FOREIGN_KEYS_REQUIRED');
  });
});

test('DEV/file/URL/credentials/apply flags and duplicated mode flags are rejected', () => {
  for (const args of [
    ['--mode', 'dev'], ['--mode', 'apply'], ['--mode'], ['--apply'], ['--target', 'dev'],
    ['--db', 'fixture.sqlite'], ['--dsn', 'mock://forbidden.example'], ['--url', 'https://forbidden.example'],
    ['--token', 'SYNTHETIC_TOKEN_NOT_A_SECRET'], ['--mode', 'dry-run', '--mode', 'local-rehearsal'],
  ]) assert.throws(() => parseArgs(args));
  expectCode(() => runLocal({ mode: 'dev' }), 'ONLY_LOCAL_MODES_ALLOWED');
});

test('CLI runs locally even with dummy DB variables; rejected token input is not echoed', () => {
  const script = fileURLToPath(new URL('./fixture.mjs', import.meta.url));
  const stdout = execFileSync(process.execPath, ['--disable-warning=ExperimentalWarning', script], {
    encoding: 'utf8', env: { ...process.env, EDUPILOT_DB_URL: 'jdbc:mock://forbidden.example',
      EDUPILOT_DB_PASSWORD: 'SYNTHETIC_ENV_NOT_A_SECRET' },
  });
  const report = JSON.parse(stdout);
  assert.equal(report.environment, 'local-synthetic-memory');
  assert.equal(report.seedRolledBack, true);
  assert.equal(report.devExecuted, false);
  assert.ok(!stdout.includes('SYNTHETIC_ENV_NOT_A_SECRET'));
  const refused = spawnSync(process.execPath, [script, '--token', 'SYNTHETIC_TOKEN_NOT_A_SECRET'], { encoding: 'utf8' });
  assert.equal(refused.status, 1);
  assert.equal(refused.stdout, '');
  assert.ok(!refused.stderr.includes('SYNTHETIC_TOKEN_NOT_A_SECRET'));
});

test('local-rehearsal reports cleanup rollback while remaining explicitly local', () => {
  const report = runLocal({ mode: 'local-rehearsal' });
  assert.equal(report.seedRolledBack, false);
  assert.equal(report.manifest.localCommitted, true);
  assert.equal(report.cleanupRehearsal.dryRun, true);
  assert.equal(report.cleanupRehearsal.outsideRowsUnchanged, true);
  assert.equal(report.devExecuted, false);
});
