import test from 'node:test';
import assert from 'node:assert/strict';
import { prepareLocalAssets } from './local-assets.mjs';
import { parseArguments } from './mysql-rehearsal.mjs';
import { startLocalMysql, stopLocalMysql, createLocalSchema, localSession, killLocalConnection } from './local-mysql.mjs';
import { validateInput, seedMysql, cleanupMysql, sha256, LOCAL_COMMIT, LOCAL_DELETE } from './mysql-adapter.mjs';

const commit = {mode:'local-rehearsal',localApproval:LOCAL_COMMIT};
const remove = {mode:'local-rehearsal',localApproval:LOCAL_DELETE};
const timestamp = '2026-10-04 00:00:00.000000';
const expected = code => error => error.code === code;
const schemaCache = new WeakMap();

async function query(owner, database, sql, values = []) {
  const session = await localSession(owner, database);
  try { return await session.rows(sql, values); } finally { await session.close(); }
}
async function mutate(owner, database, sql, values = []) {
  const session = await localSession(owner, database);
  try { await session.sql(sql, values); } finally { await session.close(); }
}
async function state(owner, database) {
  const [row] = await query(owner, database, "SELECT JSON_OBJECT('quizzes',(SELECT COUNT(*) FROM quizzes),'runs',(SELECT COUNT(*) FROM qa_fixture_runs),'resources',(SELECT COUNT(*) FROM qa_fixture_run_resources),'building',(SELECT COUNT(*) FROM qa_fixture_runs WHERE state='BUILDING'))");
  return row;
}
async function scenario(owner) {
  if (!schemaCache.has(owner)) schemaCache.set(owner,createLocalSchema(owner));
  const database = await schemaCache.get(owner);
  const session = await localSession(owner, database);
  try {
    // Reset only test-owned rows between serial scenarios; migrate once per private server.
    await session.sql('DROP TABLE IF EXISTS local_extra_quiz_dependent;');
    for (const table of ['qa_fixture_run_resources','qa_fixture_runs','repair_results','diagnoses','quiz_assessments','quiz_submissions','notes','qa_messages','qa_threads','chat_messages','session_page_records','quizzes','learning_sessions','material_pages','learning_materials','users']) await session.sql(`DELETE FROM ${table};`);
  } finally { await session.close(); }
  return {database,input:await prepareLocalAssets(owner,database)};
}
async function protectedFullHashes(owner, database) {
  // Harness-only full row hashes: actual field values, including synthetic password, never leave MySQL.
  const tables = (await query(owner,database,"SELECT JSON_OBJECT('name',TABLE_NAME) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='BASE TABLE' AND TABLE_NAME NOT IN ('quizzes','qa_fixture_runs','qa_fixture_run_resources') ORDER BY TABLE_NAME")).map(row=>row.name);
  const result = {};
  for (const table of tables) {
    const columns = await query(owner,database,"SELECT JSON_OBJECT('name',COLUMN_NAME) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? ORDER BY ORDINAL_POSITION",[table]);
    const argumentsSql = columns.map(column => `'${column.name}',\`${column.name}\``).join(',');
    const digest=`SHA2(CAST(JSON_OBJECT(${argumentsSql}) AS CHAR),256)`;
    result[table] = await query(owner,database,`SELECT JSON_OBJECT('hash',${digest}) FROM ${table} ORDER BY ${digest}`);
  }
  result.outside = await query(owner,database,"SELECT JSON_OBJECT('id',CAST(id AS CHAR),'hash',SHA2(CAST(JSON_OBJECT('id',id,'session',session_id,'title',title,'public',public_question_json,'private',private_answer_json,'created',created_at,'page',page_number,'start',coverage_start_page,'end',coverage_end_page,'type',quiz_type,'schema',schema_version) AS CHAR),256)) FROM quizzes WHERE title='OUTSIDE_SCOPE'");
  return result;
}

test('actual BE schema on owned MySQL; no Docker/TCP/existing service', async t => {
  const owner = await startLocalMysql();
  t.after(async () => { await stopLocalMysql(owner); });

  await t.test('53 real migrations, MySQL 8.0, InnoDB, no TCP', async () => {
    const {database} = await scenario(owner);
    const [identity] = await query(owner,database,"SELECT JSON_OBJECT('version',VERSION(),'noTcp',@@skip_networking,'engine',@@default_storage_engine,'zone',@@session.time_zone)");
    assert.match(identity.version,/^8\.0\./); assert.equal(identity.noTcp,1); assert.equal(identity.engine,'InnoDB'); assert.equal(identity.zone,'+00:00');
    const [column] = await query(owner,database,"SELECT JSON_OBJECT('precision',DATETIME_PRECISION,'type',DATA_TYPE) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='quizzes' AND COLUMN_NAME='created_at'");
    assert.deepEqual(column,{precision:6,type:'datetime'});
  });
  await t.test('default dry-run rolls back quizzes and sealed ledger; existing rows unchanged', async () => {
    const {database,input} = await scenario(owner); const before = await protectedFullHashes(owner,database);
    const result = await seedMysql(owner,database,input);
    assert.equal(result.action,'WOULD_CREATE'); assert.equal(result.committed,false); assert.equal(result.manifest.quizzes.length,103);
    assert.deepEqual(await state(owner,database),{quizzes:1,runs:0,resources:0,building:0});
    assert.deepEqual(await protectedFullHashes(owner,database),before);
  });
  await t.test('commit103/ledger111;100→1→0; repeated page1;same DATETIME6; public OX JSON', async () => {
    const {database,input} = await scenario(owner); const before = await protectedFullHashes(owner,database);
    const result = await seedMysql(owner,database,input,commit);
    assert.equal(result.action,'CREATED'); assert.deepEqual(await state(owner,database),{quizzes:104,runs:1,resources:111,building:0});
    const list = async offset => query(owner,database,"SELECT JSON_OBJECT('id',CAST(id AS CHAR),'page',page_number,'time',DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s.%f'),'public',public_question_json) FROM quizzes WHERE session_id=? ORDER BY created_at DESC,id DESC LIMIT 100 OFFSET ?",[input.sessions.A1.sessionId,offset]);
    const page0 = await list(0); const page1 = await list(100); const page2 = await list(200);
    assert.equal(page0.length,100); assert.equal(page1.length,1); assert.equal(page2.length,0); assert.deepEqual(await list(100),page1);
    const all = [...page0,...page1]; assert.equal(new Set(all.map(row => row.id)).size,101);
    for(let i=1;i<all.length;i++) assert.ok(BigInt(all[i-1].id)>BigInt(all[i].id));
    assert.ok(all.every(row => row.time === timestamp && row.page===7 && row.public.schemaVersion==='1.0' && row.public.questions.length===1 && !('choices' in row.public.questions[0])));
    assert.ok(!JSON.stringify(result).includes('answerValue')); assert.ok(!JSON.stringify(result).includes('private_answer_json'));
    assert.deepEqual(await protectedFullHashes(owner,database),before);
  });
  await t.test('same run is idempotent and altered approved scope rejected', async () => {
    const {database,input} = await scenario(owner); const first=await seedMysql(owner,database,input,commit);
    const second=await seedMysql(owner,database,input,commit); assert.equal(second.action,'EXISTING'); assert.deepEqual(second.manifest,first.manifest);
    await assert.rejects(seedMysql(owner,database,{...input,createdAtUtc:'2026-10-04 00:00:01.000000'},commit),expected('RUN_SCOPE_OR_LEDGER_MISMATCH'));
    assert.deepEqual(await state(owner,database),{quizzes:104,runs:1,resources:111,building:0});
  });
  await t.test('manifest ID reused by another run cannot insert', async () => {
    const {database,input} = await scenario(owner); await seedMysql(owner,database,input,commit);
    await assert.rejects(seedMysql(owner,database,{...input,runLabel:'NOTE04-LOCAL-OTHER'},commit),expected('MANIFEST_ID_ALREADY_USED'));
    assert.equal((await state(owner,database)).quizzes,104);
  });
  for(const fault of ['after-ledger','after-lock','after-50-quizzes','after-seal']) await t.test(`${fault}: data+run+resource rows roll back together`,async()=>{
    const {database,input}=await scenario(owner);
    await assert.rejects(seedMysql(owner,database,input,{...commit,fault}),expected('INJECTED_LOCAL_FAILURE'));
    assert.deepEqual(await state(owner,database),{quizzes:1,runs:0,resources:0,building:0});
  });
  await t.test('two real connections serialize identical run and return one set of IDs',async()=>{
    const {database,input}=await scenario(owner);
    let unblock; const held=new Promise(resolve=>{unblock=resolve;}); let ready; const locked=new Promise(resolve=>{ready=resolve;});
    const firstPromise=seedMysql(owner,database,input,{...commit,onPhase:async name=>{if(name==='after-lock'){ready();await held;}}});
    await Promise.race([locked,firstPromise]); let secondFinished=false;
    const secondPromise=seedMysql(owner,database,input,commit).finally(()=>{secondFinished=true;});
    await new Promise(resolve=>setTimeout(resolve,100)); assert.equal(secondFinished,false); unblock();
    const [first,second]=await Promise.all([firstPromise,secondPromise]);
    assert.equal(first.action,'CREATED'); assert.equal(second.action,'EXISTING'); assert.deepEqual(second.manifest.quizzes,first.manifest.quizzes);
    assert.deepEqual(await state(owner,database),{quizzes:104,runs:1,resources:111,building:0});
  });
  await t.test('connection killed after seal before commit rolls back; new attempt can succeed',async()=>{
    const {database,input}=await scenario(owner);
    await assert.rejects(seedMysql(owner,database,input,{...commit,onPhase:async(name,session)=>{if(name==='after-seal')await killLocalConnection(session);}}),expected('LOCAL_SESSION_UNAVAILABLE'));
    assert.deepEqual(await state(owner,database),{quizzes:1,runs:0,resources:0,building:0});
    assert.equal((await seedMysql(owner,database,input,commit)).action,'CREATED');
  });
  await t.test('lost acknowledgement after commit recovers existing DB manifest, no reseed',async()=>{
    const {database,input}=await scenario(owner);
    await assert.rejects(seedMysql(owner,database,input,{...commit,fault:'after-commit'}),expected('INJECTED_LOCAL_FAILURE'));
    const result=await seedMysql(owner,database,input,commit); assert.equal(result.action,'EXISTING');
    assert.deepEqual(await state(owner,database),{quizzes:104,runs:1,resources:111,building:0});
  });
  for(const [name,sql,field] of [
    ['wrong owner','UPDATE learning_materials SET owner_id=? WHERE id=?','owner'],
    ['non READY','UPDATE learning_materials SET processing_status=\'PROCESSING\' WHERE id=?','material'],
    ['active turn','UPDATE learning_sessions SET active_turn_request_id=\'LOCAL_BUSY\' WHERE id=?','session'],
    ['stale version','UPDATE learning_sessions SET version=1 WHERE id=?','session'],
  ]) await t.test(`${name} fails before103 inserts and ledger is rolled back`,async()=>{
    const {database,input}=await scenario(owner);
    const values=field==='owner'?[input.learners.B.userId,input.sessions.A1.materialId]:[input.sessions.A1[field==='material'?'materialId':'sessionId']];
    await mutate(owner,database,sql,values);
    await assert.rejects(seedMysql(owner,database,input,commit),error=>['MATERIAL_PRECONDITION_FAILED','SESSION_PRECONDITION_FAILED'].includes(error.code));
    assert.deepEqual(await state(owner,database),{quizzes:1,runs:0,resources:0,building:0});
  });
  await t.test('extra owned session blocks inventory before reading its quizzes',async()=>{
    const {database,input}=await scenario(owner);
    await mutate(owner,database,"INSERT INTO learning_sessions (user_id,material_id) VALUES (?,?)",[input.learners.A.userId,input.sessions.A1.materialId]);
    await assert.rejects(seedMysql(owner,database,input,commit),expected('SESSION_INVENTORY_MISMATCH'));
    assert.deepEqual(await state(owner,database),{quizzes:1,runs:0,resources:0,building:0});
  });
  await t.test('session descendants block generation',async()=>{
    const {database,input}=await scenario(owner);
    await mutate(owner,database,"INSERT INTO session_page_records (session_id,page_number,explained_at) VALUES (?,7,?)",[input.sessions.A1.sessionId,timestamp]);
    await assert.rejects(seedMysql(owner,database,input,commit),expected('SESSION_DEPENDENTS_PRESENT'));
    assert.equal((await state(owner,database)).runs,0);
  });
  await t.test('an existing target quiz cannot be overwritten or counted as fixture',async()=>{
    const {database,input}=await scenario(owner);
    await mutate(owner,database,"INSERT INTO quizzes (session_id,page_number,title,coverage_start_page,coverage_end_page,quiz_type,public_question_json,private_answer_json,schema_version) VALUES (?,7,'PREEXISTING',1,7,'OX','{}','{}','1.0')",[input.sessions.A1.sessionId]);
    await assert.rejects(seedMysql(owner,database,input,commit),expected('QUIZZES_ALREADY_PRESENT'));
    assert.deepEqual(await state(owner,database),{quizzes:2,runs:0,resources:0,building:0});
  });
  await t.test('cleanup default dry-run and partial DELETE failure preserve103 and protected8',async()=>{
    const {database,input}=await scenario(owner); const first=await seedMysql(owner,database,input,commit); const before=await protectedFullHashes(owner,database);
    const result=await cleanupMysql(owner,database,input,sha256(first.manifest)); assert.equal(result.action,'WOULD_CLEAN'); assert.equal(result.deleted,103);
    await assert.rejects(cleanupMysql(owner,database,input,sha256(first.manifest),{...remove,fault:'after-50-quizzes'}),expected('INJECTED_LOCAL_FAILURE'));
    assert.deepEqual(await state(owner,database),{quizzes:104,runs:1,resources:111,building:0});
    assert.deepEqual(await protectedFullHashes(owner,database),before);
  });
  await t.test('cleanup rejects tampered manifest/hash/quiz rows or added submissions',async()=>{
    const {database,input}=await scenario(owner); const first=await seedMysql(owner,database,input,commit);
    await assert.rejects(cleanupMysql(owner,database,input,'0'.repeat(64),remove),expected('APPROVED_MANIFEST_HASH_MISMATCH'));
    const quiz=first.manifest.quizzes[0];
    await mutate(owner,database,'UPDATE quizzes SET title=\'LOCAL_TAMPER\' WHERE id=?',[quiz.id]);
    await assert.rejects(cleanupMysql(owner,database,input,sha256(first.manifest),remove),expected('QUIZ_ROW_HASH_MISMATCH'));
    const original=`${input.runLabel}:A1:001`; await mutate(owner,database,'UPDATE quizzes SET title=? WHERE id=?',[original,quiz.id]);
    await mutate(owner,database,"INSERT INTO quiz_submissions (quiz_id,user_id,request_id,submitted_answer_json,score,max_score,passed,grading_result_json) VALUES (?,?,'LOCAL_SUBMISSION','{}',0,10,FALSE,'{}')",[quiz.id,input.learners.A.userId]);
    await assert.rejects(cleanupMysql(owner,database,input,sha256(first.manifest),remove),expected('SUBMISSIONS_PRESENT'));
    assert.equal((await state(owner,database)).quizzes,104);
  });
  await t.test('local cleanup only103; retains111 resource IDs and CLEANED tombstone; never regenerates',async()=>{
    const {database,input}=await scenario(owner); const before=await protectedFullHashes(owner,database); const first=await seedMysql(owner,database,input,commit);
    const result=await cleanupMysql(owner,database,input,sha256(first.manifest),remove);
    assert.equal(result.action,'CLEANED'); assert.equal(result.deleted,103); assert.deepEqual(await state(owner,database),{quizzes:1,runs:1,resources:111,building:0});
    assert.deepEqual(await protectedFullHashes(owner,database),before);
    const replay=await seedMysql(owner,database,input,commit); assert.equal(replay.action,'EXISTING'); assert.equal(replay.manifest.state,'CLEANED');
    assert.equal((await cleanupMysql(owner,database,input,sha256(result.manifest),remove)).action,'ALREADY_CLEANED');
  });
  await t.test('new FK child invisible to seed role is still detected by setup metadata preflight',async()=>{
    const {database,input}=await scenario(owner);
    await mutate(owner,database,'CREATE TABLE local_extra_quiz_dependent (id BIGINT PRIMARY KEY,quiz_id BIGINT,FOREIGN KEY (quiz_id) REFERENCES quizzes(id)) ENGINE=InnoDB');
    await assert.rejects(seedMysql(owner,database,input,commit),expected('FK_GRAPH_REVIEW_REQUIRED'));
    assert.equal((await state(owner,database)).quizzes,1);
  });
  await t.test('missing ledger prevents seeding; no implicit DDL repair',async()=>{
    const {database,input}=await scenario(owner);
    await mutate(owner,database,'RENAME TABLE qa_fixture_runs TO local_missing_ledger;');
    try { await assert.rejects(seedMysql(owner,database,input,commit),expected('INNODB_SCHEMA_AND_LEDGER_REQUIRED')); }
    finally { await mutate(owner,database,'RENAME TABLE local_missing_ledger TO qa_fixture_runs;'); }
    assert.deepEqual(await state(owner,database),{quizzes:1,runs:0,resources:0,building:0});
  });
  await t.test('actual grants deny auth plaintext/asset DML/DDL; seed cannot delete, cleaner cannot seed',async()=>{
    const {database,input}=await scenario(owner);
    for(const [actor,sql] of [
      ['seed','SELECT email,password_hash FROM users;'],
      ['seed',"UPDATE learning_sessions SET current_page=1;"],
      ['seed','DELETE FROM quizzes;'],
      ['seed','CREATE TABLE local_forbidden (id INT);'],
      ['cleanup','DELETE FROM users;'],
      ['cleanup',"INSERT INTO quizzes (session_id,page_number,title,coverage_start_page,coverage_end_page,quiz_type,public_question_json,private_answer_json,schema_version) VALUES (1,7,'FORBIDDEN',1,7,'OX','{}','{}','1.0');"],
    ]) {
      const session=await localSession(owner,database,actor);
      try { await assert.rejects(session.sql(sql),error=>[1142,1143,1227].includes(error.mysqlCode)); }
      finally { await session.close(); }
    }
    assert.deepEqual(await state(owner,database),{quizzes:1,runs:0,resources:0,building:0});
    assert.equal(validateInput(input).learners.A.userId,input.learners.A.userId);
  });
  await t.test('CLI default rollback and credential/target/options rejected before server start',()=>{
    assert.deepEqual(parseArguments([]),{mode:'dry-run',cleanup:false,schema:'develop-v53'});
    for(const args of [['--host=localhost'],['--port=3306'],['--database=edupilot'],['--url=mysql://example'],['--token=LOCAL_FAKE'],['--apply'],['--mode=dev'],['--schema=dev'],['--mode=dry-run','--mode=dry-run'],['--mode=local-rehearsal'],['--cleanup-local-approval=LOCAL_SYNTHETIC_DELETE']]) assert.throws(()=>parseArguments(args));
    assert.equal(parseArguments(['--mode=local-rehearsal','--local-approval=LOCAL_SYNTHETIC_COMMIT','--cleanup-local-approval=LOCAL_SYNTHETIC_DELETE']).cleanup,true);
  });
  await t.test('missing/unsafe IDs, real targets and implicit commit/delete modes rejected',async()=>{
    const {database,input}=await scenario(owner);
    const nullId=structuredClone(input); nullId.learners.A.userId=null; assert.throws(()=>validateInput(nullId),expected('CANONICAL_BIGINT_ID_REQUIRED'));
    const unsafe=structuredClone(input); unsafe.learners.A.userId='9223372036854775808'; assert.throws(()=>validateInput(unsafe),expected('CANONICAL_BIGINT_ID_REQUIRED'));
    const numeric=structuredClone(input); numeric.sessions.A1.sessionId=1; assert.throws(()=>validateInput(numeric),expected('CANONICAL_BIGINT_ID_REQUIRED'));
    assert.throws(()=>validateInput({...input,createdAtUtc:'2026-02-30 00:00:00.000000'}),expected('UTC_DATETIME6_REQUIRED'));
    assert.throws(()=>validateInput({...input,environment:'DEV'}),expected('LOCAL_INPUT_REQUIRED'));
    await assert.rejects(seedMysql(owner,database,{...input,schemaSourceCommit:'e949fbecbe8f6cd414ee9f18605d1a15dca2fa6e'},commit),expected('SCHEMA_SCOPE_MISMATCH'));
    await assert.rejects(startLocalMysql({host:'localhost'}),expected('DATABASE_TARGET_OPTIONS_FORBIDDEN'));
    await assert.rejects(seedMysql({},database,input,commit),expected('OWNED_LOCAL_MYSQL_REQUIRED'));
    await assert.rejects(seedMysql(owner,'edupilot',input,commit),expected('OWNED_SCHEMA_REQUIRED'));
    await assert.rejects(seedMysql(owner,database,input,{mode:'apply'}),expected('LOCAL_MODE_REQUIRED'));
    await assert.rejects(seedMysql(owner,database,input,{mode:'local-rehearsal'}),expected('EXPLICIT_LOCAL_APPROVAL_REQUIRED'));
    await assert.rejects(seedMysql(owner,database,input,{host:'127.0.0.1'}),expected('ADAPTER_OPTIONS_INVALID'));
    await assert.rejects(cleanupMysql(owner,database,input,'0'.repeat(64),{mode:'local-rehearsal',localApproval:LOCAL_COMMIT}),expected('EXPLICIT_LOCAL_APPROVAL_REQUIRED'));
    assert.deepEqual(await state(owner,database),{quizzes:1,runs:0,resources:0,building:0});
  });
  await t.test('pinned current PR495 V1..V59: actual schema,103+ledger,cleanup; no invented auth/consent',async()=>{
    const database=await createLocalSchema(owner,{schema:'pr495-v59'});
    const input=await prepareLocalAssets(owner,database);
    const before=await protectedFullHashes(owner,database);
    const accounts=await query(owner,database,"SELECT JSON_OBJECT('cohort',access_cohort,'emailState',email_verification_state,'ageState',age_verification_state,'dobMissing',date_of_birth IS NULL,'verifiedMissing',email_verified_at IS NULL) FROM users WHERE id IN (?,?) ORDER BY id",[input.learners.A.userId,input.learners.B.userId]);
    assert.ok(accounts.every(row=>row.cohort==='NEW_SIGNUP'&&row.emailState==='UNKNOWN'&&row.ageState==='UNKNOWN'&&row.dobMissing===true&&row.verifiedMissing===true));
    const first=await seedMysql(owner,database,input,commit);
    assert.equal(first.manifest.schemaSource.sourceCommit,'e949fbecbe8f6cd414ee9f18605d1a15dca2fa6e');
    assert.equal(first.manifest.schemaSource.migrationCount,59);
    assert.deepEqual(await state(owner,database),{quizzes:104,runs:1,resources:111,building:0});
    const second=await seedMysql(owner,database,input,commit);assert.equal(second.action,'EXISTING');assert.deepEqual(second.manifest,first.manifest);
    const cleaned=await cleanupMysql(owner,database,input,sha256(first.manifest),remove);assert.equal(cleaned.deleted,103);
    assert.deepEqual(await protectedFullHashes(owner,database),before);
    const [external]=await query(owner,database,"SELECT JSON_OBJECT('deliveries',(SELECT COUNT(*) FROM email_deliveries),'outbox',(SELECT COUNT(*) FROM email_outbox),'guardian',(SELECT COUNT(*) FROM guardian_web_requests),'consents',(SELECT COUNT(*) FROM policy_consents))");
    assert.deepEqual(external,{deliveries:0,outbox:0,guardian:0,consents:0});
  });
});
