/** Standalone CLI: creates its own DB process; cannot connect to any existing environment. */
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';
import { startLocalMysql, stopLocalMysql, createLocalSchema, ensure, LocalMysqlError, localSchemaSource } from './local-mysql.mjs';
import { prepareLocalAssets } from './local-assets.mjs';
import { seedMysql, cleanupMysql, sha256, LOCAL_COMMIT, LOCAL_DELETE } from './mysql-adapter.mjs';

export function parseArguments(args) {
  const result = {mode:'dry-run',cleanup:false,schema:'develop-v53'}; const seen = new Set();
  for (const arg of args) {
    ensure(typeof arg === 'string' && arg.startsWith('--'), 'CLI_ARGUMENT_REJECTED');
    const split = arg.indexOf('='); const key = split === -1 ? arg.slice(2) : arg.slice(2,split); const value = split === -1 ? null : arg.slice(split+1);
    ensure(['mode','local-approval','cleanup-local-approval','mysql-bin-dir','schema'].includes(key) && !seen.has(key) && value, 'CLI_ARGUMENT_REJECTED');
    seen.add(key);
    if (key === 'mode') { ensure(['dry-run','local-rehearsal'].includes(value), 'LOCAL_MODE_REQUIRED'); result.mode=value; }
    if (key === 'local-approval') { ensure(value===LOCAL_COMMIT, 'EXPLICIT_LOCAL_APPROVAL_REQUIRED'); result.localApproval=value; }
    if (key === 'cleanup-local-approval') { ensure(value===LOCAL_DELETE, 'EXPLICIT_LOCAL_APPROVAL_REQUIRED'); result.cleanup=true; }
    if (key === 'mysql-bin-dir') result.binDir=value;
    if (key === 'schema') {ensure(['develop-v53','pr495-v59'].includes(value),'PINNED_SCHEMA_REQUIRED');result.schema=value;}
  }
  if (result.mode === 'local-rehearsal') ensure(result.localApproval===LOCAL_COMMIT, 'EXPLICIT_LOCAL_APPROVAL_REQUIRED');
  ensure(!result.cleanup || result.mode==='local-rehearsal', 'CLEANUP_REQUIRES_LOCAL_REHEARSAL');
  return result;
}
export async function runMysqlRehearsal(args = []) {
  const options = parseArguments(args); // Reject targets/credentials before starting any process.
  const owner = await startLocalMysql(options.binDir ? {binDir:options.binDir} : {});
  try {
    const database = await createLocalSchema(owner,{schema:options.schema});
    const input = await prepareLocalAssets(owner,database);
    const created = await seedMysql(owner,database,input,{mode:options.mode,localApproval:options.localApproval});
    let cleaned = null;
    if (options.cleanup) cleaned = await cleanupMysql(owner,database,input,sha256(created.manifest),{mode:'local-rehearsal',localApproval:LOCAL_DELETE});
    return {environment:owner.environment,migrations:options.schema==='develop-v53'?'V1..V53':'V1..V59',
      schemaSource:localSchemaSource(owner,database),mode:options.mode,action:created.action,scopeHash:created.manifest.scopeHash,
      counts:created.manifest.counts,manifestHash:sha256(created.manifest),protectedAssets:created.manifest.protected.length,
      cleanup:cleaned ? {action:cleaned.action,deleted:cleaned.deleted,state:cleaned.manifest.state} : 'NOT_REQUESTED',
      liveEnvironment:'NOT_ACCESSED',persistentFixture:'NONE_AFTER_PROCESS_STOP'};
  } finally { await stopLocalMysql(owner); }
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { process.stdout.write(`${JSON.stringify(await runMysqlRehearsal(process.argv.slice(2)),null,2)}\n`); }
  catch (error) { process.stderr.write(`${error instanceof LocalMysqlError ? error.code : 'LOCAL_REHEARSAL_FAILED'}\n`); process.exitCode=1; }
}
