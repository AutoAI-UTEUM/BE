/** Owned, disposable Windows MySQL process. No existing server/target is accepted. */
import { spawn } from 'node:child_process';
import { mkdtemp, readFile, realpath, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { basename, dirname, join, relative, resolve, sep } from 'node:path';
import { randomBytes } from 'node:crypto';
import { fileURLToPath } from 'node:url';

const capabilities = new WeakMap();
const defaultBin = 'C:\\Program Files\\MySQL\\MySQL Server 8.0\\bin';
export const DEVELOP_SCHEMA_COMMIT = 'ef8f0f74a3d2d46a0adc9aabf1d9dad9577dd938';
export const CANDIDATE_SCHEMA_COMMIT = 'e949fbecbe8f6cd414ee9f18605d1a15dca2fa6e';

export class LocalMysqlError extends Error {
  constructor(code) { super(code); this.code = code; }
}
export function ensure(condition, code) { if (!condition) throw new LocalMysqlError(code); }

function environment(root) {
  return {
    SystemRoot: process.env.SystemRoot, WINDIR: process.env.WINDIR,
    TEMP: root, TMP: root, MYSQL_TEST_LOGIN_FILE: join(root, 'absent-login-file'),
    MYSQL_HISTFILE: 'NUL',
  };
}
function child(binary, args, root) {
  return spawn(binary, args, { cwd: root, env: environment(root), windowsHide: true, stdio: 'pipe' });
}
async function waitForExit(processChild, timeout = 5000) {
  if (processChild.exitCode !== null || processChild.signalCode !== null) return;
  await new Promise(accept => {
    const timer = setTimeout(accept, timeout);
    processChild.once('close', () => { clearTimeout(timer); accept(); });
  });
}
async function finished(processChild, timeout = 45000) {
  return new Promise((accept, reject) => {
    const timer = setTimeout(() => { processChild.kill(); reject(new LocalMysqlError('LOCAL_PROCESS_TIMEOUT')); }, timeout);
    processChild.once('error', () => { clearTimeout(timer); reject(new LocalMysqlError('LOCAL_PROCESS_START_FAILED')); });
    processChild.once('close', code => { clearTimeout(timer); code === 0 ? accept() : reject(new LocalMysqlError('LOCAL_PROCESS_FAILED')); });
    processChild.stdout.resume(); processChild.stderr.resume();
  });
}

class SqlSession {
  constructor(owner, state, database, user) {
    this.owner = owner; this.state = state; this.closed = false; this.pending = null; this.stderr = '';
    const args = ['--no-defaults', '--protocol=MEMORY', `--shared-memory-base-name=${state.channel}`,
      `--user=${user}`, '--skip-password', '--batch', '--raw', '--skip-column-names', '--silent',
      '--unbuffered', '--skip-reconnect', '--local-infile=0', '--connect-timeout=3', '--default-character-set=utf8mb4'];
    if (database) args.push(`--database=${database}`);
    this.process = child(state.mysql, args, state.root);
    this.process.stdout.setEncoding('utf8'); this.process.stderr.setEncoding('utf8');
    let buffer = '';
    this.process.stdout.on('data', data => {
      buffer += data;
      while (buffer.includes('\n')) {
        const end = buffer.indexOf('\n'); const line = buffer.slice(0, end).replace(/\r$/, ''); buffer = buffer.slice(end + 1);
        if (!this.pending) continue;
        if (line === this.pending.marker) {
          const task = this.pending; this.pending = null; clearTimeout(task.timer); task.accept(task.lines);
        } else if (line) this.pending.lines.push(line);
      }
    });
    this.process.stderr.on('data', data => { this.stderr = (this.stderr + data).slice(-4096); });
    const fail = () => {
      this.closed = true;
      if (this.pending) {
        const task = this.pending; this.pending = null; clearTimeout(task.timer);
        const match = this.stderr.match(/ERROR (\d+)/);
        const error = new LocalMysqlError('LOCAL_SQL_FAILED'); error.mysqlCode = match ? Number(match[1]) : null;
        error.message = `LOCAL_SQL_FAILED (${error.mysqlCode ?? 'connection'})`;
        const denied = this.stderr.match(/(SELECT|UPDATE|INSERT|DELETE) command denied[^\n]*for table '([a-z_]+)'/);
        if (denied) error.message += ` ${denied[1]}:${denied[2]}`;
        task.reject(error); // Never propagate SQL, bound data, or server error text.
      }
    };
    this.process.on('error', fail); this.process.on('close', fail); this.process.stdin.on('error', fail);
  }
  async sql(statement, values = []) {
    requireOwner(this.owner);
    ensure(!this.closed && !this.pending, 'LOCAL_SESSION_UNAVAILABLE');
    const marker = `NOTE04_END_${randomBytes(16).toString('hex')}`;
    const literal = value => value === null ? 'NULL' : typeof value === 'number' && Number.isSafeInteger(value) ? String(value) :
      `CONVERT(X'${Buffer.from(String(value), 'utf8').toString('hex')}' USING utf8mb4)`;
    const bound = values.length ? values.map((value, i) => `SET @note04_p${i}=${literal(value)};`).join('\n') +
      `\nSET @note04_sql=${literal(statement)}; PREPARE note04_statement FROM @note04_sql;\n` +
      `EXECUTE note04_statement USING ${values.map((_, i) => `@note04_p${i}`).join(',')}; SET @note04_affected=ROW_COUNT(); DEALLOCATE PREPARE note04_statement;` : statement;
    return new Promise((accept, reject) => {
      const timer = setTimeout(() => { this.destroy(); reject(new LocalMysqlError('LOCAL_SQL_TIMEOUT')); }, 15000);
      this.pending = { marker, lines: [], timer, accept, reject };
      this.process.stdin.write(`${bound}\n;SELECT '${marker}';\n`);
    });
  }
  async rows(statement, values = []) {
    const lines = await this.sql(statement, values);
    try { return lines.map(line => JSON.parse(line)); }
    catch { throw new LocalMysqlError('LOCAL_SQL_RESULT_INVALID'); }
  }
  destroy() { if (!this.closed) { this.closed = true; this.process.kill(); } }
  async close() {
    if (!this.closed) {
      try { await this.sql('ROLLBACK;'); } catch { /* Disconnect also rolls back. */ }
      this.process.stdin.end(); this.closed = true;
    }
    await waitForExit(this.process);
  }
}

function requireOwner(owner) {
  const state = capabilities.get(owner);
  ensure(state && !state.stopped && state.server.exitCode === null && !state.server.killed, 'OWNED_LOCAL_MYSQL_REQUIRED');
  return state;
}
function normalizedPath(path) { return resolve(path).toLowerCase().replace(/[\\/]+$/, ''); }

export async function localSession(owner, database = null, actor = 'admin') {
  const state = requireOwner(owner);
  ensure(database === null || state.databases.has(database), 'OWNED_SCHEMA_REQUIRED');
  ensure(['admin','seed','cleanup'].includes(actor), 'LOCAL_ACTOR_INVALID');
  const user = actor === 'admin' ? 'root' : state.actors.get(database)?.[actor];
  ensure(user, 'LOCAL_ACTOR_NOT_PREPARED');
  const session = new SqlSession(owner, state, database, user);
  try {
    const [identity] = await session.rows("SELECT JSON_OBJECT('datadir',@@datadir,'channel',@@shared_memory_base_name,'networkDisabled',@@skip_networking,'version',VERSION(),'database',DATABASE(),'connectionId',CONNECTION_ID())");
    ensure(normalizedPath(identity.datadir) === normalizedPath(state.datadir) && identity.channel === state.channel &&
      identity.networkDisabled === 1 && /^8\.0\.(\d+)/.test(identity.version) && Number(identity.version.match(/^8\.0\.(\d+)/)[1]) >= 22 && identity.database === database, 'LOCAL_TARGET_IDENTITY_MISMATCH');
    session.connectionId = identity.connectionId;
    await session.sql("SET SESSION time_zone='+00:00'; SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ; SET SESSION innodb_lock_wait_timeout=5; SET SESSION wait_timeout=10; SET SESSION sql_mode='STRICT_TRANS_TABLES,NO_ZERO_DATE,NO_ZERO_IN_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION';");
    return session;
  } catch (error) { session.destroy(); throw error; }
}

/** Kills one identified connection on our private process; never an existing server/thread. */
export async function killLocalConnection(session) {
  requireOwner(session.owner);
  ensure(Number.isSafeInteger(session.connectionId) && session.connectionId > 0, 'OWNED_CONNECTION_ID_REQUIRED');
  const admin = await localSession(session.owner);
  try { await admin.sql(`KILL CONNECTION ${session.connectionId};`); }
  finally { await admin.close(); }
  session.destroy();
}

export async function startLocalMysql(options = {}) {
  ensure(process.platform === 'win32', 'WINDOWS_LOCAL_MYSQL_REQUIRED');
  ensure(Object.keys(options).every(key => key === 'binDir'), 'DATABASE_TARGET_OPTIONS_FORBIDDEN');
  const binDir = await realpath(options.binDir ?? defaultBin);
  const mysql = join(binDir, 'mysql.exe'); const mysqld = join(binDir, 'mysqld.exe');
  await realpath(mysql); await realpath(mysqld);
  const root = await mkdtemp(join(tmpdir(), 'note04-mysql-')); const datadir = join(root, 'data');
  const channel = `NOTE04_LOCAL_${randomBytes(16).toString('hex')}`;
  const flags = ['--no-defaults', `--basedir=${dirname(binDir)}`, `--datadir=${datadir}`, '--mysqlx=OFF',
    '--skip-networking', '--shared-memory', `--shared-memory-base-name=${channel}`, '--skip-named-pipe',
    '--performance-schema=OFF', '--innodb-buffer-pool-size=32M', '--innodb-log-buffer-size=8M',
    '--innodb-redo-log-capacity=32M', '--max-connections=8', '--table-open-cache=64', '--log-error-verbosity=1'];
  let server;
  try {
    await finished(child(mysqld, [...flags, '--initialize-insecure'], root));
    server = child(mysqld, [...flags, '--console'], root);
    server.stdout.resume(); server.stderr.resume(); server.on('error', () => {});
    const owner = Object.freeze({ environment: 'LOCAL_OWNED_MYSQL_SCHEMA' });
    const exitHook = () => { if (server.exitCode === null && !server.killed) server.kill(); };
    process.on('exit',exitHook);
    const state = { root, datadir, channel, mysql, server, stopped: false, databases: new Set(), actors: new Map(), schemas:new Map(), exitHook };
    capabilities.set(owner, state);
    const end = Date.now() + 20000;
    while (Date.now() < end) {
      try { const session = await localSession(owner); await session.close(); return owner; }
      catch { ensure(server.exitCode === null, 'LOCAL_SERVER_START_FAILED'); await new Promise(accept => setTimeout(accept, 100)); }
    }
    server.kill(); throw new LocalMysqlError('LOCAL_SERVER_START_TIMEOUT');
  } catch (error) {
    if (server && server.exitCode === null) { server.kill(); await waitForExit(server); }
    await removeOwnedDirectory(root); throw error;
  }
}

async function removeOwnedDirectory(root) {
  const temp = await realpath(tmpdir()); const target = resolve(root); const childPath = relative(temp, target);
  ensure(childPath && !childPath.startsWith(`..${sep}`) && !childPath.includes(sep) && basename(target).startsWith('note04-mysql-'), 'LOCAL_DIRECTORY_BOUNDARY_REQUIRED');
  await rm(target, { recursive: true, force: true });
}

export async function stopLocalMysql(owner) {
  const state = capabilities.get(owner);
  ensure(state && !state.stopped, 'OWNED_LOCAL_MYSQL_REQUIRED');
  try {
    if (state.server.exitCode === null && !state.server.killed) {
      const session = await localSession(owner);
      try { await session.sql('SHUTDOWN;'); } catch { /* Server closes the requesting client. */ }
      session.destroy(); await session.close();
    }
  } finally {
    if (state.server.exitCode === null) {
      await waitForExit(state.server);
      if (state.server.exitCode === null) { state.server.kill(); await waitForExit(state.server); }
    }
    process.removeListener('exit',state.exitHook);
    state.stopped = true;
    await removeOwnedDirectory(state.root);
  }
}

async function readPinnedGit(state, args) {
  const repository = fileURLToPath(new URL('../../../',import.meta.url));
  const processChild = spawn('git',['--no-optional-locks','--literal-pathspecs','-c',`safe.directory=${repository}`,'-C',repository,...args],
    {cwd:state.root,windowsHide:true,env:{...environment(state.root),PATH:process.env.PATH,GIT_CONFIG_NOSYSTEM:'1',GIT_CONFIG_GLOBAL:join(state.root,'absent-git-config'),GIT_NO_LAZY_FETCH:'1',GIT_TERMINAL_PROMPT:'0'},stdio:'pipe'});
  return new Promise((accept,reject)=>{
    processChild.stdout.setEncoding('utf8');
    let output=''; let failed=false;
    const timer=setTimeout(()=>{failed=true;processChild.kill();reject(new LocalMysqlError('PINNED_SCHEMA_READ_FAILED'));},10000);
    processChild.stdout.on('data',chunk=>{output+=chunk;if(output.length>1048576){failed=true;processChild.kill();reject(new LocalMysqlError('PINNED_SCHEMA_READ_FAILED'));}});
    processChild.stderr.resume();
    processChild.once('error',()=>{failed=true;clearTimeout(timer);reject(new LocalMysqlError('PINNED_SCHEMA_OBJECT_REQUIRED'));});
    processChild.once('close',code=>{clearTimeout(timer);if(!failed){code===0?accept(output):reject(new LocalMysqlError('PINNED_SCHEMA_OBJECT_REQUIRED'));}});
    processChild.stdin.end();
  });
}

export function localSchemaSource(owner,database) {
  const state=requireOwner(owner); const source=state.schemas.get(database);
  ensure(source&&state.actors.has(database),'OWNED_SCHEMA_REQUIRED');
  return source;
}

/** Real SQL unchanged: two pinned Git snapshots only; never the mutable checkout/network. */
export async function createLocalSchema(owner, options = {}) {
  const state = requireOwner(owner);
  ensure(Object.keys(options).every(key=>key==='schema'),'SCHEMA_SOURCE_OPTIONS_FORBIDDEN');
  const schema=options.schema??'develop-v53';
  ensure(['develop-v53','pr495-v59'].includes(schema),'PINNED_SCHEMA_REQUIRED');
  const expectedCount=schema==='develop-v53'?53:59;
  const sourceCommit=schema==='develop-v53'?DEVELOP_SCHEMA_COMMIT:CANDIDATE_SCHEMA_COMMIT;
  const sourcePath='main-service/src/main/resources/db/migration';
  const sha=(await readPinnedGit(state,['rev-parse',`${sourceCommit}^{commit}`])).trim();
  ensure(sha===sourceCommit,'PINNED_SCHEMA_REQUIRED');
  const names=(await readPinnedGit(state,['ls-tree','-r','--name-only',sourceCommit,'--',sourcePath])).trim().split('\n').map(path=>basename(path));
  ensure(names.every(name=>/^V\d+__[a-z0-9_]+\.sql$/.test(name)),'SCHEMA_SOURCE_REVIEW_REQUIRED');
  names.sort((a,b)=>Number(a.match(/^V(\d+)/)[1])-Number(b.match(/^V(\d+)/)[1]));
  ensure(names.length === expectedCount && names.every((name, i) => name.startsWith(`V${i + 1}__`)), 'SCHEMA_SOURCE_REVIEW_REQUIRED');
  const database = `note04_local_${randomBytes(8).toString('hex')}`;
  const admin = await localSession(owner);
  try { await admin.sql(`CREATE DATABASE ${database} CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;`); }
  finally { await admin.close(); }
  state.databases.add(database);
  const session = await localSession(owner, database);
  try {
    for (const name of names) await session.sql(await readPinnedGit(state,['show','--no-ext-diff','--no-textconv',`${sourceCommit}:${sourcePath}/${name}`]));
    await session.sql(await readFile(new URL('./local-ledger.sql', import.meta.url), 'utf8'));
    const actors = {seed:`note04_seed_${randomBytes(6).toString('hex')}`,cleanup:`note04_clean_${randomBytes(6).toString('hex')}`};
    const readColumns = {
      users:'id,role,status,suspended_at',
      learning_materials:'id,owner_id,status,processing_status,page_count,updated_at',
      material_pages:'material_id,page_number',
      learning_sessions:'id,user_id,material_id,status,current_page,page_status,version,active_quiz_id,active_turn_request_id,active_turn_started_at,pending_diagnosis_id',
      quizzes:'id,session_id,page_number,title,coverage_start_page,coverage_end_page,quiz_type,public_question_json,private_answer_json,schema_version,created_at',
      quiz_submissions:'quiz_id',
      chat_messages:'session_id',qa_threads:'session_id',session_page_records:'session_id',notes:'session_id',
      quiz_assessments:'session_id',diagnoses:'session_id',repair_results:'session_id',
    };
    for (const [actor,user] of Object.entries(actors)) {
      // Empty password is only for an unnetworked, newly initialized disposable process.
      await session.sql(`CREATE USER '${user}'@'localhost' IDENTIFIED BY '';`);
      for (const [table,columns] of Object.entries(readColumns)) await session.sql(`GRANT SELECT (${columns}) ON ${database}.${table} TO '${user}'@'localhost';`);
      for (const table of ['qa_fixture_runs','qa_fixture_run_resources']) await session.sql(`GRANT SELECT ON ${database}.${table} TO '${user}'@'localhost';`);
      await session.sql(`GRANT UPDATE (state,manifest_sha256,manifest_json) ON ${database}.qa_fixture_runs TO '${user}'@'localhost';`);
      if (actor === 'seed') {
        await session.sql(`GRANT INSERT ON ${database}.quizzes TO '${user}'@'localhost';`);
        await session.sql(`GRANT INSERT ON ${database}.qa_fixture_runs TO '${user}'@'localhost';`);
        await session.sql(`GRANT INSERT ON ${database}.qa_fixture_run_resources TO '${user}'@'localhost';`);
      } else await session.sql(`GRANT DELETE ON ${database}.quizzes TO '${user}'@'localhost';`);
    }
    state.actors.set(database,actors);
    state.schemas.set(database,Object.freeze({schema,sourceCommit,migrationCount:expectedCount}));
  } finally { await session.close(); }
  return database;
}
