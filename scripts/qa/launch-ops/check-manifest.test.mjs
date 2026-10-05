import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { checkManifest } from './check-manifest.mjs';

const template = JSON.parse(readFileSync(new URL('../../../docs/qa/launch-ops/readiness-manifest.example.json', import.meta.url), 'utf8'));

function syntheticComplete() {
  const m = structuredClone(template);
  m.fe.sourceSha = '1'.repeat(40);
  m.fe.contractReviewComplete = true;
  m.fe.ci = { headSha: m.fe.sourceSha, conclusion: 'success', url: 'synthetic-ci' };
  m.fe.settings.injectionEvidence = 'synthetic-build-input';
  m.fe.artifact = { sourceSha: m.fe.sourceSha, buildRunUrl: 'synthetic-build', entryPath: '/assets/synthetic.js',
    indexSha256: '2'.repeat(64), entrySha256: '3'.repeat(64), fileManifestSha256: '4'.repeat(64),
    styles: [{ path: '/assets/synthetic.css', sha256: '7'.repeat(64) }] };
  m.mail.observed = { enabled: true, provider: 'ses', from: 'no-reply@example.com', region: 'synthetic-region',
    baseUrl: 'https://dev.uteum.com', evidence: 'synthetic-runtime' };
  m.mail.ses = { sendingEnabled: true, productionAccessEnabled: false, approvedRecipientVerified: true,
    region: 'synthetic-region', senderFrom: 'no-reply@example.com',
    identityVerified: true, accountEvidence: 'synthetic-account', identityEvidence: 'synthetic-identity', runtimeRoleEvidence: 'synthetic-role' };
  Object.assign(m.mail, { keyContinuityEvidence: 'synthetic-secret-version-only', outboxDecisionEvidence: 'synthetic-empty-queue',
    otherProducerIsolationConfirmed: true, approvalRef: 'synthetic-approval', approvedInboxHandle: 'APPROVED_INBOX_1',
    plannedMessages: 2, incrementalBudgetApproval: 'synthetic-budget' });
  Object.assign(m.window, { owner: 'Synthetic BE', feOwner: 'Synthetic FE', mailOwner: 'Synthetic mail',
    approvalRef: 'synthetic-window', pauseScopeAndBudget: 'synthetic-reviewed-budget',
    cancelOwner: 'Synthetic BE', reopenCriteria: 'synthetic-criteria' });
  Object.assign(m.recovery, { hookPreserved: true, syntheticEvidence: 'synthetic-jpa', beforeMigrationCancelPlan: 'synthetic-cancel', backupPlan: 'synthetic-backup-plan' });
  return m;
}

test('preparation example remains blocked with unknown live values and selected inbox unapproved for sending', () => {
  const result = checkManifest(template);
  assert.equal(result.metadataComplete, false);
  assert.equal(result.executionAuthorized, false);
  assert.ok(result.missing.includes('mail.approvedScope'));
  assert.ok(result.missing.includes('fe.sourceAndContract'));
});

test('a complete synthetic preparation record is still never execution authorization', () => {
  const result = checkManifest(syntheticComplete());
  assert.equal(result.metadataComplete, true);
  assert.equal(result.executionAuthorized, false);
});

const corruptions = [
  ['environment', m => { m.environment = 'prod'; }],
  ['baseline', m => { m.be.reviewedBaselineSha = 'a'.repeat(40); }],
  ['Main CI head', m => { m.be.ci.main.headSha = 'a'.repeat(40); }],
  ['AI CI incomplete', m => { m.be.ci.ai.conclusion = null; }],
  ['FE CI head', m => { m.fe.ci.headSha = 'a'.repeat(40); }],
  ['FE OFF build', m => { m.fe.settings.authReadiness = ''; }],
  ['FE dropped capability', m => { m.fe.settings.capabilities = 'reports'; }],
  ['malformed capability value', m => { m.fe.settings.capabilities = 42; }],
  ['artifact from another source', m => { m.fe.artifact.sourceSha = 'a'.repeat(40); }],
  ['artifact digest missing', m => { m.fe.artifact.entrySha256 = null; }],
  ['CSS evidence missing', m => { m.fe.artifact.styles = []; }],
  ['logging receipt', m => { m.mail.observed.provider = 'logging'; }],
  ['mail disabled', m => { m.mail.observed.enabled = false; }],
  ['sender identity unknown', m => { m.mail.ses.identityVerified = null; }],
  ['SES proof for another region', m => { m.mail.ses.region = 'another-region'; }],
  ['SES proof for another From', m => { m.mail.ses.senderFrom = 'other@example.com'; }],
  ['runtime role unknown', m => { m.mail.ses.runtimeRoleEvidence = null; }],
  ['sandbox recipient unknown', m => { m.mail.ses.approvedRecipientVerified = null; }],
  ['outbox decision unknown', m => { m.mail.outboxDecisionEvidence = null; }],
  ['other producers active', m => { m.mail.otherProducerIsolationConfirmed = false; }],
  ['mail approval absent', m => { m.mail.approvalRef = null; }],
  ['unbounded message plan', m => { m.mail.plannedMessages = 500; }],
  ['old image rollback', m => { m.recovery.strategy = 'old-image'; }],
  ['V60 hook omitted', m => { m.recovery.hookPreserved = false; }],
  ['window owner absent', m => { m.window.owner = null; }],
];
for (const [name, corrupt] of corruptions) test(`rejects ${name}`, () => {
  const m = syntheticComplete(); corrupt(m);
  assert.equal(checkManifest(m).metadataComplete, false);
});

test('prepare record cannot substitute for actual reopen evidence', () => {
  const result = checkManifest(syntheticComplete(), 'reopen');
  assert.equal(result.metadataComplete, false);
  assert.ok(result.missing.includes('be.runningImages.main'));
  assert.ok(result.missing.includes('mail.acceptance'));
});

test('reopen requires matching images/Flyway/FE, receipt, role smoke and zero withdrawn DOB leaks', () => {
  const m = syntheticComplete();
  m.be.runningImages = Object.fromEntries(['main', 'ai'].map(name => [name,
    { sourceSha: m.be.sourceSha, repoDigest: `sha256:${'5'.repeat(64)}`, readbackEvidence: 'synthetic-image' }]));
  m.be.flyway = { latestVersion: '60', failedCount: 0, checksumMatched: true, evidence: 'synthetic-flyway' };
  m.fe.served = { indexSha256: m.fe.artifact.indexSha256, entrySha256: m.fe.artifact.entrySha256,
    fileManifestSha256: m.fe.artifact.fileManifestSha256, verifyRouteStatus: 200,
    onBehaviorAccepted: true, evidence: 'synthetic-served' };
  m.mail.receiptEvidence = 'synthetic-inbox'; m.mail.explicitConfirmAndStatusEvidence = 'synthetic-confirm';
  Object.assign(m.recovery, { deletedUserDobLeakCount: 0, privateFreshBackupRef: 'synthetic-backup',
    backupIntegrityChecked: true, currentDeletionJournalRef: 'synthetic-private-journal' });
  m.window.roleSmokePassed = true; m.window.smokeEvidence = 'synthetic-roles';
  assert.equal(checkManifest(m, 'reopen').metadataComplete, true);
  m.recovery.deletedUserDobLeakCount = 1;
  assert.ok(checkManifest(m, 'reopen').missing.includes('recovery.runtime'));
  m.recovery.deletedUserDobLeakCount = 0;
  m.fe.served.entrySha256 = '6'.repeat(64);
  assert.ok(checkManifest(m, 'reopen').missing.includes('fe.served'));
});

test('malformed or absent values fail without exposing field contents', () => {
  assert.equal(checkManifest(null).metadataComplete, false);
  assert.equal(checkManifest({}, 'unknown').metadataComplete, false);
  const m = syntheticComplete(); m.mail.observed.evidence = '';
  const result = checkManifest(m);
  assert.equal(JSON.stringify(result).includes('no-reply@example.com'), false);
});

test('CLI reads only the selected local manifest and blocks the unfilled example', () => {
  const script = fileURLToPath(new URL('./check-manifest.mjs', import.meta.url));
  const fixture = fileURLToPath(new URL('../../../docs/qa/launch-ops/readiness-manifest.example.json', import.meta.url));
  const result = spawnSync(process.execPath, [script, fixture], { encoding: 'utf8' });
  assert.equal(result.status, 2);
  assert.equal(JSON.parse(result.stdout).executionAuthorized, false);
});
