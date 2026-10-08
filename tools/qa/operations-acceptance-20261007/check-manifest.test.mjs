import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtempSync, readFileSync, rmdirSync, unlinkSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { BASELINE, BUNDLES, EVIDENCE, HISTORY, checkManifest, runCli } from './check-manifest.mjs';

const template = JSON.parse(readFileSync(new URL('./metadata-manifest.example.json', import.meta.url), 'utf8'));
const clone = () => structuredClone(template);
const hash = 'a'.repeat(64);
const at = '2026-10-07T02:00:00Z';
const root = fileURLToPath(new URL('../../../', import.meta.url));

// Runtime observations below are invented; checksums are from the pinned migration scripts.
// No Docker, DB, AWS, account, mail or AI is contacted.
function synthetic() {
  const m = clone();
  m.provenance = 'SYNTHETIC';
  const data = {
    artifacts: {
      mainSourceSha: BASELINE, aiSourceSha: BASELINE, mainImageSha256: hash, aiImageSha256: hash,
      mainRepoDigestSha256: hash, aiRepoDigestSha256: hash, composeSha256: hash, nginxSha256: hash,
      feSourceSha: 'b'.repeat(40), feArtifactSha256: hash, feServedSha256: hash, composeTargetMatched: true,
      mainState: 'running', aiState: 'running', mainPublicPortCount: 0, aiPublicPortCount: 0,
    },
    migrations: {
      rows: EVIDENCE.migrations.map((x) => ({ version: x.version, script: x.script,
        checksum: x.flywayChecksum, success: true, checksumMatchesReviewed: true })),
      failedCount: 0, requiredTablesPresent: true, requiredColumnsPresent: true,
    },
    flags: {
      allInstancesMatched: true, reviewedNonsecretConfigSha256: hash, signupPaused: true,
      guardianTeamEnabled: false, guardianPolicyConfirmed: false, reviewerBindingMatched: true,
      deletionEnabled: false, xaiFilesEnabled: false, xaiFileBackfillEnabled: false,
      pageQuizPlanEnabled: false, pageQuizPlanBackfillEnabled: false, qaQuizProposalEnabled: false,
      quizQuestionStreamEnabled: false, quotaEnabled: true, quotaDailyDefault: 200, quotaDailyInstructor: 500,
    },
    workerCleanup: {
      processingMaterials: 0, readyMaterials: 2, failedMaterials: 1, staleProcessingMaterials: 0,
      expiredGradingLeases: 0, dueDeletionIntents: 0, failedDeletionIntents: 0,
      policyPendingDeletionIntents: 0, temporaryFiles: 0, overdueTemporaryFiles: 0, orphanFiles: 0,
      workerHealth: 'UP',
    },
    security: {
      nginxAiRequestsPerMinute: 60, nginxApiRequestsPerMinute: 600, nginxLimitStatus: 429,
      internalTokenConfigured: true, internalBindingMatched: true, internalAiPublicPortCount: 0,
      corsAllowlistReviewed: true, pageTextApiEnabled: false, uploadMaxMiB: 50, proxyRulesReviewed: true,
    },
    backupRestore: {
      existingBackupObservedAtUtc: at, existingBackupSha256: hash, existingBackupBytes: 4096,
      existingIntegrityStatus: 'PASS', reviewedFreshnessSatisfied: true,
      latestDeletionJournalObservedAtUtc: at, latestDeletionJournalSha256: hash, compatibleReadersReviewed: true,
      newHostRestoreStatus: 'PASS', deletionReplayStatus: 'PASS', rollbackStatus: 'PASS',
    },
    reconciliation: {
      windowStartUtc: '2026-10-06T15:00:00Z', windowEndUtc: '2026-10-07T15:00:00Z',
      quotaDayBoundary: 'Asia/Seoul', usageRows: 3, successfulRows: 2, failedRows: 1, unknownTokenRows: 0,
      inputTokensKnown: '100', outputTokensKnown: '30', reasoningTokensKnown: '0',
      knownCostRows: 3, unknownCostRows: 0, appKnownCostUsdTicks: '9007199254740993',
      existingProviderLedgerCostUsdTicks: '9007199254740993', ledgerScopeMatched: true,
      progressMismatchCount: 0, scoreMismatchCount: 0, duplicateUsageExecutionCount: 0,
      quotaBoundaryStatus: 'PASS',
    },
  };
  for (const [name, values] of Object.entries(data)) m.sections[name] = { readStatus: 'OK', observedAtUtc: at, data: values };
  for (const id of BUNDLES) m.acceptance[id] = { status: 'PASS', observedAtUtc: at, receiptSha256: hash };
  return m;
}

test('unfilled operator template remains blocked and preserves completed historical confirmations', () => {
  const r = checkManifest(clone());
  assert.equal(r.valid, true);
  assert.equal(r.metadataComplete, false);
  assert.equal(r.conditionsClear, false);
  assert.equal(r.operatorReportedAcceptanceComplete, false);
  assert.equal(r.liveAcceptanceVerified, false);
  assert.equal(r.executionAuthorized, false);
  assert.deepEqual(template.confirmedHistory, HISTORY);
  assert.deepEqual(r.historicalConfirmations, { devLogs14Days: 'USER_REPORTED_COMPLETE', reviewerActiveAdmin: 'USER_REPORTED_COMPLETE' });
  assert.equal(r.issues.filter((x) => x.code === 'METADATA_NOT_AVAILABLE').length, 7);
  assert.equal(r.issues.filter((x) => x.code === 'ACCEPTANCE_NOT_REPORTED_PASS').length, 6);
});

test('complete synthetic metadata can pass without attesting live acceptance or authorizing execution', () => {
  const r = checkManifest(synthetic());
  assert.equal(r.valid, true);
  assert.equal(r.metadataComplete, true);
  assert.equal(r.conditionsClear, true);
  assert.deepEqual(r.issues, []);
  assert.equal(r.operatorReportedAcceptanceComplete, false);
  assert.equal(r.liveAcceptanceVerified, false);
  assert.equal(r.executionAuthorized, false);
});

test('operator-reported complete evidence still requires human source/freshness/authorization review', () => {
  const m = synthetic(); m.provenance = 'OPERATOR_REPORTED';
  const r = checkManifest(m);
  assert.equal(r.operatorReportedAcceptanceComplete, true);
  assert.equal(r.liveAcceptanceVerified, false);
  assert.equal(r.executionAuthorized, false);
});

test('caller review flag cannot approve a changed Flyway checksum', () => {
  const m = synthetic();
  m.sections.migrations.data.rows[0].checksum = 0;
  m.sections.migrations.data.rows[0].checksumMatchesReviewed = true;
  const r = checkManifest(m);
  assert.equal(r.valid, true);
  assert.equal(r.conditionsClear, false);
  assert.ok(r.issues.some((x) => x.code === 'MIGRATION_CHECKSUM_MISMATCH'));
  assert.equal(r.liveAcceptanceVerified, false);
  assert.equal(r.executionAuthorized, false);
});

test('PROD does not inherit DEV live acceptance from history', () => {
  const m = clone(); m.environment = 'PROD';
  const r = checkManifest(m);
  assert.equal(r.environment, 'PROD');
  assert.equal(r.operatorReportedAcceptanceComplete, false);
  assert.equal(r.liveAcceptanceVerified, false);
});

for (const [name, edit] of [
  ['log history cannot become unknown', (m) => { m.confirmedHistory.devLogs.status = 'UNKNOWN'; }],
  ['log retention cannot become null', (m) => { m.confirmedHistory.devLogs.mainRetentionInDays = null; }],
  ['completed ADMIN promotion cannot become pending', (m) => { m.confirmedHistory.reviewer.role = 'LEARNER'; }],
  ['historical promotion is not permission to rerun', (m) => { m.confirmedHistory.reviewer.promotionRerunAuthorized = true; }],
  ['baseline SHA is immutable', (m) => { m.requestedBaselineSha = '1'.repeat(40); }],
  ['execution authorization cannot be inserted', (m) => { m.executionAuthorized = true; }],
  ['secret value cannot be inserted in known numeric field', (m) => { m.sections.workerCleanup.data.processingMaterials = 'CANARY_PRIVATE_PASSWORD'; }],
  ['raw env field is rejected', (m) => { m.sections.flags.data.env = 'CANARY_PRIVATE_PASSWORD'; }],
  ['token is not metadata', (m) => { m.sections.security.data.internalToken = 'CANARY_PRIVATE_PASSWORD'; }],
  ['hashed token is not metadata', (m) => { m.sections.security.data.internalTokenSha256 = hash; }],
  ['sensitive unknown key is not echoed', (m) => { m['CANARY_PRIVATE_PASSWORD'] = true; }],
  ['negative counts are invalid', (m) => { m.sections.workerCleanup.data.orphanFiles = -1; }],
  ['fractional counts are invalid', (m) => { m.sections.workerCleanup.data.orphanFiles = 0.5; }],
  ['unsafe integer counts are invalid', (m) => { m.sections.workerCleanup.data.orphanFiles = Number.MAX_SAFE_INTEGER + 1; }],
  ['SHA-256 is not a Flyway checksum', (m) => { m.sections.migrations.data.rows[0].checksum = hash; }],
  ['checksum overflow is invalid', (m) => { m.sections.migrations.data.rows[0].checksum = 2147483648; }],
  ['noncanonical migration script is invalid', (m) => { m.sections.migrations.data.rows[0].script = 'V1__invented.sql'; }],
  ['impossible calendar date is invalid', (m) => { m.sections.artifacts.observedAtUtc = '2026-02-30T00:00:00Z'; }],
  ['local timestamp without UTC is invalid', (m) => { m.sections.artifacts.observedAtUtc = '2026-10-07T02:00:00'; }],
  ['cost totals must preserve integer precision', (m) => { m.sections.reconciliation.data.appKnownCostUsdTicks = 9007199254740993; }],
  ['negative cost totals are invalid', (m) => { m.sections.reconciliation.data.appKnownCostUsdTicks = '-1'; }],
  ['NOT_RUN must not carry a pass receipt', (m) => { m.acceptance.access_answers.status = 'NOT_RUN'; }],
  ['PASS requires a receipt digest', (m) => { m.acceptance.access_answers.receiptSha256 = null; }],
  ['OK cannot mean empty data', (m) => { m.sections.artifacts.data = null; }],
  ['DENIED cannot carry apparently successful data', (m) => { m.sections.artifacts.readStatus = 'DENIED'; }],
  ['NOT_COLLECTED cannot carry a timestamp', (m) => { m.sections.artifacts = { readStatus: 'NOT_COLLECTED', observedAtUtc: at, data: null }; }],
]) {
  test(name, () => {
    const m = synthetic(); edit(m);
    const r = checkManifest(m);
    assert.equal(r.valid, false);
    assert.equal(r.executionAuthorized, false);
    assert.equal(JSON.stringify(r).includes('CANARY_PRIVATE_PASSWORD'), false);
  });
}

for (const status of ['DENIED', 'MISSING', 'UNAVAILABLE', 'NOT_COLLECTED']) {
  test(`${status} is unknown metadata, never zero or completed acceptance`, () => {
    const m = synthetic();
    m.sections.workerCleanup = { readStatus: status, observedAtUtc: status === 'NOT_COLLECTED' ? null : at, data: null };
    const r = checkManifest(m);
    assert.equal(r.valid, true);
    assert.equal(r.metadataComplete, false);
    assert.equal(r.conditionsClear, false);
  });
}

for (const [name, edit] of [
  ['different running source remains a candidate mismatch', (m) => { m.sections.artifacts.data.mainSourceSha = 'b'.repeat(40); }],
  ['FE served hash mismatch blocks', (m) => { m.sections.artifacts.data.feServedSha256 = 'b'.repeat(64); }],
  ['public AI port blocks', (m) => { m.sections.artifacts.data.aiPublicPortCount = 1; }],
  ['missing V63 cannot be counted as applied', (m) => { m.sections.migrations.data.rows.pop(); }],
  ['duplicate migration version blocks', (m) => { m.sections.migrations.data.rows[1] = m.sections.migrations.data.rows[0]; }],
  ['version/script binding must match', (m) => { m.sections.migrations.data.rows[0].script = m.sections.migrations.data.rows[1].script; }],
  ['failed migration blocks', (m) => { m.sections.migrations.data.failedCount = 1; }],
  ['unreviewed checksum blocks', (m) => { m.sections.migrations.data.rows[0].checksumMatchesReviewed = false; }],
  ['required column absence blocks', (m) => { m.sections.migrations.data.requiredColumnsPresent = false; }],
  ['unconfirmed enabled guardian policy blocks', (m) => { m.sections.flags.data.guardianTeamEnabled = true; }],
  ['reviewer config mismatch does not undo historical ADMIN promotion', (m) => { m.sections.flags.data.reviewerBindingMatched = false; }],
  ['stale worker state blocks', (m) => { m.sections.workerCleanup.data.staleProcessingMaterials = 1; }],
  ['temporary-file leak blocks', (m) => { m.sections.workerCleanup.data.overdueTemporaryFiles = 1; }],
  ['pending deletion policy blocks cleanup acceptance', (m) => { m.sections.workerCleanup.data.policyPendingDeletionIntents = 1; }],
  ['internal token binding mismatch blocks', (m) => { m.sections.security.data.internalBindingMatched = false; }],
  ['new-host restore NOT_RUN blocks', (m) => { m.sections.backupRestore.data.newHostRestoreStatus = 'NOT_RUN'; }],
  ['rollback NOT_RUN blocks', (m) => { m.sections.backupRestore.data.rollbackStatus = 'NOT_RUN'; }],
  ['missing current deletion replay blocks', (m) => { m.sections.backupRestore.data.deletionReplayStatus = 'NOT_RUN'; }],
  ['old backup is not a fresh-backup proof', (m) => { m.sections.backupRestore.data.reviewedFreshnessSatisfied = false; }],
  ['unknown provider total is not zero cost', (m) => { m.sections.reconciliation.data.existingProviderLedgerCostUsdTicks = null; }],
  ['one unknown cost row blocks exact reconciliation', (m) => { m.sections.reconciliation.data.unknownCostRows = 1; m.sections.reconciliation.data.knownCostRows = 2; }],
  ['provider/app cost mismatch blocks', (m) => { m.sections.reconciliation.data.existingProviderLedgerCostUsdTicks = '9007199254740994'; }],
  ['missing failed usage row blocks', (m) => { m.sections.reconciliation.data.failedRows = 0; }],
  ['unknown tokens are not free calls', (m) => { m.sections.reconciliation.data.unknownTokenRows = 1; }],
  ['different provider feature scope blocks', (m) => { m.sections.reconciliation.data.ledgerScopeMatched = false; }],
  ['KST quota boundary NOT_RUN blocks', (m) => { m.sections.reconciliation.data.quotaBoundaryStatus = 'NOT_RUN'; }],
  ['progress mismatch blocks', (m) => { m.sections.reconciliation.data.progressMismatchCount = 1; }],
  ['score mismatch blocks', (m) => { m.sections.reconciliation.data.scoreMismatchCount = 1; }],
  ['zero-length reconciliation window blocks', (m) => { m.sections.reconciliation.data.windowEndUtc = m.sections.reconciliation.data.windowStartUtc; }],
]) {
  test(name, () => {
    const m = synthetic(); edit(m);
    const r = checkManifest(m);
    assert.equal(r.valid, true);
    assert.equal(r.metadataComplete, true);
    assert.equal(r.conditionsClear, false);
    assert.equal(r.operatorReportedAcceptanceComplete, false);
    assert.equal(r.executionAuthorized, false);
    assert.equal(r.historicalConfirmations.reviewerActiveAdmin, 'USER_REPORTED_COMPLETE');
  });
}

test('all six separate acceptance receipts are required', () => {
  for (const id of BUNDLES) {
    const m = synthetic(); m.provenance = 'OPERATOR_REPORTED';
    m.acceptance[id] = { status: 'NOT_RUN', observedAtUtc: null, receiptSha256: null };
    assert.equal(checkManifest(m).operatorReportedAcceptanceComplete, false);
  }
});

test('catalog pins six bundles and keeps persistence tests owned by A', () => {
  assert.equal(EVIDENCE.baselineSha, BASELINE);
  assert.equal(EVIDENCE.sourceHashMode, 'git-blob-sha256;normalize-checkout-CRLF-to-LF-for-text');
  assert.deepEqual(EVIDENCE.bundles.map((b) => b.id), BUNDLES);
  assert.match(EVIDENCE.bundles[1].owner, /A-test-execution;C-reference-only/);
  assert.equal(EVIDENCE.migrations.length, 63);
  assert.deepEqual(EVIDENCE.migrations.map((x) => x.version), Array.from({ length: 63 }, (_, i) => i + 1));
});

test('all 46 existing evidence references and 63 migrations match pinned file SHA-256', () => {
  const sources = [...EVIDENCE.bundles.flatMap((b) => b.sources), ...EVIDENCE.migrations];
  assert.equal(sources.length, 109);
  for (const x of sources) {
    const canonical = readFileSync(join(root, x.path), 'utf8').replaceAll('\r\n', '\n');
    const observed = createHash('sha256').update(canonical).digest('hex');
    assert.equal(observed, x.sha256, x.path);
  }
});

test('checker has no network, subprocess, credential, SQL execution or write API', () => {
  const source = readFileSync(new URL('./check-manifest.mjs', import.meta.url), 'utf8');
  assert.doesNotMatch(source, /node:(?:child_process|net|http|https|tls)|\bfetch\s*\(|writeFile|appendFile|process\.env/);
});

test('public handoff uses opaque user-execution references and preserves completed history', () => {
  const doc = readFileSync(join(root, 'docs/qa/operations-acceptance-20261007.md'), 'utf8');
  assert.match(doc, /DEV_LOG_RETENTION_USER_RECEIPT_20261006/);
  assert.match(doc, /GUARDIAN_ADMIN_USER_RECEIPT_20261006/);
  assert.match(doc, /ACTIVE ADMIN 승격·정상 API readback 완료/);
  assert.match(doc, /보존 14일/);
  assert.doesNotMatch(doc, /admin-role-\d+|evidence\/dev-log-metadata-[0-9]+\/|C:[\\/]Users[\\/]/i);
});

test('query plan is SELECT-only, has no unbounded raw data output and needs explicit window binding', () => {
  const sql = readFileSync(new URL('./readonly-aggregates.sql', import.meta.url), 'utf8').replace(/--[^\n]*/g, '');
  const statements = sql.split(';').map((x) => x.trim()).filter(Boolean);
  assert.equal(statements.length, 13);
  for (const statement of statements) {
    assert.match(statement, /^SELECT\b/i);
    assert.doesNotMatch(statement, /\b(?:INSERT|UPDATE|DELETE|DROP|ALTER|GRANT|REVOKE|CALL|SET|LOCK|INTO|OUTFILE|DUMPFILE|SLEEP|BENCHMARK)\b/i);
    assert.doesNotMatch(statement, /SELECT\s+\*|\b(?:password|email|answer|content|resource_key|lease_token|failure_trace_id)\s*(?:,|FROM)/i);
  }
  assert.match(sql, /:window_start_utc/);
  assert.match(sql, /COUNT\(\*\) - COUNT\(cost_usd_ticks\)/);
});

test('CLI masks parse errors, filenames and canaries; exits 2 for unfilled evidence', () => {
  const directory = mkdtempSync(join(fileURLToPath(new URL('./', import.meta.url)), '.synthetic-c-'));
  const input = join(directory, 'CANARY_PRIVATE_PASSWORD.json');
  try {
    writeFileSync(input, '{CANARY_PRIVATE_PASSWORD');
    assert.equal(JSON.stringify(runCli([input])).includes('CANARY_PRIVATE_PASSWORD'), false);
    assert.equal(runCli([input]).exitCode, 1);
    writeFileSync(input, JSON.stringify(clone()));
    const child = spawnSync(process.execPath, [fileURLToPath(new URL('./check-manifest.mjs', import.meta.url)), input], { encoding: 'utf8', shell: false });
    assert.equal(child.status, 2);
    assert.equal(JSON.stringify(child).includes('CANARY_PRIVATE_PASSWORD'), false);
    assert.equal(JSON.parse(child.stdout).executionAuthorized, false);
    writeFileSync(input, JSON.stringify(synthetic()));
    assert.equal(runCli([input]).exitCode, 0);
    const m = synthetic(); m.acceptance.access_answers = { status: 'NOT_RUN', observedAtUtc: null, receiptSha256: null };
    writeFileSync(input, JSON.stringify(m));
    assert.equal(runCli([input]).exitCode, 2);
    writeFileSync(input, ' '.repeat(131073));
    assert.equal(runCli([input]).exitCode, 1);
    assert.equal(runCli([]).exitCode, 1);
    assert.equal(runCli([directory]).exitCode, 1);
  } finally {
    // Delete only the exact file and empty directory created by this test, never recursively.
    unlinkSync(input); rmdirSync(directory);
  }
});


function signedFlywayChecksum(text) {
  const bytes = Buffer.from(text.replace(/^\uFEFF/, '').replace(/\r\n|\r|\n/g, ''), 'utf8');
  let crc = -1;
  for (const byte of bytes) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0);
  }
  return ~crc;
}

test('trusted catalog matches signed UTF8 Flyway checksums regardless of BOM and line endings', () => {
  for (const item of EVIDENCE.migrations) {
    const text = readFileSync(join(root, item.path), 'utf8').replaceAll('\r\n', '\n');
    for (const variant of [text, text.replaceAll('\n', '\r\n'), text.replaceAll('\n', '\r'), `\uFEFF${text}`]) {
      assert.equal(signedFlywayChecksum(variant), item.flywayChecksum, item.script);
    }
  }
  assert.equal(EVIDENCE.migrations[0].flywayChecksum, -65002876);
  assert.equal(EVIDENCE.migrations.at(-1).flywayChecksum, 1429210292);
});

test('each altered numeric checksum is rejected even with a positive review flag', () => {
  for (let index = 0; index < EVIDENCE.migrations.length; index++) {
    const m = synthetic();
    const row = m.sections.migrations.data.rows[index];
    row.checksum = row.checksum === 2147483647 ? -2147483648 : row.checksum + 1;
    const result = checkManifest(m);
    assert.equal(result.valid, true);
    assert.equal(result.conditionsClear, false);
    assert.ok(result.issues.some((x) => x.path === `$.sections.migrations.rows[${index}].checksum`
      && x.code === 'MIGRATION_CHECKSUM_MISMATCH'));
  }
});

test('checksums swapped between valid migration versions cannot satisfy review', () => {
  const m = synthetic();
  const rows = m.sections.migrations.data.rows;
  [rows[0].checksum, rows.at(-1).checksum] = [rows.at(-1).checksum, rows[0].checksum];
  const result = checkManifest(m);
  assert.equal(result.valid, true);
  assert.equal(result.conditionsClear, false);
  assert.equal(result.issues.filter((x) => x.code === 'MIGRATION_CHECKSUM_MISMATCH').length, 2);
});
