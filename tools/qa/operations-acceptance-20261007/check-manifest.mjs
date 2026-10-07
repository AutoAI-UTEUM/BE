import { readFileSync, statSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

export const BASELINE = '28e265397dc52667fd5e7f98f1e782ba2ddafef7';
export const EVIDENCE = JSON.parse(readFileSync(new URL('./evidence-map.json', import.meta.url), 'utf8'));
export const BUNDLES = [
  'access_answers', 'persistence_duplicates', 'pdf_worker_cleanup',
  'limits_security_internal_ai', 'images_migrations_restore', 'progress_scores_usage_quota',
];
export const HISTORY = {
  devLogs: {
    status: 'USER_REPORTED_READ_COMPLETE', observedAtUtc: '2026-10-06T17:39:09Z',
    mainRetentionInDays: 14, aiRetentionInDays: 14,
  },
  reviewer: {
    status: 'USER_REPORTED_PROMOTED_VERIFIED', observedAtUtc: '2026-10-06T13:31:00Z',
    binding: 'GUARDIAN_REVIEW_ACCOUNT', role: 'ADMIN', accountStatus: 'ACTIVE',
    promotionRerunAuthorized: false,
  },
};

const record = (fields) => ({ kind: 'record', fields });
const array = (item, max) => ({ kind: 'array', item, max });
const scalar = (test) => ({ kind: 'scalar', test });
const literal = (value) => scalar((x) => x === value);
const choice = (...values) => scalar((x) => values.includes(x));
const nullable = (item) => ({ kind: 'nullable', item });
const bool = scalar((x) => typeof x === 'boolean');
const count = scalar((x) => Number.isSafeInteger(x) && x >= 0);
const positive = scalar((x) => Number.isSafeInteger(x) && x > 0);
const sha = (length) => scalar((x) => typeof x === 'string' && new RegExp(`^[a-f0-9]{${length}}$`).test(x));
// Decimal strings preserve SQL BIGINT/SUM precision. They are not floating point USD.
const total = scalar((x) => typeof x === 'string' && /^(0|[1-9][0-9]{0,37})$/.test(x));
const utc = scalar((x) => {
  if (typeof x !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/.test(x)) return false;
  const date = new Date(x);
  return Number.isFinite(date.getTime()) && date.toISOString() === x.replace('Z', '.000Z');
});
const checkpoint = (fields) => record({
  readStatus: choice('NOT_COLLECTED', 'OK', 'DENIED', 'UNAVAILABLE', 'MISSING'),
  observedAtUtc: nullable(utc), data: nullable(record(fields)),
});
const historyShape = (value) => record(Object.fromEntries(Object.entries(value).map(([key, x]) =>
  [key, x && typeof x === 'object' ? historyShape(x) : literal(x)])));

export const SECTION_FIELDS = {
  artifacts: {
    mainSourceSha: sha(40), aiSourceSha: sha(40), mainImageSha256: sha(64), aiImageSha256: sha(64),
    mainRepoDigestSha256: sha(64), aiRepoDigestSha256: sha(64),
    composeSha256: sha(64), nginxSha256: sha(64), feSourceSha: sha(40),
    feArtifactSha256: sha(64), feServedSha256: sha(64),
    composeTargetMatched: bool, mainState: choice('running', 'exited', 'paused', 'restarting'),
    aiState: choice('running', 'exited', 'paused', 'restarting'),
    mainPublicPortCount: count, aiPublicPortCount: count,
  },
  migrations: {
    rows: array(record({
      version: positive, script: choice(...EVIDENCE.migrations.map((x) => x.script)),
      checksum: scalar((x) => Number.isInteger(x) && x >= -2147483648 && x <= 2147483647),
      success: bool, checksumMatchesReviewed: bool,
    }), 63),
    failedCount: count, requiredTablesPresent: bool, requiredColumnsPresent: bool,
  },
  flags: {
    allInstancesMatched: bool, reviewedNonsecretConfigSha256: sha(64),
    signupPaused: bool, guardianTeamEnabled: bool, guardianPolicyConfirmed: bool,
    reviewerBindingMatched: bool, deletionEnabled: bool, xaiFilesEnabled: bool,
    xaiFileBackfillEnabled: bool, pageQuizPlanEnabled: bool, pageQuizPlanBackfillEnabled: bool,
    qaQuizProposalEnabled: bool, quizQuestionStreamEnabled: bool,
    quotaEnabled: bool, quotaDailyDefault: positive, quotaDailyInstructor: positive,
  },
  workerCleanup: {
    processingMaterials: count, readyMaterials: count, failedMaterials: count,
    staleProcessingMaterials: count, expiredGradingLeases: count,
    dueDeletionIntents: count, failedDeletionIntents: count, policyPendingDeletionIntents: count,
    temporaryFiles: count, overdueTemporaryFiles: count, orphanFiles: count,
    workerHealth: choice('UP', 'DOWN', 'UNKNOWN'),
  },
  security: {
    nginxAiRequestsPerMinute: positive, nginxApiRequestsPerMinute: positive,
    nginxLimitStatus: literal(429), internalTokenConfigured: bool, internalBindingMatched: bool,
    internalAiPublicPortCount: count, corsAllowlistReviewed: bool,
    pageTextApiEnabled: bool, uploadMaxMiB: positive, proxyRulesReviewed: bool,
  },
  backupRestore: {
    existingBackupObservedAtUtc: utc, existingBackupSha256: sha(64), existingBackupBytes: positive,
    existingIntegrityStatus: choice('PASS', 'FAIL', 'NOT_CHECKED'), reviewedFreshnessSatisfied: bool,
    latestDeletionJournalObservedAtUtc: utc, latestDeletionJournalSha256: sha(64),
    compatibleReadersReviewed: bool,
    newHostRestoreStatus: choice('NOT_RUN', 'PASS', 'FAIL'),
    deletionReplayStatus: choice('NOT_RUN', 'PASS', 'FAIL'),
    rollbackStatus: choice('NOT_RUN', 'PASS', 'FAIL'),
  },
  reconciliation: {
    windowStartUtc: utc, windowEndUtc: utc, quotaDayBoundary: literal('Asia/Seoul'),
    usageRows: count, successfulRows: count, failedRows: count, unknownTokenRows: count,
    inputTokensKnown: total, outputTokensKnown: total, reasoningTokensKnown: total,
    knownCostRows: count, unknownCostRows: count, appKnownCostUsdTicks: total,
    existingProviderLedgerCostUsdTicks: nullable(total), ledgerScopeMatched: bool,
    progressMismatchCount: count, scoreMismatchCount: count, duplicateUsageExecutionCount: count,
    quotaBoundaryStatus: choice('NOT_RUN', 'PASS', 'FAIL'),
  },
};

const receipt = record({
  status: choice('NOT_RUN', 'PASS', 'FAIL', 'BLOCKED'),
  observedAtUtc: nullable(utc), receiptSha256: nullable(sha(64)),
});
const schema = record({
  schemaVersion: literal('operations-acceptance-v1'), requestedBaselineSha: literal(BASELINE),
  provenance: choice('SYNTHETIC', 'OPERATOR_REPORTED'), environment: choice('DEV', 'PROD'),
  confirmedHistory: historyShape(HISTORY),
  sections: record(Object.fromEntries(Object.entries(SECTION_FIELDS).map(([key, fields]) => [key, checkpoint(fields)]))),
  acceptance: record(Object.fromEntries(BUNDLES.map((id) => [id, receipt]))),
});

function validate(shape, value, path, issues) {
  if (shape.kind === 'nullable') {
    if (value !== null) validate(shape.item, value, path, issues);
  } else if (shape.kind === 'record') {
    if (!value || typeof value !== 'object' || Array.isArray(value)) {
      issues.push({ path, code: 'EXPECTED_OBJECT' }); return;
    }
    // Never echo an unrecognized key: a malicious key itself can contain a secret.
    if (Object.keys(value).some((key) => !Object.hasOwn(shape.fields, key))) issues.push({ path, code: 'UNKNOWN_FIELD' });
    for (const [key, item] of Object.entries(shape.fields)) {
      const child = `${path}.${key}`;
      if (!Object.hasOwn(value, key)) issues.push({ path: child, code: 'MISSING_FIELD' });
      else validate(item, value[key], child, issues);
    }
  } else if (shape.kind === 'array') {
    if (!Array.isArray(value) || value.length > shape.max) issues.push({ path, code: 'INVALID_ARRAY' });
    else value.forEach((item, index) => validate(shape.item, item, `${path}[${index}]`, issues));
  } else if (!shape.test(value)) issues.push({ path, code: 'INVALID_VALUE' });
}

export function checkManifest(manifest) {
  const issues = [];
  validate(schema, manifest, '$', issues);
  const result = {
    valid: false, metadataComplete: false, conditionsClear: false,
    operatorReportedAcceptanceComplete: false, liveAcceptanceVerified: false,
    executionAuthorized: false, issues,
  };
  if (issues.length) return result;

  for (const [name, item] of Object.entries(manifest.sections)) {
    const path = `$.sections.${name}`;
    const collected = item.readStatus !== 'NOT_COLLECTED';
    if ((collected && !item.observedAtUtc) || (!collected && item.observedAtUtc !== null)
      || (item.readStatus === 'OK' ? item.data === null : item.data !== null)) {
      issues.push({ path, code: 'CONTRADICTORY_READ_STATUS' });
    }
  }
  for (const id of BUNDLES) {
    const item = manifest.acceptance[id];
    if (item.status === 'NOT_RUN' ? item.observedAtUtc !== null || item.receiptSha256 !== null
      : item.observedAtUtc === null || item.receiptSha256 === null) {
      issues.push({ path: `$.acceptance.${id}`, code: 'CONTRADICTORY_RECEIPT' });
    }
  }
  if (issues.length) return result;
  result.valid = true;
  result.environment = manifest.environment;
  result.provenance = manifest.provenance;
  for (const [name, item] of Object.entries(manifest.sections)) {
    if (item.readStatus !== 'OK') issues.push({ path: `$.sections.${name}`, code: 'METADATA_NOT_AVAILABLE' });
  }
  result.metadataComplete = issues.length === 0;
  const require = (condition, path, code = 'OBSERVED_CONDITION_UNMET') => {
    if (!condition) issues.push({ path: `$.sections.${path}`, code });
  };
  const data = (name) => manifest.sections[name].readStatus === 'OK' ? manifest.sections[name].data : null;
  const a = data('artifacts');
  if (a) {
    require(a.mainSourceSha === BASELINE && a.aiSourceSha === BASELINE, 'artifacts', 'SOURCE_DIFFERS_FROM_PINNED_CANDIDATE');
    require(a.composeTargetMatched && a.mainState === 'running' && a.aiState === 'running', 'artifacts');
    require(a.mainPublicPortCount === 0 && a.aiPublicPortCount === 0, 'artifacts', 'PUBLIC_SERVICE_PORT');
    require(a.feArtifactSha256 === a.feServedSha256, 'artifacts', 'FE_ARTIFACT_MISMATCH');
  }
  const m = data('migrations');
  if (m) {
    const versions = new Set(m.rows.map((row) => row.version));
    require(m.rows.length === 63 && versions.size === 63 && [...versions].every((v) => v >= 1 && v <= 63)
      && m.rows.every((r) => EVIDENCE.migrations.find((x) => x.version === r.version)?.script === r.script
        && r.success && r.checksumMatchesReviewed)
      && m.failedCount === 0 && m.requiredTablesPresent && m.requiredColumnsPresent, 'migrations', 'MIGRATION_REVIEW_INCOMPLETE');
  }
  const f = data('flags');
  if (f) require(f.allInstancesMatched && f.reviewerBindingMatched && f.quotaEnabled
    && (!f.guardianTeamEnabled || f.guardianPolicyConfirmed), 'flags');
  const w = data('workerCleanup');
  if (w) require(w.workerHealth === 'UP' && w.staleProcessingMaterials === 0 && w.expiredGradingLeases === 0
    && w.overdueTemporaryFiles === 0 && w.orphanFiles === 0 && w.failedDeletionIntents === 0
    && w.dueDeletionIntents === 0 && w.policyPendingDeletionIntents === 0, 'workerCleanup');
  const s = data('security');
  if (s) require(s.internalTokenConfigured && s.internalBindingMatched && s.internalAiPublicPortCount === 0
    && s.corsAllowlistReviewed && !s.pageTextApiEnabled && s.proxyRulesReviewed, 'security');
  const b = data('backupRestore');
  if (b) require(b.existingIntegrityStatus === 'PASS' && b.reviewedFreshnessSatisfied && b.compatibleReadersReviewed
    && b.newHostRestoreStatus === 'PASS' && b.deletionReplayStatus === 'PASS' && b.rollbackStatus === 'PASS', 'backupRestore', 'RESTORE_ACCEPTANCE_NOT_COMPLETE');
  const r = data('reconciliation');
  if (r) {
    require(new Date(r.windowStartUtc) < new Date(r.windowEndUtc), 'reconciliation', 'INVALID_WINDOW');
    require(r.usageRows === r.successfulRows + r.failedRows && r.usageRows === r.knownCostRows + r.unknownCostRows
      && r.unknownTokenRows <= r.usageRows, 'reconciliation', 'AGGREGATE_COUNT_MISMATCH');
    require(r.unknownCostRows === 0 && r.unknownTokenRows === 0 && r.ledgerScopeMatched
      && r.existingProviderLedgerCostUsdTicks !== null && r.appKnownCostUsdTicks === r.existingProviderLedgerCostUsdTicks,
    'reconciliation', 'COST_OR_TOKEN_RECONCILIATION_PENDING');
    require(r.progressMismatchCount === 0 && r.scoreMismatchCount === 0 && r.duplicateUsageExecutionCount === 0
      && r.quotaBoundaryStatus === 'PASS', 'reconciliation');
  }
  result.conditionsClear = issues.length === 0;
  for (const id of BUNDLES) {
    if (manifest.acceptance[id].status !== 'PASS') issues.push({ path: `$.acceptance.${id}`, code: 'ACCEPTANCE_NOT_REPORTED_PASS' });
  }
  result.operatorReportedAcceptanceComplete = result.conditionsClear && issues.length === 0 && manifest.provenance === 'OPERATOR_REPORTED';
  result.historicalConfirmations = { devLogs14Days: 'USER_REPORTED_COMPLETE', reviewerActiveAdmin: 'USER_REPORTED_COMPLETE' };
  return result;
}

export function runCli(args) {
  try {
    if (args.length !== 1 || !statSync(args[0]).isFile() || statSync(args[0]).size > 131072) {
      return { exitCode: 1, report: { valid: false, executionAuthorized: false, error: 'INPUT_NOT_SMALL_JSON_FILE' } };
    }
    const report = checkManifest(JSON.parse(readFileSync(args[0], 'utf8').replace(/^\uFEFF/, '')));
    return { exitCode: report.valid ? (report.issues.length === 0 ? 0 : 2) : 1, report };
  } catch {
    return { exitCode: 1, report: { valid: false, executionAuthorized: false, error: 'INPUT_UNREADABLE_OR_INVALID_JSON' } };
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const { exitCode, report } = runCli(process.argv.slice(2));
  process.stdout.write(`${JSON.stringify(report, null, 2)}\n`);
  process.exitCode = exitCode;
}
