/** Non-loginable synthetic assets for a freshly created, owned local schema only. */
import { localSession, localSchemaSource, ensure } from './local-mysql.mjs';

export async function prepareLocalAssets(owner, database) {
  const session = await localSession(owner, database);
  const timestamp = '2026-10-04 00:00:00.000000';
  const input = {format:'note04.mysql.v1',environment:owner.environment,schemaSourceCommit:localSchemaSource(owner,database).sourceCommit,runLabel:'NOTE04-DEV-20261004-R1',manifestId:'note04-dev-20261004-r1-plan',
    approvalReference:'LOCAL-SYNTHETIC-REHEARSAL',createdAtUtc:timestamp,learners:{},sessions:{}};
  try {
    const [existing] = await session.rows("SELECT JSON_OBJECT('users',(SELECT COUNT(*) FROM users),'materials',(SELECT COUNT(*) FROM learning_materials),'sessions',(SELECT COUNT(*) FROM learning_sessions),'quizzes',(SELECT COUNT(*) FROM quizzes),'runs',(SELECT COUNT(*) FROM qa_fixture_runs))");
    ensure(Object.values(existing).every(value => value === 0), 'LOCAL_SYNTHETIC_SCHEMA_NOT_EMPTY');
    await session.sql('START TRANSACTION;');
    let controlId;
    for (const alias of ['A','B','CONTROL']) {
      await session.sql("INSERT INTO users (email,password_hash,name,role,status,created_at,updated_at) VALUES (?,'LOCAL_NON_LOGINABLE',?,'LEARNER','ACTIVE',?,?)", [`${alias.toLowerCase()}@note04.invalid`,`합성 ${alias}`,timestamp,timestamp]);
      const [row] = await session.rows("SELECT JSON_OBJECT('id',CAST(LAST_INSERT_ID() AS CHAR))");
      if (alias !== 'CONTROL') input.learners[alias] = {userId:row.id}; else controlId = row.id;
    }
    let controlSessionId;
    for (const alias of ['A1','A2','B1','CONTROL']) {
      const userId = alias === 'CONTROL' ? controlId : input.learners[alias[0]].userId;
      await session.sql("INSERT INTO learning_materials (owner_id,title,storage_key,page_count,processing_status,status,created_at,updated_at) VALUES (?,?,?,7,'READY','ACTIVE',?,?)", [userId,`합성 ${alias}`,`LOCAL_ONLY_${alias}`,timestamp,timestamp]);
      const [material] = await session.rows("SELECT JSON_OBJECT('id',CAST(LAST_INSERT_ID() AS CHAR))");
      await session.sql('INSERT INTO material_pages (material_id,page_number,text_content) VALUES (?,7,\'로컬 합성 페이지\')', [material.id]);
      await session.sql("INSERT INTO learning_sessions (user_id,material_id,current_page,page_status,status,created_at,updated_at) VALUES (?,?,7,'NOT_EXPLAINED','ACTIVE',?,?)", [userId,material.id,timestamp,timestamp]);
      const [learnerSession] = await session.rows("SELECT JSON_OBJECT('id',CAST(LAST_INSERT_ID() AS CHAR))");
      if (alias !== 'CONTROL') input.sessions[alias] = {userId,materialId:material.id,sessionId:learnerSession.id,expectedVersion:'0',expectedMaterialUpdatedAtUtc:timestamp};
      else controlSessionId = learnerSession.id;
    }
    await session.sql("INSERT INTO quizzes (session_id,page_number,title,coverage_start_page,coverage_end_page,quiz_type,public_question_json,private_answer_json,schema_version,created_at) VALUES (?,7,'OUTSIDE_SCOPE',1,7,'OX','{\"schemaVersion\":\"1.0\",\"questions\":[]}','{\"schemaVersion\":\"1.0\",\"questions\":[]}','1.0',?)", [controlSessionId,timestamp]);
    await session.sql('COMMIT;');
    return input;
  } catch (error) { try { await session.sql('ROLLBACK;'); } catch { /* Closing also rolls back. */ } throw error; }
  finally { await session.close(); }
}
