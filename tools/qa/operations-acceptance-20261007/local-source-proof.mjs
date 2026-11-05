// Read-only verification of replayable LOCAL test evidence. Never a CI attestation,
// live acceptance receipt, deployment permission, or replacement of historical proof.
import { createHash } from 'node:crypto';
import { lstatSync, readFileSync, readdirSync, realpathSync } from 'node:fs';
import { isAbsolute, join, relative, resolve, sep } from 'node:path';

export const SCOPE = 'composer-spring-local-synthetic;not-ci;not-live-acceptance';
export const FEATURE = 'main-service/src/main/java/io/edupilot/aiusage/AiFeature.java';
export const GENERATOR = 'tools/qa/operations-acceptance-20261007/local-source-proof.py';
const PREFIX = 'tools/qa/operations-acceptance-20261007/local-verification/';
export const REQUIRED_SUITES = [
  'io.edupilot.MainServiceApplicationTests',
  ...['ComposerAdmissionServiceTest', 'ComposerJpaTest', 'ComposerPredictionControllerTest',
    'ComposerPredictionServiceTest', 'ComposerSecurityJpaTest', 'ComposerSnapshotServiceTest',
    'HttpComposerAiClientTest'].map(name => `io.edupilot.composer.${name}`),
];
export const COMPATIBILITY_CASE = 'legacyFeatureValuesAndPredictionRoundTripThroughVarcharAndAdminJson()';
const BUILD_FILES = ['build.gradle', 'settings.gradle', 'gradlew', 'gradlew.bat',
  'gradle/wrapper/gradle-wrapper.jar', 'gradle/wrapper/gradle-wrapper.properties'].map(p => `main-service/${p}`);
const TEXT = new Set(['java', 'gradle', 'bat', 'properties', 'yml', 'yaml', 'xml', 'json', 'sql', 'txt', 'md']);
const hash = data => createHash('sha256').update(data).digest('hex');
const sha = x => typeof x === 'string' && /^[a-f0-9]{64}$/.test(x);
const integer = x => Number.isSafeInteger(x) && x >= 0;
const utc = x => typeof x === 'string' && /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ$/.test(x)
  && Number.isFinite(Date.parse(x)) && new Date(x).toISOString() === x.replace('Z', '.000Z');
const totals = rows => Object.fromEntries(['tests', 'passed', 'failures', 'errors', 'skipped']
  .map(key => [key, rows.reduce((sum, row) => sum + row[key], 0)]));

function safeFile(root, path) {
  if (isAbsolute(path) || path.split('/').some(x => !x || x === '.' || x === '..') || path.includes('\\')) throw Error('PATH');
  const target = resolve(root, path);
  const child = relative(realpathSync(root), realpathSync(target));
  if (!child || isAbsolute(child) || child === '..' || child.startsWith(`..${sep}`) || !lstatSync(target).isFile()) throw Error('PATH');
  return target;
}

export function sourceSnapshot(root) {
  const paths = [...BUILD_FILES];
  const walk = path => {
    for (const entry of readdirSync(join(root, path), { withFileTypes: true })) {
      if (entry.isSymbolicLink()) throw Error('SYMLINK');
      const name = `${path}/${entry.name}`;
      if (entry.isDirectory()) walk(name);
      else if (entry.isFile()) paths.push(name);
    }
  };
  walk('main-service/src/main'); walk('main-service/src/test');
  const files = paths.sort().map(path => {
    let data = readFileSync(safeFile(root, path));
    if (TEXT.has(path.split('.').at(-1)) || path.endsWith('/gradlew')) data = Buffer.from(data.toString('latin1').replaceAll('\r\n', '\n'), 'latin1');
    return { path, sha256: hash(data) };
  });
  return { files, count: files.length, sha256: hash(files.map(x => `${x.path}\0${x.sha256}\n`).join('')) };
}

/** Pure semantic checks. Digests supplied here are computed from bytes by verifyLocalBinding. */
export function validateProof({ binding: b, historical: h, receipt: r, summary: s, snapshot,
  receiptHash, summaryHash, generatorHash }) {
  const errors = [];
  const require = (ok, code) => { if (!ok) errors.push(code); };
  require(b?.kind === 'LOCAL_WORKTREE_TESTS' && b.scope === SCOPE && b.path === FEATURE, 'BINDING_KIND');
  require(b?.hashMode === 'sha256-normalized-lf', 'HASH_MODE');
  require(!['ciRunUrl', 'sourceSha', 'integrationSha'].some(key => Object.hasOwn(b ?? {}, key)), 'LOCAL_IS_NOT_CI');
  require(h?.path === FEATURE && b?.baselineSha256 === h.sha256, 'HISTORICAL_BASELINE');
  require(sha(b?.sha256) && b.sha256 === snapshot?.files?.find(x => x.path === FEATURE)?.sha256, 'CURRENT_SOURCE');
  require(sha(b?.receiptSha256) && b.receiptSha256 === receiptHash, 'RECEIPT_DIGEST');
  require(sha(b?.summarySha256) && b.summarySha256 === summaryHash, 'SUMMARY_DIGEST');
  require(r?.schemaVersion === 1 && r.kind === 'LOCAL_WORKTREE_TESTS' && r.scope === SCOPE, 'RECEIPT_KIND');
  require(r?.ciRunUrl === null && r.executionAuthorized === false && r.operatorAcceptance === false
    && r.realMysql === false && r.realProvider === false && r.deployedServices === false, 'NO_LIVE_OR_CI_CLAIM');
  require(r?.attestation === 'UNSIGNED_LOCAL_REPLAYABLE;not-CI-or-live-acceptance', 'LOCAL_ATTESTATION');
  require(r?.source?.kind === 'WORKTREE_SNAPSHOT' && /^[a-f0-9]{40}$/.test(r.source.baseCommit ?? '')
    && r.source.inputCount === snapshot?.count && r.source.inputTreeSha256 === snapshot?.sha256, 'INPUT_TREE');
  require(r?.binding?.path === b?.path && r?.binding?.baselineSha256 === b?.baselineSha256
    && r?.binding?.sha256 === b?.sha256, 'RECEIPT_BINDING');
  require(r?.generatorSha256 === generatorHash && sha(generatorHash), 'GENERATOR_DIGEST');
  require(r?.summarySha256 === summaryHash && r?.logSha256 && sha(r.logSha256), 'EXECUTION_DIGESTS');
  require(r?.execution?.profile === 'main-offline-full-build-v1' && r.execution.exitCode === 0
    && r.execution.javaMajor === 21 && r.execution.rerunTasks === true, 'ACTUAL_BUILD_PROFILE');
  require(utc(r?.execution?.startedAtUtc) && utc(r?.execution?.finishedAtUtc)
    && utc(r?.generatedAtUtc) && Date.parse(r.execution.startedAtUtc) < Date.parse(r.execution.finishedAtUtc)
    && Date.parse(r.execution.finishedAtUtc) <= Date.parse(r.generatedAtUtc), 'EXECUTION_WINDOW');
  require(s?.schemaVersion === 1 && s.kind === 'LOCAL_JUNIT_METADATA' && Array.isArray(s.suites) && s.suites.length > 0, 'JUNIT_SUMMARY');
  if (!Array.isArray(s?.suites)) return [...errors, 'NO_SUITES'];
  if (!s.suites.every(x => x && typeof x === 'object' && !Array.isArray(x))) return [...errors, 'INVALID_SUITE'];
  require(new Set(s.suites.map(x => x.name)).size === s.suites.length, 'DUPLICATE_SUITE');
  for (const suite of s.suites) {
    require(typeof suite.name === 'string' && /^[\w.$]+$/.test(suite.name) && sha(suite.rawJUnitSha256), 'JUNIT_PROVENANCE');
    require(['tests', 'passed', 'failures', 'errors', 'skipped'].every(k => integer(suite[k])), 'JUNIT_COUNTS');
    require(Array.isArray(suite.cases) && suite.cases.length === suite.tests, 'JUNIT_CASE_COUNT');
    if (!Array.isArray(suite.cases)) continue;
    require(suite.cases.every(x => x && typeof x.name === 'string' && x.name.length > 0
      && ['PASSED', 'FAILED', 'ERROR', 'SKIPPED'].includes(x.status)), 'JUNIT_CASE_STATUS');
    for (const [status, key] of [['PASSED', 'passed'], ['FAILED', 'failures'], ['ERROR', 'errors'], ['SKIPPED', 'skipped']]) {
      require(suite.cases.filter(x => x?.status === status).length === suite[key], 'JUNIT_STATUS_COUNT');
    }
  }
  const observed = totals(s.suites);
  require(observed.tests > 0 && observed.failures === 0 && observed.errors === 0, 'BUILD_HAS_FAILURES');
  require(Object.keys(observed).every(k => observed[k] === s.totals?.[k] && observed[k] === r?.totals?.[k]), 'TOTALS');
  for (const name of REQUIRED_SUITES) {
    const suite = s.suites.find(x => x.name === name);
    require(suite && suite.tests > 0 && suite.tests === suite.passed && suite.skipped === 0, 'REQUIRED_SUITE_NOT_PASSED');
  }
  require(s.suites.find(x => x.name === 'io.edupilot.composer.ComposerJpaTest')?.cases
    ?.some(x => x?.name === COMPATIBILITY_CASE && x.status === 'PASSED'), 'FEATURE_COMPATIBILITY_NOT_PASSED');
  return [...new Set(errors)];
}

export function verifyLocalBinding(root, binding, historical) {
  try {
    const read = path => {
      if (typeof path !== 'string' || !path.startsWith(PREFIX)
        || !/^[a-z0-9.-]+\.json$/.test(path.slice(PREFIX.length))) throw Error('PROOF_PATH');
      const bytes = readFileSync(safeFile(root, path));
      if (bytes.length > 2 * 1024 * 1024) throw Error('PROOF_SIZE');
      return { value: JSON.parse(bytes), digest: hash(Buffer.from(bytes.toString('latin1').replaceAll('\r\n', '\n'), 'latin1')) };
    };
    const receipt = read(binding.receiptPath), summary = read(binding.summaryPath);
    return validateProof({ binding, historical, receipt: receipt.value, summary: summary.value,
      snapshot: sourceSnapshot(root), receiptHash: receipt.digest, summaryHash: summary.digest,
      generatorHash: hash(Buffer.from(readFileSync(safeFile(root, GENERATOR)).toString('latin1').replaceAll('\r\n', '\n'), 'latin1')) });
  } catch { return ['LOCAL_PROOF_UNREADABLE']; }
}
