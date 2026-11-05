import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { SCOPE, FEATURE, REQUIRED_SUITES, COMPATIBILITY_CASE, validateProof, verifyLocalBinding } from './local-source-proof.mjs';

const sha = x => createHash('sha256').update(JSON.stringify(x)).digest('hex');
const sum = suites => Object.fromEntries(['tests', 'passed', 'failures', 'errors', 'skipped']
  .map(key => [key, suites.reduce((n, suite) => n + suite[key], 0)]));

// Fabricated metadata is confined to validator unit tests. It is never installed
// in evidence-map.json or presented as an executed build/CI/acceptance receipt.
function fixture() {
  const suites = REQUIRED_SUITES.map(name => ({ name, tests: 1, passed: 1, failures: 0, errors: 0, skipped: 0,
    rawJUnitSha256: 'f'.repeat(64), cases: [{ name: name.endsWith('.ComposerJpaTest') ? COMPATIBILITY_CASE : 'unitFixture()', status: 'PASSED' }] }));
  const f = {
    historical: { path: FEATURE, sha256: 'b'.repeat(64) },
    binding: { kind: 'LOCAL_WORKTREE_TESTS', scope: SCOPE, hashMode: 'sha256-normalized-lf', path: FEATURE,
      baselineSha256: 'b'.repeat(64), sha256: 'a'.repeat(64) },
    snapshot: { count: 5, sha256: 'd'.repeat(64), files: [{ path: FEATURE, sha256: 'a'.repeat(64) }] },
    generatorHash: 'e'.repeat(64),
    summary: { schemaVersion: 1, kind: 'LOCAL_JUNIT_METADATA', suites, totals: sum(suites) },
    receipt: { schemaVersion: 1, kind: 'LOCAL_WORKTREE_TESTS', scope: SCOPE,
      generatedAtUtc: '2026-11-04T10:03:00Z', ciRunUrl: null, executionAuthorized: false,
      operatorAcceptance: false, realMysql: false, realProvider: false, deployedServices: false,
      attestation: 'UNSIGNED_LOCAL_REPLAYABLE;not-CI-or-live-acceptance',
      source: { kind: 'WORKTREE_SNAPSHOT', baseCommit: 'c'.repeat(40), inputCount: 5, inputTreeSha256: 'd'.repeat(64) },
      binding: { path: FEATURE, baselineSha256: 'b'.repeat(64), sha256: 'a'.repeat(64) },
      generatorSha256: 'e'.repeat(64), logSha256: 'f'.repeat(64), totals: sum(suites),
      execution: { profile: 'main-offline-full-build-v1', javaMajor: 21, exitCode: 0, rerunTasks: true,
        startedAtUtc: '2026-11-04T10:00:00Z', finishedAtUtc: '2026-11-04T10:02:00Z' } },
  };
  seal(f); return f;
}
function seal(f) {
  f.summaryHash = sha(f.summary); f.receipt.summarySha256 = f.summaryHash;
  f.binding.summarySha256 = f.summaryHash;
  f.receiptHash = sha(f.receipt); f.binding.receiptSha256 = f.receiptHash;
}
function counts(f) { f.summary.totals = sum(f.summary.suites); f.receipt.totals = { ...f.summary.totals }; }

test('valid local fixture is recognized only as unsigned local synthetic verification', () => {
  const f = fixture(); assert.deepEqual(validateProof(f), []);
  assert.equal(f.receipt.executionAuthorized, false); assert.equal(f.receipt.operatorAcceptance, false);
});

for (const [name, code, mutate] of [
  ['CI-looking binding', 'LOCAL_IS_NOT_CI', f => { f.binding.ciRunUrl = 'synthetic-unverified-url'; }],
  ['fake commit identity', 'LOCAL_IS_NOT_CI', f => { f.binding.sourceSha = 'c'.repeat(40); }],
  ['changed historical baseline', 'HISTORICAL_BASELINE', f => { f.historical.sha256 = 'c'.repeat(64); }],
  ['unreviewed source', 'CURRENT_SOURCE', f => { f.snapshot.files[0].sha256 = 'c'.repeat(64); }],
  ['stale input tree', 'INPUT_TREE', f => { f.snapshot.sha256 = 'c'.repeat(64); }],
  ['stale source inventory', 'INPUT_TREE', f => { f.snapshot.count++; }],
  ['commit proof substituted for worktree', 'INPUT_TREE', f => { f.receipt.source.kind = 'COMMIT'; }],
  ['different generator', 'GENERATOR_DIGEST', f => { f.generatorHash = 'c'.repeat(64); }],
  ['failed process', 'ACTUAL_BUILD_PROFILE', f => { f.receipt.execution.exitCode = 1; }],
  ['online profile', 'ACTUAL_BUILD_PROFILE', f => { f.receipt.execution.profile = 'online'; }],
  ['up-to-date tests', 'ACTUAL_BUILD_PROFILE', f => { f.receipt.execution.rerunTasks = false; }],
  ['wrong JDK', 'ACTUAL_BUILD_PROFILE', f => { f.receipt.execution.javaMajor = 17; }],
  ['live acceptance claim', 'NO_LIVE_OR_CI_CLAIM', f => { f.receipt.operatorAcceptance = true; }],
  ['deployment permission claim', 'NO_LIVE_OR_CI_CLAIM', f => { f.receipt.executionAuthorized = true; }],
  ['real MySQL claim', 'NO_LIVE_OR_CI_CLAIM', f => { f.receipt.realMysql = true; }],
  ['real provider claim', 'NO_LIVE_OR_CI_CLAIM', f => { f.receipt.realProvider = true; }],
  ['CI attestation claim', 'LOCAL_ATTESTATION', f => { f.receipt.attestation = 'SIGNED_CI'; }],
  ['missing log provenance', 'EXECUTION_DIGESTS', f => { f.receipt.logSha256 = null; }],
  ['backwards execution window', 'EXECUTION_WINDOW', f => { f.receipt.execution.finishedAtUtc = '2026-11-04T09:00:00Z'; }],
  ['invalid calendar time', 'EXECUTION_WINDOW', f => { f.receipt.execution.startedAtUtc = '2026-02-30T10:00:00Z'; }],
  ['mismatched totals', 'TOTALS', f => { f.receipt.totals = { ...f.receipt.totals, tests: 99 }; }],
  ['missing raw report hash', 'JUNIT_PROVENANCE', f => { f.summary.suites[0].rawJUnitSha256 = null; }],
  ['wrong case count', 'JUNIT_CASE_COUNT', f => { f.summary.suites[0].tests++; }],
  ['unknown case status', 'JUNIT_CASE_STATUS', f => { f.summary.suites[0].cases[0].status = 'UNKNOWN'; }],
  ['missing compatibility case', 'FEATURE_COMPATIBILITY_NOT_PASSED', f => {
    f.summary.suites.find(s => s.name.endsWith('.ComposerJpaTest')).cases[0].name = 'unrelated()';
  }],
  ['missing required suite', 'REQUIRED_SUITE_NOT_PASSED', f => { f.summary.suites.shift(); counts(f); }],
  ['duplicate suite', 'DUPLICATE_SUITE', f => { f.summary.suites.push(structuredClone(f.summary.suites[0])); counts(f); }],
  ['skipped required test', 'REQUIRED_SUITE_NOT_PASSED', f => {
    const s = f.summary.suites[0]; s.cases[0].status = 'SKIPPED'; s.passed = 0; s.skipped = 1; counts(f);
  }],
  ['failed required test', 'BUILD_HAS_FAILURES', f => {
    const s = f.summary.suites[0]; s.cases[0].status = 'FAILED'; s.passed = 0; s.failures = 1; counts(f);
  }],
]) test(`rejects ${name}, even if the edited artifact digests are recomputed`, () => {
  const f = fixture(); mutate(f); seal(f); assert.ok(validateProof(f).includes(code));
});

test('artifact digests themselves must match', () => {
  const f = fixture(); f.receiptHash = '0'.repeat(64); f.summaryHash = '0'.repeat(64);
  assert.ok(validateProof(f).includes('RECEIPT_DIGEST')); assert.ok(validateProof(f).includes('SUMMARY_DIGEST'));
});

test('null and malformed case metadata fails closed', () => {
  const f = fixture(); f.summary.suites[0].cases[0] = null; seal(f);
  assert.ok(validateProof(f).includes('JUNIT_CASE_STATUS'));
  f.summary.suites = [null]; seal(f); assert.ok(validateProof(f).includes('INVALID_SUITE'));
});

test('proof files cannot escape the dedicated local metadata directory', () => {
  const root = fileURLToPath(new URL('../../../', import.meta.url));
  const f = fixture(); f.binding.receiptPath = '../not-an-authorized-proof.json';
  assert.deepEqual(verifyLocalBinding(root, f.binding, f.historical), ['LOCAL_PROOF_UNREADABLE']);
});

test('historical evidence catalog values and earlier CI bindings remain unchanged', () => {
  const e = JSON.parse(readFileSync(new URL('./evidence-map.json', import.meta.url), 'utf8'));
  const history = Object.fromEntries(['baselineSha', 'bundles', 'migrations', 'sourceHashMode',
    'currentSourceBindings', 'historicalSourceBindings'].map(key => [key, e[key]]));
  assert.equal(sha(history), 'befc1d3ad49a76c13819d2eae4c9618317408a2044e86d428510d5a35b5178b5');
});
