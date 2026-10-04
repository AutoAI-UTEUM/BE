/**
 * NOTE04 fixture rehearsal. Only a branded SQLite :memory: connection is allowed.
 * No network, credentials, filesystem DB, production schema, or DEV apply mode.
 * Node >= 24; standalone, with no npm/Gradle dependency or application imports.
 */
import { createHash } from 'node:crypto';
import { DatabaseSync } from 'node:sqlite';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export const RUN_LABEL = 'NOTE04-DEV-20261004-R1';
export const MANIFEST_ID = 'note04-dev-20261004-r1-plan';
export const BASE_COMMIT = 'ef8f0f74a3d2d46a0adc9aabf1d9dad9577dd938';
export const CREATED_AT = '2026-10-04T00:00:00.000Z';
const LOCAL_ENVIRONMENT = 'local-synthetic-memory';
const CONTROL_RUN = 'LOCAL-SYNTHETIC-CONTROL';
const TABLES = [
  'fixture_users', 'fixture_materials', 'fixture_sessions',
  'fixture_quizzes', 'fixture_submissions', 'fixture_dependents',
];
const localConnections = new WeakSet();
const FAULTS = ['after-users', 'after-materials', 'after-sessions', 'after-quizzes', 'after-ledger-seal', 'outside-row'];

export class FixtureError extends Error {
  constructor(code) {
    super(code);
    this.name = 'FixtureError';
    this.code = code;
  }
}

function requireCondition(condition, code) {
  if (!condition) throw new FixtureError(code);
}

function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical);
  if (value !== null && typeof value === 'object') {
    return Object.fromEntries(Object.keys(value).sort().map(key => [key, canonical(value[key])]));
  }
  return value;
}

export function fingerprint(value) {
  return createHash('sha256').update(JSON.stringify(canonical(value))).digest('hex');
}

function requireLocal(db) {
  requireCondition(localConnections.has(db), 'LOCAL_MEMORY_DATABASE_REQUIRED');
  const databases = db.prepare('PRAGMA database_list').all();
  requireCondition(databases.length === 1 && databases[0].name === 'main' && databases[0].file === '',
    'ATTACHED_OR_FILE_DATABASE_FORBIDDEN');
  requireCondition(db.prepare('PRAGMA foreign_keys').get().foreign_keys === 1, 'FOREIGN_KEYS_REQUIRED');
}

/** No target argument is accepted, including another :memory: connection. */
export function openLocalDb(...args) {
  requireCondition(args.length === 0, 'DATABASE_TARGET_ARGUMENT_FORBIDDEN');
  const db = new DatabaseSync(':memory:');
  localConnections.add(db);
  db.exec(`
    PRAGMA foreign_keys = ON;
    CREATE TABLE fixture_runs (
      run_label TEXT PRIMARY KEY, manifest_id TEXT UNIQUE NOT NULL,
      state TEXT NOT NULL, manifest_sha256 TEXT NOT NULL DEFAULT ''
    );
    CREATE TABLE fixture_users (
      id INTEGER PRIMARY KEY, run_label TEXT NOT NULL REFERENCES fixture_runs(run_label),
      alias TEXT NOT NULL, role TEXT NOT NULL CHECK(role = 'LEARNER'),
      status TEXT NOT NULL, UNIQUE(run_label, alias)
    );
    CREATE TABLE fixture_materials (
      id INTEGER PRIMARY KEY, run_label TEXT NOT NULL REFERENCES fixture_runs(run_label),
      alias TEXT NOT NULL, owner_id INTEGER NOT NULL REFERENCES fixture_users(id),
      status TEXT NOT NULL, processing_status TEXT NOT NULL,
      page_count INTEGER NOT NULL CHECK(page_count >= 7), UNIQUE(run_label, alias)
    );
    CREATE TABLE fixture_sessions (
      id INTEGER PRIMARY KEY, run_label TEXT NOT NULL REFERENCES fixture_runs(run_label),
      alias TEXT NOT NULL, user_id INTEGER NOT NULL REFERENCES fixture_users(id),
      material_id INTEGER NOT NULL REFERENCES fixture_materials(id),
      status TEXT NOT NULL, current_page INTEGER NOT NULL,
      active_turn_request_id TEXT, active_quiz_id INTEGER, UNIQUE(run_label, alias)
    );
    CREATE TABLE fixture_quizzes (
      id INTEGER PRIMARY KEY, run_label TEXT NOT NULL REFERENCES fixture_runs(run_label),
      session_id INTEGER NOT NULL REFERENCES fixture_sessions(id), title TEXT NOT NULL,
      quiz_type TEXT NOT NULL CHECK(quiz_type = 'OX'), page_number INTEGER NOT NULL,
      coverage_start_page INTEGER NOT NULL, coverage_end_page INTEGER NOT NULL,
      public_question_json TEXT NOT NULL, private_answer_json TEXT NOT NULL,
      schema_version TEXT NOT NULL, created_at TEXT NOT NULL
    );
    CREATE TABLE fixture_submissions (
      id INTEGER PRIMARY KEY, run_label TEXT NOT NULL REFERENCES fixture_runs(run_label),
      quiz_id INTEGER NOT NULL REFERENCES fixture_quizzes(id),
      user_id INTEGER NOT NULL REFERENCES fixture_users(id), score INTEGER NOT NULL,
      max_score INTEGER NOT NULL, passed INTEGER NOT NULL, UNIQUE(quiz_id, user_id)
    );
    CREATE TABLE fixture_dependents (
      id INTEGER PRIMARY KEY, run_label TEXT NOT NULL REFERENCES fixture_runs(run_label),
      session_id INTEGER NOT NULL REFERENCES fixture_sessions(id), kind TEXT NOT NULL
    );
  `);
  // Observer rows are entirely synthetic and excluded from fixture counts/IDs.
  db.prepare('INSERT INTO fixture_runs VALUES (?, ?, ?, ?)')
    .run(CONTROL_RUN, 'local-control-only', 'CONTROL', '');
  db.prepare('INSERT INTO fixture_users VALUES (1, ?, ?, ?, ?)')
    .run(CONTROL_RUN, 'observer', 'LEARNER', 'ACTIVE');
  db.prepare('INSERT INTO fixture_materials VALUES (1, ?, ?, 1, ?, ?, 7)')
    .run(CONTROL_RUN, 'observer', 'ACTIVE', 'READY');
  db.prepare('INSERT INTO fixture_sessions VALUES (1, ?, ?, 1, 1, ?, 7, NULL, NULL)')
    .run(CONTROL_RUN, 'observer', 'ACTIVE');
  insertQuiz(db, CONTROL_RUN, 1, 'CONTROL', 1);
  db.prepare('INSERT INTO fixture_submissions VALUES (1, ?, 1, 1, 10, 10, 1)').run(CONTROL_RUN);
  db.prepare('INSERT INTO fixture_dependents VALUES (1, ?, 1, ?)').run(CONTROL_RUN, 'synthetic-note');
  return db;
}

function insertQuiz(db, runLabel, sessionId, alias, index) {
  // Deterministic synthetic OX question/answer. Never include the answer in reports.
  const publicJson = JSON.stringify({ schemaVersion: '1.0', questions: [
    { questionId: 'q1', questionText: 'Synthetic OX fixture', points: 10, choices: null },
  ] });
  const privateJson = JSON.stringify({ schemaVersion: '1.0', questions: [
    { questionId: 'q1', answerValue: true, explanation: 'Synthetic-only explanation' },
  ] });
  return Number(db.prepare(`INSERT INTO fixture_quizzes
    (run_label, session_id, title, quiz_type, page_number, coverage_start_page,
     coverage_end_page, public_question_json, private_answer_json, schema_version, created_at)
    VALUES (?, ?, ?, 'OX', 7, 1, 7, ?, ?, '1.0', ?)`).run(
    runLabel, sessionId, `[${runLabel}][${alias}] OX ${String(index).padStart(3, '0')}`,
    publicJson, privateJson, CREATED_AT,
  ).lastInsertRowid);
}

export function snapshot(db) {
  requireLocal(db);
  return Object.fromEntries(['fixture_runs', ...TABLES].map(table => [
    table, db.prepare(`SELECT * FROM ${table} ORDER BY ${table === 'fixture_runs' ? 'run_label' : 'id'}`).all(),
  ]));
}

function outsideFingerprint(db) {
  const rows = Object.fromEntries(['fixture_runs', ...TABLES].map(table => [table,
    db.prepare(`SELECT * FROM ${table} WHERE run_label <> ?
      ORDER BY ${table === 'fixture_runs' ? 'run_label' : 'id'}`).all(RUN_LABEL),
  ]));
  return fingerprint(rows);
}

function scopedRows(db, table) {
  return db.prepare(`SELECT * FROM ${table} WHERE run_label = ? ORDER BY id`).all(RUN_LABEL);
}

function transaction(db, work, commit) {
  requireLocal(db);
  db.exec('BEGIN IMMEDIATE');
  try {
    const result = work();
    db.exec(commit ? 'COMMIT' : 'ROLLBACK');
    return result;
  } catch (error) {
    db.exec('ROLLBACK');
    throw error;
  }
}

function verifyFixture(db) {
  const users = scopedRows(db, 'fixture_users');
  const materials = scopedRows(db, 'fixture_materials');
  const sessions = scopedRows(db, 'fixture_sessions');
  const quizzes = scopedRows(db, 'fixture_quizzes');
  requireCondition(users.length === 2 && materials.length === 3 && sessions.length === 3 && quizzes.length === 103,
    'FIXTURE_ROW_COUNTS_MISMATCH');
  requireCondition(users.map(row => row.alias).sort().join(',') === 'A,B'
    && users.every(row => row.role === 'LEARNER' && row.status === 'ACTIVE'), 'LEARNER_PRECONDITION_MISMATCH');
  requireCondition(sessions.map(row => row.alias).sort().join(',') === 'A1,A2,B1'
    && materials.map(row => row.alias).sort().join(',') === 'A1,A2,B1', 'RESOURCE_ALIAS_MISMATCH');
  for (const session of sessions) {
    const owner = users.find(row => row.alias === session.alias[0]);
    const material = materials.find(row => row.alias === session.alias);
    requireCondition(session.user_id === owner.id && session.material_id === material.id
      && material.owner_id === owner.id, 'OWNERSHIP_MISMATCH');
    requireCondition(session.status === 'ACTIVE' && session.current_page === 7
      && session.active_turn_request_id === null && session.active_quiz_id === null
      && material.status === 'ACTIVE' && material.processing_status === 'READY' && material.page_count === 7,
    'RESOURCE_STATE_MISMATCH');
    const expectedCount = session.alias === 'A1' ? 101 : 1;
    const selected = quizzes.filter(row => row.session_id === session.id);
    requireCondition(selected.length === expectedCount, 'SESSION_QUIZ_COUNT_MISMATCH');
  }
  requireCondition(quizzes.every(row => row.quiz_type === 'OX' && row.created_at === CREATED_AT
    && row.page_number === 7 && row.coverage_start_page === 1 && row.coverage_end_page === 7
    && row.schema_version === '1.0' && JSON.parse(row.public_question_json).questions.length === 1),
  'QUIZ_PRECONDITION_MISMATCH');
  requireCondition(scopedRows(db, 'fixture_submissions').length === 0
    && db.prepare(`SELECT count(*) AS n FROM fixture_submissions s
      JOIN fixture_quizzes q ON q.id = s.quiz_id WHERE q.run_label = ?`).get(RUN_LABEL).n === 0,
  'QUIZ_MUST_BE_UNSUBMITTED');
  requireCondition(scopedRows(db, 'fixture_dependents').length === 0
    && db.prepare(`SELECT count(*) AS n FROM fixture_dependents d
      JOIN fixture_sessions s ON s.id = d.session_id WHERE s.run_label = ?`).get(RUN_LABEL).n === 0,
  'UNEXPECTED_DEPENDENT_ROWS');
}

function createManifest(db, beforeHash, outsideHash, localCommitted) {
  const tables = Object.fromEntries(TABLES.map(table => {
    const rows = scopedRows(db, table);
    return [table, { ids: rows.map(row => row.id), rowCount: rows.length, rowsSha256: fingerprint(rows) }];
  }));
  return {
    schemaVersion: 1, manifestId: MANIFEST_ID, runLabel: RUN_LABEL, baseCommit: BASE_COMMIT,
    environment: LOCAL_ENVIRONMENT, localCommitted, devExecuted: false, idsAreDevIds: false,
    approval: { devRead: false, devWrite: false, accountCreation: false, consent: false,
      credentialSharing: false, permanentDeletion: false },
    learners: scopedRows(db, 'fixture_users').map(row => ({ alias: row.alias, userId: row.id })),
    resources: scopedRows(db, 'fixture_sessions').map(row => ({ alias: row.alias,
      userId: row.user_id, materialId: row.material_id, sessionId: row.id,
      disposition: 'CREATED_LOCAL_ONLY', quizIds: scopedRows(db, 'fixture_quizzes')
        .filter(quiz => quiz.session_id === row.id).map(quiz => quiz.id),
    })),
    invariants: { learnerCount: 2, materialCount: 3, sessionCount: 3, quizCount: 103,
      a1Count: 101, a2Count: 1, b1Count: 1, submissionCount: 0, dependentCount: 0,
      a1CreatedAt: CREATED_AT, order: ['createdAt DESC', 'quizId DESC'], quizPdfPage: 7 },
    tables, beforeSnapshotSha256: beforeHash, outsideRowsSha256: outsideHash,
    owners: { execution: 'BE 한승준 (제안/미확정)', cleanup: 'BE 한승준 (제안/미확정)',
      feAcceptance: 'FE 이감재 (제안/미확정)' },
  };
}

export function seedFixture(db, { dryRun = true, injectFault = null } = {}) {
  requireLocal(db);
  requireCondition(typeof dryRun === 'boolean', 'DRY_RUN_BOOLEAN_REQUIRED');
  requireCondition(injectFault === null || FAULTS.includes(injectFault), 'UNKNOWN_LOCAL_FAULT');
  const beforeHash = fingerprint(snapshot(db));
  const outsideHash = outsideFingerprint(db);
  const failAt = stage => { if (injectFault === stage) throw new FixtureError('INJECTED_LOCAL_FAILURE'); };
  const result = transaction(db, () => {
    requireCondition(!db.prepare('SELECT 1 FROM fixture_runs WHERE run_label = ? OR manifest_id = ?')
      .get(RUN_LABEL, MANIFEST_ID), 'DUPLICATE_RUN');
    db.prepare('INSERT INTO fixture_runs (run_label, manifest_id, state) VALUES (?, ?, ?)')
      .run(RUN_LABEL, MANIFEST_ID, 'SEEDED_LOCAL');
    const users = {};
    for (const alias of ['A', 'B']) {
      users[alias] = Number(db.prepare(`INSERT INTO fixture_users (run_label, alias, role, status)
        VALUES (?, ?, 'LEARNER', 'ACTIVE')`).run(RUN_LABEL, alias).lastInsertRowid);
    }
    failAt('after-users');
    const materials = {};
    for (const alias of ['A1', 'A2', 'B1']) {
      materials[alias] = Number(db.prepare(`INSERT INTO fixture_materials
        (run_label, alias, owner_id, status, processing_status, page_count)
        VALUES (?, ?, ?, 'ACTIVE', 'READY', 7)`).run(RUN_LABEL, alias, users[alias[0]]).lastInsertRowid);
    }
    failAt('after-materials');
    const sessions = {};
    for (const alias of ['A1', 'A2', 'B1']) {
      sessions[alias] = Number(db.prepare(`INSERT INTO fixture_sessions
        (run_label, alias, user_id, material_id, status, current_page)
        VALUES (?, ?, ?, ?, 'ACTIVE', 7)`).run(RUN_LABEL, alias, users[alias[0]], materials[alias]).lastInsertRowid);
    }
    failAt('after-sessions');
    for (const alias of ['A1', 'A2', 'B1']) {
      const count = alias === 'A1' ? 101 : 1;
      for (let index = 1; index <= count; index++) insertQuiz(db, RUN_LABEL, sessions[alias], alias, index);
    }
    failAt('after-quizzes');
    if (injectFault === 'outside-row') db.prepare('UPDATE fixture_users SET alias = ? WHERE id = 1')
      .run('unexpected-observer-change');
    verifyFixture(db);
    requireCondition(outsideFingerprint(db) === outsideHash, 'OUTSIDE_ROWS_CHANGED');
    const manifest = createManifest(db, beforeHash, outsideHash, !dryRun);
    db.prepare('UPDATE fixture_runs SET manifest_sha256 = ? WHERE run_label = ?')
      .run(fingerprint(manifest), RUN_LABEL);
    failAt('after-ledger-seal');
    const mockResponses = acceptanceResponses(db, manifest);
    return { manifest, mockResponses };
  }, !dryRun);
  if (dryRun) requireCondition(fingerprint(snapshot(db)) === beforeHash, 'DRY_RUN_ROLLBACK_MISMATCH');
  return result;
}

/** Validate exact manifest membership, state, ownership, unchanged row images, and ledger binding. */
export function verifyManifest(db, manifest) {
  requireLocal(db);
  requireCondition(manifest?.manifestId === MANIFEST_ID && manifest.runLabel === RUN_LABEL
    && manifest.environment === LOCAL_ENVIRONMENT && manifest.localCommitted === true
    && manifest.idsAreDevIds === false && manifest.devExecuted === false, 'LOCAL_MANIFEST_REQUIRED');
  const ledger = db.prepare('SELECT * FROM fixture_runs WHERE run_label = ?').get(RUN_LABEL);
  requireCondition(ledger?.state === 'SEEDED_LOCAL' && ledger.manifest_id === MANIFEST_ID
    && ledger.manifest_sha256 === fingerprint(manifest), 'MANIFEST_BINDING_MISMATCH');
  verifyFixture(db);
  for (const table of TABLES) {
    const rows = scopedRows(db, table);
    const expected = manifest.tables[table];
    requireCondition(fingerprint(rows.map(row => row.id)) === fingerprint(expected.ids)
      && rows.length === expected.rowCount && fingerprint(rows) === expected.rowsSha256,
    'MANIFEST_ROWS_CHANGED');
  }
  requireCondition(outsideFingerprint(db) === manifest.outsideRowsSha256, 'OUTSIDE_ROWS_CHANGED');
  return true;
}

/** Cleanup is a local rehearsal, never an authorization to delete DEV resources. */
export function cleanupFixture(db, manifest, { dryRun = true, confirmation = null, injectFault = null } = {}) {
  requireCondition(typeof dryRun === 'boolean', 'DRY_RUN_BOOLEAN_REQUIRED');
  requireCondition(dryRun || confirmation === 'LOCAL_MEMORY_ONLY', 'LOCAL_CLEANUP_CONFIRMATION_REQUIRED');
  requireCondition(injectFault === null || injectFault === 'after-first-delete', 'UNKNOWN_LOCAL_FAULT');
  const beforeHash = fingerprint(snapshot(db));
  const result = transaction(db, () => {
    verifyManifest(db, manifest);
    let deleted = 0;
    for (const table of ['fixture_quizzes', 'fixture_sessions', 'fixture_materials', 'fixture_users']) {
      for (const id of manifest.tables[table].ids) {
        const changed = db.prepare(`DELETE FROM ${table} WHERE id = ? AND run_label = ?`).run(id, RUN_LABEL).changes;
        requireCondition(changed === 1, 'DELETE_ROW_COUNT_MISMATCH');
        deleted++;
        if (injectFault === 'after-first-delete') throw new FixtureError('INJECTED_LOCAL_FAILURE');
      }
    }
    requireCondition(deleted === 111 && TABLES.every(table => scopedRows(db, table).length === 0),
      'CLEANUP_ROW_COUNTS_MISMATCH');
    requireCondition(outsideFingerprint(db) === manifest.outsideRowsSha256, 'OUTSIDE_ROWS_CHANGED');
    // Keep the tombstone: a cleaned run label must not silently create another batch.
    db.prepare('UPDATE fixture_runs SET state = ? WHERE run_label = ?').run('CLEANED_LOCAL', RUN_LABEL);
    return { deletedLocalRows: deleted, dryRun, outsideRowsUnchanged: true, runLedgerRetained: true };
  }, !dryRun);
  if (dryRun) requireCondition(fingerprint(snapshot(db)) === beforeHash, 'CLEANUP_DRY_RUN_ROLLBACK_MISMATCH');
  return result;
}

function mockError(status, code, message) {
  return { evidence: 'LOCAL_MOCK_ONLY', status, body: { success: false,
    error: { code, message, details: [] }, traceId: 'local-note04-mock', timestamp: CREATED_AT } };
}

/** Model of the BE list envelope, not an HTTP server or authentication test. */
export function mockList(db, userId, sessionId, page = 0, size = 100) {
  requireLocal(db);
  if (!Number.isInteger(page) || page < 0 || page > 2147483647
    || !Number.isInteger(size) || size < 1 || size > 100) {
    return mockError(400, 'VALIDATION_FAILED', '요청 값이 올바르지 않습니다.');
  }
  if (!db.prepare(`SELECT id FROM fixture_sessions
    WHERE id = ? AND user_id = ? AND status <> 'DELETED'`).get(sessionId, userId)) {
    return mockError(404, 'SESSION_NOT_FOUND', '학습 세션을 찾을 수 없습니다.');
  }
  const totalElements = db.prepare('SELECT count(*) AS n FROM fixture_quizzes WHERE session_id = ?').get(sessionId).n;
  const rows = db.prepare(`SELECT q.*, s.id AS submission_id, s.score, s.max_score, s.passed
    FROM fixture_quizzes q LEFT JOIN fixture_submissions s ON s.quiz_id = q.id AND s.user_id = ?
    WHERE q.session_id = ? ORDER BY q.created_at DESC, q.id DESC LIMIT ? OFFSET ?`)
    .all(userId, sessionId, size, page * size);
  const quizzes = rows.map(row => ({ quizId: row.id, title: row.title, quizType: row.quiz_type,
    page: row.page_number, coverageStartPage: row.coverage_start_page, coverageEndPage: row.coverage_end_page,
    questionCount: JSON.parse(row.public_question_json).questions.length, submitted: row.submission_id !== null,
    score: row.score, maxScore: row.max_score, passed: row.passed === null ? null : Boolean(row.passed),
    createdAt: row.created_at,
  }));
  return { evidence: 'LOCAL_MOCK_ONLY', status: 200, body: { success: true,
    data: { quizzes, page, size, totalElements, totalPages: Math.ceil(totalElements / size),
      hasNext: (page + 1) * size < totalElements }, message: '요청이 성공했습니다.' } };
}

function acceptanceResponses(db, manifest) {
  const actor = alias => manifest.learners.find(row => row.alias === alias).userId;
  const session = alias => manifest.resources.find(row => row.alias === alias).sessionId;
  return {
    A1_page0: mockList(db, actor('A'), session('A1')),
    A1_page1: mockList(db, actor('A'), session('A1'), 1, 100),
    A1_page2: mockList(db, actor('A'), session('A1'), 2, 100),
    A2_sentinel: mockList(db, actor('A'), session('A2')),
    B1_sentinel: mockList(db, actor('B'), session('B1')),
    A_reads_B1: mockList(db, actor('A'), session('B1')),
    B_reads_A1: mockList(db, actor('B'), session('A1')),
    invalid_page: mockList(db, actor('A'), session('A1'), -1),
    invalid_size0: mockList(db, actor('A'), session('A1'), 0, 0),
    invalid_size101: mockList(db, actor('A'), session('A1'), 0, 101),
  };
}

export function parseArgs(args) {
  let mode = 'dry-run';
  let includeMockResponses = false;
  let help = false;
  let modeSeen = false;
  for (let index = 0; index < args.length; index++) {
    const arg = args[index];
    if (arg === '--help') help = true;
    else if (arg === '--include-mock-responses') includeMockResponses = true;
    else if (arg === '--mode' && !modeSeen) {
      modeSeen = true;
      mode = args[++index];
      requireCondition(['dry-run', 'local-rehearsal'].includes(mode), 'ONLY_LOCAL_MODES_ALLOWED');
    } else throw new FixtureError('UNSUPPORTED_OPTION_NO_EXTERNAL_TARGETS_ALLOWED');
  }
  return { mode, includeMockResponses, help };
}

export function runLocal({ mode = 'dry-run', includeMockResponses = false } = {}) {
  requireCondition(['dry-run', 'local-rehearsal'].includes(mode), 'ONLY_LOCAL_MODES_ALLOWED');
  const db = openLocalDb();
  try {
    const beforeHash = fingerprint(snapshot(db));
    const { manifest, mockResponses } = seedFixture(db, { dryRun: mode === 'dry-run' });
    const cleanup = mode === 'local-rehearsal' ? cleanupFixture(db, manifest) : null;
    const pageChecks = Object.fromEntries(Object.entries(mockResponses).map(([name, response]) => [name, {
      evidence: response.evidence, status: response.status, code: response.body.error?.code,
      count: response.body.data?.quizzes.length, page: response.body.data?.page,
      size: response.body.data?.size, totalElements: response.body.data?.totalElements,
      totalPages: response.body.data?.totalPages, hasNext: response.body.data?.hasNext,
    }]));
    return { mode, environment: LOCAL_ENVIRONMENT, devExecuted: false, networkCalls: 0,
      seedRolledBack: mode === 'dry-run' && fingerprint(snapshot(db)) === beforeHash,
      manifest, pageChecks, cleanupRehearsal: cleanup,
      ...(includeMockResponses ? { mockResponses } : {}),
    };
  } finally {
    db.close();
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const options = parseArgs(process.argv.slice(2));
    if (options.help) console.log('node fixture.mjs [--mode dry-run|local-rehearsal] [--include-mock-responses]\n'
      + 'Default: dry-run. SQLite :memory: only. No DEV apply, URLs, files, tokens, or connection options.');
    else console.log(JSON.stringify(runLocal(options), null, 2));
  } catch (error) {
    // Print only an allowlisted local error code, never connection inputs or stack/environment values.
    console.error(JSON.stringify({ error: error instanceof FixtureError ? error.code : 'LOCAL_REHEARSAL_FAILED',
      devExecuted: false }));
    process.exitCode = 1;
  }
}
