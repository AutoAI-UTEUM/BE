/** Actual BE V53 and pinned V59 schema adapter; only a process owned by local-mysql.mjs. */
import { ensure, localSession, localSchemaSource, LocalMysqlError, DEVELOP_SCHEMA_COMMIT, CANDIDATE_SCHEMA_COMMIT } from './local-mysql.mjs';
import { createHash } from 'node:crypto';

const aliases = ['A1', 'A2', 'B1'];
const counts = { A1: 101, A2: 1, B1: 1 };
const descendants = ['chat_messages','qa_threads','session_page_records','notes','quiz_assessments','diagnoses','repair_results'];
const faults = ['after-ledger', 'after-lock', 'after-50-quizzes', 'after-seal', 'after-commit'];
export const LOCAL_COMMIT = 'LOCAL_SYNTHETIC_COMMIT';
export const LOCAL_DELETE = 'LOCAL_SYNTHETIC_DELETE';

function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort().map(key => [key, canonical(value[key])]));
  return value;
}
export function sha256(value) { return createHash('sha256').update(JSON.stringify(canonical(value))).digest('hex'); }
function keys(value, allowed) {
  ensure(value && typeof value === 'object' && !Array.isArray(value) &&
    Object.keys(value).length === allowed.length && Object.keys(value).every(key => allowed.includes(key)), 'INPUT_CONTRACT_INVALID');
}
function id(value) {
  ensure(typeof value === 'string' && /^[1-9]\d{0,18}$/.test(value) && BigInt(value) <= 9223372036854775807n, 'CANONICAL_BIGINT_ID_REQUIRED');
  return value;
}
function timestamp(value) {
  ensure(typeof value === 'string' && /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{6}$/.test(value), 'UTC_DATETIME6_REQUIRED');
  const date = new Date(`${value.slice(0, 10)}T${value.slice(11, 23)}Z`);
  ensure(!Number.isNaN(date.getTime()) && date.toISOString().slice(0, 23).replace('T',' ') === value.slice(0, 23), 'UTC_DATETIME6_REQUIRED');
}
/** IDs/metadata are supplied, never discovered by email/title/most-recent search. */
export function validateInput(input) {
  keys(input, ['format','environment','schemaSourceCommit','runLabel','manifestId','approvalReference','createdAtUtc','learners','sessions']);
  ensure(input.format === 'note04.mysql.v1' && input.environment === 'LOCAL_OWNED_MYSQL_SCHEMA' &&
    input.approvalReference === 'LOCAL-SYNTHETIC-REHEARSAL', 'LOCAL_INPUT_REQUIRED');
  ensure([DEVELOP_SCHEMA_COMMIT,CANDIDATE_SCHEMA_COMMIT].includes(input.schemaSourceCommit),'PINNED_SCHEMA_REQUIRED');
  ensure(typeof input.runLabel === 'string' && /^NOTE04-[A-Z0-9-]{1,56}$/.test(input.runLabel) &&
    typeof input.manifestId === 'string' && /^note04-[a-z0-9-]{1,57}$/.test(input.manifestId), 'RUN_IDENTIFIERS_INVALID');
  timestamp(input.createdAtUtc); keys(input.learners, ['A','B']);
  for (const learner of Object.values(input.learners)) { keys(learner, ['userId']); id(learner.userId); }
  ensure(input.learners.A.userId !== input.learners.B.userId, 'DISTINCT_LEARNERS_REQUIRED');
  keys(input.sessions, aliases);
  for (const alias of aliases) {
    const item = input.sessions[alias];
    keys(item, ['userId','materialId','sessionId','expectedVersion','expectedMaterialUpdatedAtUtc']);
    id(item.userId); id(item.materialId); id(item.sessionId); timestamp(item.expectedMaterialUpdatedAtUtc);
    ensure(typeof item.expectedVersion === 'string' && /^(0|[1-9]\d{0,18})$/.test(item.expectedVersion) &&
      BigInt(item.expectedVersion) <= 9223372036854775807n, 'SESSION_VERSION_REQUIRED');
    ensure(item.userId === input.learners[alias[0]].userId, 'APPROVED_OWNERSHIP_INVALID');
  }
  for (const field of ['materialId','sessionId']) ensure(new Set(aliases.map(alias => input.sessions[alias][field])).size === 3, 'DISTINCT_ASSETS_REQUIRED');
  return JSON.parse(JSON.stringify(input));
}
function optionsFor(options, cleanup = false) {
  ensure(Object.keys(options).every(key => ['mode','localApproval','fault','onPhase'].includes(key)), 'ADAPTER_OPTIONS_INVALID');
  const mode = options.mode ?? 'dry-run';
  ensure(['dry-run','local-rehearsal'].includes(mode), 'LOCAL_MODE_REQUIRED');
  if (mode === 'local-rehearsal') ensure(options.localApproval === (cleanup ? LOCAL_DELETE : LOCAL_COMMIT), 'EXPLICIT_LOCAL_APPROVAL_REQUIRED');
  ensure(!options.fault || faults.includes(options.fault), 'FAULT_INVALID');
  ensure(!options.onPhase || typeof options.onPhase === 'function', 'PHASE_HOOK_INVALID');
  return mode;
}
async function phase(options, name, session) {
  if (options.onPhase) await options.onPhase(name, session);
  if (options.fault === name) throw new LocalMysqlError('INJECTED_LOCAL_FAILURE');
}
const placeholders = n => Array(n).fill('?').join(',');
const sorted = values => [...values].sort((a,b) => BigInt(a) < BigInt(b) ? -1 : BigInt(a) > BigInt(b) ? 1 : 0);
const quizJson = `JSON_OBJECT('id',CAST(q.id AS CHAR),'sessionId',CAST(q.session_id AS CHAR),
  'ownerId',CAST(s.user_id AS CHAR),'type',q.quiz_type,'page',q.page_number,'title',q.title,
  'coverageStart',q.coverage_start_page,'coverageEnd',q.coverage_end_page,'schemaVersion',q.schema_version,
  'createdAtUtc',DATE_FORMAT(q.created_at,'%Y-%m-%d %H:%i:%s.%f'),
  'publicHash',SHA2(CAST(q.public_question_json AS CHAR),256),'privateHash',SHA2(CAST(q.private_answer_json AS CHAR),256))`;

async function schemaReady(session, owner, database) {
  const [settings] = await session.rows("SELECT JSON_OBJECT('foreignKeys',@@foreign_key_checks,'isolation',@@transaction_isolation)");
  ensure(settings.foreignKeys === 1 && settings.isolation === 'REPEATABLE-READ', 'FK_AND_ISOLATION_REQUIRED');
  // Restricted users cannot see an ungranted new child table in information_schema.
  // Complete metadata preflight uses the setup owner's capability on our private server only.
  const inspector = await localSession(owner, database);
  try {
  const tables = ['users','learning_materials','material_pages','learning_sessions','quizzes','quiz_submissions',
    ...descendants,'qa_fixture_runs','qa_fixture_run_resources'];
  const rows = await inspector.rows(`SELECT JSON_OBJECT('table',TABLE_NAME,'engine',ENGINE) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN (${placeholders(tables.length)})`, tables);
  ensure(rows.length === tables.length && rows.every(row => row.engine === 'InnoDB'), 'INNODB_SCHEMA_AND_LEDGER_REQUIRED');
  const fks = await inspector.rows("SELECT JSON_OBJECT('table',TABLE_NAME,'column',COLUMN_NAME,'parent',REFERENCED_TABLE_NAME) FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE() AND REFERENCED_TABLE_NAME IN ('learning_sessions','quizzes')");
  const expected = [...descendants.map(table => `${table}:session_id:learning_sessions`), 'quizzes:session_id:learning_sessions','quiz_submissions:quiz_id:quizzes'].sort();
  ensure(JSON.stringify(fks.map(row => `${row.table}:${row.column}:${row.parent}`).sort()) === JSON.stringify(expected), 'FK_GRAPH_REVIEW_REQUIRED');
  } finally { await inspector.close(); }
}

async function protectedResources(session, input) {
  const userIds = sorted(Object.values(input.learners).map(learner => learner.userId));
  const users = await session.rows(`SELECT JSON_OBJECT('id',CAST(id AS CHAR),'role',role,'status',status,'suspended',suspended_at IS NOT NULL) FROM users WHERE id IN (${placeholders(2)}) ORDER BY id FOR SHARE`, userIds);
  ensure(users.length === 2 && users.every(row => row.role === 'LEARNER' && row.status === 'ACTIVE' && row.suspended === false), 'LEARNER_PRECONDITION_FAILED');
  const materialIds = sorted(aliases.map(alias => input.sessions[alias].materialId));
  const materials = await session.rows(`SELECT JSON_OBJECT('id',CAST(id AS CHAR),'ownerId',CAST(owner_id AS CHAR),'status',status,'processingStatus',processing_status,'pageCount',page_count,'updatedAtUtc',DATE_FORMAT(updated_at,'%Y-%m-%d %H:%i:%s.%f')) FROM learning_materials WHERE id IN (${placeholders(3)}) ORDER BY id FOR SHARE`, materialIds);
  const sessionIds = sorted(aliases.map(alias => input.sessions[alias].sessionId));
  const sessions = await session.rows(`SELECT JSON_OBJECT('id',CAST(id AS CHAR),'ownerId',CAST(user_id AS CHAR),'materialId',CAST(material_id AS CHAR),'status',status,'page',current_page,'pageStatus',page_status,'version',CAST(version AS CHAR),'activeQuiz',active_quiz_id IS NOT NULL,'activeTurn',active_turn_request_id IS NOT NULL OR active_turn_started_at IS NOT NULL,'pendingDiagnosis',pending_diagnosis_id IS NOT NULL) FROM learning_sessions WHERE id IN (${placeholders(3)}) ORDER BY id FOR SHARE`, sessionIds);
  ensure(materials.length === 3 && sessions.length === 3, 'ASSET_PREPARATION_BLOCKED');
  const inventory = await session.rows(`SELECT JSON_OBJECT('id',CAST(id AS CHAR),'ownerId',CAST(user_id AS CHAR)) FROM learning_sessions WHERE user_id IN (?,?) AND status <> 'DELETED' ORDER BY id FOR SHARE`, userIds);
  ensure(JSON.stringify(inventory.map(row => row.id)) === JSON.stringify(sessionIds), 'SESSION_INVENTORY_MISMATCH');
  const resources = users.map(row => ({kind:'USER',alias:row.id === input.learners.A.userId ? 'A':'B',id:row.id,ownerId:row.id,sessionId:null,disposition:'REUSED_PROTECTED',rowHash:sha256(row)}));
  for (const alias of aliases) {
    const item = input.sessions[alias]; const material = materials.find(row => row.id === item.materialId); const learnerSession = sessions.find(row => row.id === item.sessionId);
    ensure(material.ownerId === item.userId && material.status === 'ACTIVE' && material.processingStatus === 'READY' &&
      material.pageCount >= 7 && material.updatedAtUtc === item.expectedMaterialUpdatedAtUtc, 'MATERIAL_PRECONDITION_FAILED');
    ensure(learnerSession.ownerId === item.userId && learnerSession.materialId === item.materialId &&
      learnerSession.status === 'ACTIVE' && learnerSession.page === 7 && learnerSession.pageStatus === 'NOT_EXPLAINED' &&
      learnerSession.version === item.expectedVersion && !learnerSession.activeQuiz && !learnerSession.activeTurn && !learnerSession.pendingDiagnosis, 'SESSION_PRECONDITION_FAILED');
    const [page] = await session.rows('SELECT JSON_OBJECT(\'count\',COUNT(*)) FROM material_pages WHERE material_id=? AND page_number=7 FOR SHARE', [item.materialId]);
    ensure(page.count === 1, 'MATERIAL_PAGE_REQUIRED');
    resources.push({kind:'MATERIAL',alias,id:item.materialId,ownerId:item.userId,sessionId:null,disposition:'REUSED_PROTECTED',rowHash:sha256(material)});
    resources.push({kind:'SESSION',alias,id:item.sessionId,ownerId:item.userId,sessionId:item.sessionId,disposition:'REUSED_PROTECTED',rowHash:sha256(learnerSession)});
  }
  for (const table of descendants) {
    const [result] = await session.rows(`SELECT JSON_OBJECT('count',COUNT(*)) FROM ${table} WHERE session_id IN (${placeholders(3)}) FOR SHARE`, sessionIds);
    ensure(result.count === 0, 'SESSION_DEPENDENTS_PRESENT');
  }
  return resources;
}

async function quizRows(session, input) {
  return session.rows(`SELECT ${quizJson} FROM quizzes q JOIN learning_sessions s ON s.id=q.session_id WHERE q.session_id IN (?,?,?) ORDER BY q.id FOR SHARE`, sorted(aliases.map(alias => input.sessions[alias].sessionId)));
}
async function validateQuizzes(session, input, manifest, cleaned = false) {
  const rows = await quizRows(session, input);
  ensure(rows.length === (cleaned ? 0 : 103), 'QUIZ_ROW_COUNT_MISMATCH');
  if (cleaned) return;
  ensure(new Set(rows.map(row => row.id)).size === 103, 'QUIZ_ID_MEMBERSHIP_MISMATCH');
  for (const alias of aliases) ensure(rows.filter(row => row.sessionId === input.sessions[alias].sessionId).length === counts[alias], 'QUIZ_ROW_COUNT_MISMATCH');
  for (const row of rows) {
    const resource = manifest.quizzes.find(item => item.id === row.id);
    ensure(resource && resource.ownerId === row.ownerId && resource.sessionId === row.sessionId && resource.rowHash === sha256(row), 'QUIZ_ROW_HASH_MISMATCH');
    ensure(row.type === 'OX' && row.page === 7 && row.coverageStart === 1 && row.coverageEnd === 7 &&
      row.schemaVersion === '1.0' && row.createdAtUtc === input.createdAtUtc, 'QUIZ_CONTRACT_MISMATCH');
  }
  const [submissions] = await session.rows('SELECT JSON_OBJECT(\'count\',COUNT(*)) FROM quiz_submissions qs JOIN quizzes q ON q.id=qs.quiz_id WHERE q.session_id IN (?,?,?) FOR SHARE', aliases.map(alias => input.sessions[alias].sessionId));
  ensure(submissions.count === 0, 'SUBMISSIONS_PRESENT');
}
async function storeResources(session, input, resources) {
  for (const item of resources) await session.sql('INSERT INTO qa_fixture_run_resources (run_label,kind,alias,resource_id,owner_id,session_id,disposition,row_sha256) VALUES (?,?,?,?,?,?,?,?)',
    [input.runLabel,item.kind,item.alias,item.id,item.ownerId,item.sessionId,item.disposition,item.rowHash]);
}
async function seal(session, input, manifest) {
  await session.sql('UPDATE qa_fixture_runs SET state=?,manifest_sha256=?,manifest_json=? WHERE run_label=? AND approved_scope_sha256=?',
    [manifest.state,sha256(manifest),JSON.stringify(manifest),input.runLabel,sha256(input)]);
}
async function existing(session, input) {
  const rows = await session.rows("SELECT JSON_OBJECT('scopeHash',approved_scope_sha256,'state',state,'manifestHash',manifest_sha256,'manifest',manifest_json) FROM qa_fixture_runs WHERE run_label=? FOR UPDATE", [input.runLabel]);
  if (!rows.length) return null;
  const row = rows[0]; const manifest = row.manifest;
  ensure(row.scopeHash === sha256(input) && ['COMMITTED','CLEANED'].includes(row.state) && manifest && sha256(manifest) === row.manifestHash && manifest.state === row.state, 'RUN_SCOPE_OR_LEDGER_MISMATCH');
  ensure(manifest.schemaSource?.sourceCommit===input.schemaSourceCommit&&manifest.schemaSource.migrationCount===(input.schemaSourceCommit===DEVELOP_SCHEMA_COMMIT?53:59),'SCHEMA_SCOPE_MISMATCH');
  const current = await protectedResources(session, input);
  ensure(sha256(current) === sha256(manifest.protected), 'PROTECTED_ASSET_CHANGED');
  const ledgerResources = await session.rows("SELECT JSON_OBJECT('kind',kind,'alias',alias,'id',CAST(resource_id AS CHAR),'ownerId',CAST(owner_id AS CHAR),'sessionId',CAST(session_id AS CHAR),'disposition',disposition,'rowHash',row_sha256) FROM qa_fixture_run_resources WHERE run_label=? ORDER BY kind,resource_id", [input.runLabel]);
  const order = resources => [...resources].sort((a,b) => a.kind.localeCompare(b.kind) || (BigInt(a.id) < BigInt(b.id) ? -1 : BigInt(a.id) > BigInt(b.id) ? 1 : 0));
  ensure(sha256(ledgerResources) === sha256(order([...manifest.protected,...manifest.quizzes])), 'RUN_RESOURCE_MEMBERSHIP_MISMATCH');
  await validateQuizzes(session, input, manifest, row.state === 'CLEANED');
  return manifest;
}

export async function seedMysql(owner, database, inputValue, options = {}) {
  const input = validateInput(inputValue); const mode = optionsFor(options);
  const schemaSource=localSchemaSource(owner,database);
  ensure(input.schemaSourceCommit===schemaSource.sourceCommit,'SCHEMA_SCOPE_MISMATCH');
  const session = await localSession(owner, database, 'seed');
  try {
    await schemaReady(session,owner,database); await session.sql('START TRANSACTION;');
    try {
      await session.sql('INSERT INTO qa_fixture_runs (run_label,manifest_id,approved_scope_sha256,state) VALUES (?,?,?,\'BUILDING\')', [input.runLabel,input.manifestId,sha256(input)]);
    } catch (error) {
      if (error.mysqlCode !== 1062) throw error;
      // mysql batch exits on error; disconnect rolls back. Read the winner on a new connection.
      await session.close(); const reader = await localSession(owner, database, 'seed');
      try {
        await reader.sql('START TRANSACTION;'); const manifest = await existing(reader, input);
        ensure(manifest, 'MANIFEST_ID_ALREADY_USED'); await reader.sql('ROLLBACK;');
        return {environment:owner.environment,action:'EXISTING',committed:false,manifest};
      } finally { await reader.close(); }
    }
    await phase(options, 'after-ledger', session);
    const protectedItems = await protectedResources(session, input);
    ensure((await quizRows(session, input)).length === 0, 'QUIZZES_ALREADY_PRESENT');
    await phase(options, 'after-lock', session);
    const quizIds = [];
    for (const alias of aliases) for (let ordinal = 1; ordinal <= counts[alias]; ordinal++) {
      const questionId = `${alias}-q-${String(ordinal).padStart(3,'0')}`;
      const publicData = {schemaVersion:'1.0',questions:[{questionId,questionText:`합성 NOTE04 ${alias} OX 문항 ${ordinal}: 1 + 1 = 2이다.`,points:10}]};
      const privateData = {schemaVersion:'1.0',questions:[{questionId,answerChoiceId:null,answerValue:true,explanation:'로컬 합성 검증 문항',referenceAnswer:null,gradingCriteria:null,rubric:null,modelAnswer:null}]};
      await session.sql('INSERT INTO quizzes (session_id,page_number,title,coverage_start_page,coverage_end_page,quiz_type,public_question_json,private_answer_json,schema_version,created_at) VALUES (?,7,?,1,7,\'OX\',?,?,\'1.0\',?)',
        [input.sessions[alias].sessionId,`${input.runLabel}:${alias}:${String(ordinal).padStart(3,'0')}`,JSON.stringify(publicData),JSON.stringify(privateData),input.createdAtUtc]);
      const [generated] = await session.rows("SELECT JSON_OBJECT('id',CAST(LAST_INSERT_ID() AS CHAR))");
      quizIds.push({id:generated.id,alias:`${alias}-${String(ordinal).padStart(3,'0')}`});
      if (quizIds.length === 50) await phase(options, 'after-50-quizzes', session);
    }
    const rows = await quizRows(session, input);
    const quizzes = rows.map(row => ({kind:'QUIZ',alias:quizIds.find(item => item.id === row.id).alias,id:row.id,ownerId:row.ownerId,sessionId:row.sessionId,disposition:'CREATED',rowHash:sha256(row)}));
    const manifest = {format:'note04.mysql.manifest.v1',environment:owner.environment,schemaSource,runLabel:input.runLabel,manifestId:input.manifestId,scopeHash:sha256(input),state:'COMMITTED',createdAtUtc:input.createdAtUtc,
      counts:{learners:2,materials:3,sessions:3,quizzes:103,A1:101,A2:1,B1:1},protected:protectedItems,quizzes};
    await validateQuizzes(session, input, manifest);
    ensure(sha256(await protectedResources(session, input)) === sha256(protectedItems), 'PROTECTED_ASSET_CHANGED');
    await storeResources(session, input, [...protectedItems,...quizzes]); await seal(session, input, manifest);
    await phase(options, 'after-seal', session);
    await session.sql(mode === 'dry-run' ? 'ROLLBACK;' : 'COMMIT;');
    if (mode !== 'dry-run') await phase(options, 'after-commit', session);
    return {environment:owner.environment,action:mode === 'dry-run' ? 'WOULD_CREATE' : 'CREATED',committed:mode !== 'dry-run',manifest};
  } catch (error) { try { if (!session.closed) await session.sql('ROLLBACK;'); } catch { /* Disconnect is rollback. */ } throw error; }
  finally { await session.close(); }
}

/** Defaults to rollback. Deletes only the 103 CREATED manifest quiz IDs in the owned local DB. */
export async function cleanupMysql(owner, database, inputValue, approvedManifestHash, options = {}) {
  const input = validateInput(inputValue); const mode = optionsFor(options, true);
  ensure(input.schemaSourceCommit===localSchemaSource(owner,database).sourceCommit,'SCHEMA_SCOPE_MISMATCH');
  ensure(typeof approvedManifestHash === 'string' && /^[a-f0-9]{64}$/.test(approvedManifestHash), 'APPROVED_MANIFEST_HASH_REQUIRED');
  const session = await localSession(owner, database, 'cleanup');
  try {
    await schemaReady(session,owner,database); await session.sql('START TRANSACTION;');
    const manifest = await existing(session, input); ensure(manifest, 'RUN_NOT_FOUND');
    ensure(sha256(manifest) === approvedManifestHash, 'APPROVED_MANIFEST_HASH_MISMATCH');
    if (manifest.state === 'CLEANED') { await session.sql('ROLLBACK;'); return {action:'ALREADY_CLEANED',committed:false,manifest}; }
    ensure(manifest.quizzes.length === 103 && manifest.quizzes.every(item => item.kind === 'QUIZ' && item.disposition === 'CREATED'), 'CLEANUP_MEMBERSHIP_INVALID');
    let deleted = 0;
    for (const item of manifest.quizzes) {
      await session.sql('DELETE FROM quizzes WHERE id=? AND session_id=?', [item.id,item.sessionId]);
      const [affected] = await session.rows("SELECT JSON_OBJECT('count',@note04_affected)");
      ensure(affected.count === 1, 'CLEANUP_ROW_COUNT_MISMATCH');
      if (++deleted === 50) await phase(options, 'after-50-quizzes', session);
    }
    const cleaned = {...manifest,state:'CLEANED'};
    await validateQuizzes(session, input, cleaned, true);
    ensure(sha256(await protectedResources(session, input)) === sha256(manifest.protected), 'PROTECTED_ASSET_CHANGED');
    await seal(session, input, cleaned); await phase(options, 'after-seal', session);
    await session.sql(mode === 'dry-run' ? 'ROLLBACK;' : 'COMMIT;');
    return {environment:owner.environment,action:mode === 'dry-run' ? 'WOULD_CLEAN' : 'CLEANED',committed:mode !== 'dry-run',deleted,manifest:cleaned};
  } catch (error) { try { if (!session.closed) await session.sql('ROLLBACK;'); } catch { /* Disconnect is rollback. */ } throw error; }
  finally { await session.close(); }
}
