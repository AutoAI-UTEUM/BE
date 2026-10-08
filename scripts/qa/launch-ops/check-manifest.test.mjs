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
  m.fe.guardianContract = { sourceSha: m.fe.sourceSha, reviewEvidence: 'synthetic-fe-contract',
    intake: true, fragmentView: true, explicitDeclaration: true, currentGenerationReply: true,
    adminReview: true, scopeSeparation: true, revocationAndExpiry: true };
  m.fe.settings.injectionEvidence = 'synthetic-build-input';
  m.fe.artifact = { sourceSha: m.fe.sourceSha, buildRunUrl: 'synthetic-build', entryPath: '/assets/synthetic.js',
    indexSha256: '2'.repeat(64), entrySha256: '3'.repeat(64), fileManifestSha256: '4'.repeat(64),
    styles: [{ path: '/assets/synthetic.css', sha256: '7'.repeat(64) }] };
  m.mail.observed = { enabled: true, provider: 'ses', from: 'no-reply@uteum.com', region: 'ap-northeast-2',
    baseUrl: 'https://dev.uteum.com', evidence: 'synthetic-runtime' };
  m.mail.ses = { sendingEnabled: true, productionAccessEnabled: false, approvedRecipientVerified: true,
    region: 'ap-northeast-2', senderFrom: 'no-reply@uteum.com',
    identityVerified: true, accountEvidence: 'synthetic-account', identityEvidence: 'synthetic-identity', runtimeRoleEvidence: 'synthetic-role' };
  Object.assign(m.mail, { keyContinuityEvidence: 'synthetic-secret-version-only', outboxDecisionEvidence: 'synthetic-empty-queue',
    otherProducerIsolationConfirmed: true, approvalRef: 'synthetic-approval', approvedInboxHandle: 'APPROVED_INBOX_1',
    plannedMessages: 1, plannedActions: ['SIGNUP_VERIFY'], approvedOperation: 'SERVICE_ACCEPTANCE',
    incrementalBudgetApproval: 'synthetic-service-budget' });
  Object.assign(m.window, { owner: 'Synthetic BE', feOwner: 'Synthetic FE', mailOwner: 'Synthetic mail',
    approvalRef: 'synthetic-window', pauseScopeAndBudget: 'synthetic-reviewed-budget',
    cancelOwner: 'Synthetic BE', reopenCriteria: 'synthetic-criteria' });
  Object.assign(m.recovery, { hookPreserved: true, teamReadersPreserved: true, consentEpochAndDigestPreserved: true,
    syntheticEvidence: 'synthetic-jpa', beforeMigrationCancelPlan: 'synthetic-cancel', backupPlan: 'synthetic-backup-plan' });
  m.guardian = {
    mode: 'TEAM_REVIEW',
    proposedPolicy: { enabled: true, policyConfirmed: true, approvalRef: 'synthetic-policy',
      noticeVersion: 'synthetic-v1', noticeDigest: '8'.repeat(64), configurationDigest: '9'.repeat(64),
      noticeUrl: 'https://example.com/notice', portalBaseUrl: 'https://example.com/guardian',
      collectionItemsReviewRef: 'synthetic-items', purposesReviewRef: 'synthetic-purpose',
      retentionReviewRef: 'synthetic-retention', refusalReviewRef: 'synthetic-refusal',
      relationshipReviewRef: 'synthetic-relationship', requiredScopes: ['SERVICE'],
      optionalAiScope: 'EXTERNAL_AI', optionalConsentReviewRef: 'synthetic-optional-ai',
      reviewerIds: [123], linkTtlSeconds: 3600, requestTtlSeconds: 345600,
      approvedEvidenceRetentionSeconds: 2592000, approvalValiditySeconds: 2592000 },
    reply: { channel: 'EMAIL_REPLY', selectedInboxHandle: 'GUARDIAN_REPLY_INBOX',
      selectionRef: 'synthetic-selection', privateBindingEvidence: 'synthetic-private-binding' },
    reviewer: { selectedAccountHandle: 'GUARDIAN_REVIEW_ACCOUNT', sameAccountAsReplyInbox: true,
      selectionRef: 'synthetic-selection', matchedAccountCount: 1, userId: 123,
      role: 'ADMIN', status: 'ACTIVE', readOnlyCheck: true, identityEvidence: 'synthetic-user-select' },
    cleanup: { syntheticEvidence: 'synthetic-cleanup', externalMailboxPlan: 'synthetic-mailbox-cleanup',
      backupRestoreReplayPlan: 'synthetic-restore', monitoringPlan: 'synthetic-due-monitor' },
  };
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
  ['old v1 record', m => { m.version = 'launch-ops-v1'; }],
  ['V61 source hash missing', m => { m.be.sourceFileHashes = m.be.sourceFileHashes.filter(file => !file.path.includes('V61__')); }],
  ['Main CI head', m => { m.be.ci.main.headSha = 'a'.repeat(40); }],
  ['AI CI incomplete', m => { m.be.ci.ai.conclusion = null; }],
  ['FE CI head', m => { m.fe.ci.headSha = 'a'.repeat(40); }],
  ['FE OFF build', m => { m.fe.settings.authReadiness = ''; }],
  ['FE missing TEAM review', m => { m.fe.guardianContract = null; }],
  ['FE stale TEAM review source', m => { m.fe.guardianContract.sourceSha = 'a'.repeat(40); }],
  ['FE approval from web declaration', m => { m.fe.guardianContract.explicitDeclaration = false; }],
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
  ['reply inbox used as sender', m => { m.mail.observed.from = 'reply@example.com'; m.mail.ses.senderFrom = 'reply@example.com'; }],
  ['runtime role unknown', m => { m.mail.ses.runtimeRoleEvidence = null; }],
  ['sandbox recipient unknown', m => { m.mail.ses.approvedRecipientVerified = null; }],
  ['outbox decision unknown', m => { m.mail.outboxDecisionEvidence = null; }],
  ['other producers active', m => { m.mail.otherProducerIsolationConfirmed = false; }],
  ['mail approval absent', m => { m.mail.approvalRef = null; }],
  ['unbounded message plan', m => { m.mail.plannedMessages = 500; }],
  ['SDK trial approval reused', m => { m.mail.approvalRef = m.mail.sdkTrial.sendApprovalRef; }],
  ['SDK trial budget reused', m => { m.mail.incrementalBudgetApproval = m.mail.sdkTrial.sendApprovalRef; }],
  ['SDK test counted as service', m => { m.mail.plannedActions = ['TEST']; }],
  ['signup service acceptance omitted', m => { m.mail.plannedActions = ['PASSWORD_RESET']; }],
  ['three service actions under one-send budget', m => { m.mail.plannedActions = ['SIGNUP_VERIFY', 'PASSWORD_RESET', 'WITHDRAWAL_COMPLETE']; }],
  ['SDK rerun allowed', m => { m.mail.sdkTrial.rerunPermitted = true; }],
  ['old image rollback', m => { m.recovery.strategy = 'old-image'; }],
  ['V60 hook omitted', m => { m.recovery.hookPreserved = false; }],
  ['TEAM reader omitted', m => { m.recovery.teamReadersPreserved = false; }],
  ['consent epoch recovery omitted', m => { m.recovery.consentEpochAndDigestPreserved = false; }],
  ['window owner absent', m => { m.window.owner = null; }],
];
for (const [name, corrupt] of corruptions) test(`rejects ${name}`, () => {
  const m = syntheticComplete(); corrupt(m);
  assert.equal(checkManifest(m).metadataComplete, false);
});

const guardianCorruptions = [
  ['unapproved policy', 'guardian.reviewedPolicy', g => { g.proposedPolicy.policyConfirmed = false; }],
  ['relationship procedure absent', 'guardian.reviewedPolicy', g => { g.proposedPolicy.relationshipReviewRef = null; }],
  ['notice digest absent', 'guardian.reviewedPolicy', g => { g.proposedPolicy.noticeDigest = null; }],
  ['non-HTTPS notice', 'guardian.reviewedPolicy', g => { g.proposedPolicy.noticeUrl = 'http://example.com/notice'; }],
  ['SERVICE includes mandatory AI', 'guardian.scopeSeparation', g => { g.proposedPolicy.requiredScopes.push('EXTERNAL_AI'); }],
  ['optional AI notice absent', 'guardian.scopeSeparation', g => { g.proposedPolicy.optionalConsentReviewRef = null; }],
  ['link exceeds 24 hours', 'guardian.reviewedDurations', g => { g.proposedPolicy.linkTtlSeconds = 86401; }],
  ['request exceeds first-contact five-day limit', 'guardian.reviewedDurations', g => { g.proposedPolicy.requestTtlSeconds = 432001; }],
  ['link longer than request', 'guardian.reviewedDurations', g => { g.proposedPolicy.linkTtlSeconds = 7200; g.proposedPolicy.requestTtlSeconds = 3600; }],
  ['evidence shorter than approval', 'guardian.reviewedDurations', g => { g.proposedPolicy.approvedEvidenceRetentionSeconds = 1; }],
  ['undeclared contact binding', 'guardian.selectedReplyAndReviewer', g => { g.reply.privateBindingEvidence = null; }],
  ['different reviewer account', 'guardian.selectedReplyAndReviewer', g => { g.reviewer.sameAccountAsReplyInbox = false; }],
  ['unknown selected account', 'guardian.existingActiveAdmin', g => { g.reviewer.matchedAccountCount = null; }],
  ['absent selected account', 'guardian.existingActiveAdmin', g => { g.reviewer.matchedAccountCount = 0; }],
  ['ambiguous selected account', 'guardian.existingActiveAdmin', g => { g.reviewer.matchedAccountCount = 2; }],
  ['existing non-admin', 'guardian.existingActiveAdmin', g => { g.reviewer.role = 'STUDENT'; }],
  ['suspended admin', 'guardian.existingActiveAdmin', g => { g.reviewer.status = 'SUSPENDED'; }],
  ['unmatched configured reviewer', 'guardian.existingActiveAdmin', g => { g.proposedPolicy.reviewerIds = [456]; }],
  ['role grant masquerading as read-only check', 'guardian.existingActiveAdmin', g => { g.reviewer.readOnlyCheck = false; }],
  ['external mailbox plan absent', 'guardian.cleanupPlan', g => { g.cleanup.externalMailboxPlan = null; }],
];
for (const [name, expected, corrupt] of guardianCorruptions) test(`rejects ${name}`, () => {
  const m = syntheticComplete(); corrupt(m.guardian);
  assert.ok(checkManifest(m).missing.includes(expected));
});

test('SERVICE-only reviewed policy remains separate from optional AI', () => {
  const m = syntheticComplete();
  m.guardian.proposedPolicy.optionalAiScope = null;
  m.guardian.proposedPolicy.optionalConsentReviewRef = null;
  assert.equal(checkManifest(m).metadataComplete, true);
  m.guardian.proposedPolicy.optionalConsentReviewRef = 'orphan-ai-notice';
  assert.ok(checkManifest(m).missing.includes('guardian.scopeSeparation'));
});

test('prepare record cannot substitute for actual reopen evidence', () => {
  const result = checkManifest(syntheticComplete(), 'reopen');
  assert.equal(result.metadataComplete, false);
  assert.ok(result.missing.includes('be.runningImages.main'));
  assert.ok(result.missing.includes('mail.acceptance'));
});

function syntheticReopen() {
  const m = syntheticComplete();
  m.be.runningImages = Object.fromEntries(['main', 'ai'].map(name => [name,
    { sourceSha: m.be.sourceSha, repoDigest: `sha256:${'5'.repeat(64)}`, readbackEvidence: 'synthetic-image' }]));
  m.be.flyway = { latestVersion: '63', failedCount: 0, checksumMatched: true, evidence: 'synthetic-flyway',
    migrations: m.be.sourceFileHashes.filter(file => /V6[0-3]__/.test(file.path)).map(file =>
      ({ version: file.path.match(/V(\d+)__/)[1], script: file.path.split('/').at(-1),
        checksum: 123, checksumMatched: true, success: true })) };
  m.fe.served = { indexSha256: m.fe.artifact.indexSha256, entrySha256: m.fe.artifact.entrySha256,
    fileManifestSha256: m.fe.artifact.fileManifestSha256, verifyRouteStatus: 200,
    onBehaviorAccepted: true, guardianBehaviorAccepted: true, evidence: 'synthetic-served' };
  m.mail.receiptEvidence = 'synthetic-inbox'; m.mail.explicitConfirmAndStatusEvidence = 'synthetic-confirm';
  m.mail.actualServiceSent = 1;
  Object.assign(m.recovery, { deletedUserDobLeakCount: 0, privateFreshBackupRef: 'synthetic-backup',
    backupIntegrityChecked: true, currentDeletionJournalRef: 'synthetic-private-journal' });
  m.window.roleSmokePassed = true; m.window.smokeEvidence = 'synthetic-roles';
  m.guardian.observed = { enabled: true, policyConfirmed: true,
    configurationDigest: m.guardian.proposedPolicy.configurationDigest,
    reviewerUserId: m.guardian.reviewer.userId, allInstancesEvidence: 'synthetic-all-instances' };
  Object.assign(m.guardian.cleanup, { overdueContacts: 0, overdueEvidence: 0, overdueEvents: 0,
    overdueOperations: 0, runtimeEvidence: 'synthetic-cleanup-runtime' });
  m.guardian.acceptance = { syntheticRoleAndRevocationEvidence: 'synthetic-roles-and-revoke',
    serviceWithoutAiAccepted: true, unapprovedAiDenied: true };
  return m;
}

test('reopen requires matching images/Flyway/FE, receipt, role smoke and zero withdrawn DOB leaks', () => {
  const m = syntheticReopen();
  assert.equal(checkManifest(m, 'reopen').metadataComplete, true);
  m.recovery.deletedUserDobLeakCount = 1;
  assert.ok(checkManifest(m, 'reopen').missing.includes('recovery.runtime'));
  m.recovery.deletedUserDobLeakCount = 0;
  m.fe.served.entrySha256 = '6'.repeat(64);
  assert.ok(checkManifest(m, 'reopen').missing.includes('fe.served'));
});

for (const [name, expected, corrupt] of [
  ['V60-only history', 'be.flyway', m => { m.be.flyway.latestVersion = '60'; }],
  ['V62 missing history', 'be.flyway', m => { m.be.flyway.migrations = m.be.flyway.migrations.filter(row => row.version !== '62'); }],
  ['V63 checksum mismatch', 'be.flyway', m => { m.be.flyway.migrations.find(row => row.version === '63').checksumMatched = false; }],
  ['another instance policy', 'guardian.runtimeSnapshot', m => { m.guardian.observed.configurationDigest = 'a'.repeat(64); }],
  ['runtime guardian OFF', 'guardian.runtimeSnapshot', m => { m.guardian.observed.enabled = false; }],
  ['unknown contact cleanup', 'guardian.runtimeCleanup', m => { m.guardian.cleanup.overdueContacts = null; }],
  ['overdue contact cleanup', 'guardian.runtimeCleanup', m => { m.guardian.cleanup.overdueContacts = 1; }],
  ['overdue approved evidence', 'guardian.runtimeCleanup', m => { m.guardian.cleanup.overdueEvidence = 1; }],
  ['overdue audit cleanup', 'guardian.runtimeCleanup', m => { m.guardian.cleanup.overdueEvents = 1; }],
  ['overdue idempotency cleanup', 'guardian.runtimeCleanup', m => { m.guardian.cleanup.overdueOperations = 1; }],
  ['AI scope denial unverified', 'guardian.runtimeAcceptance', m => { m.guardian.acceptance.unapprovedAiDenied = false; }],
  ['FE TEAM behavior unverified', 'fe.served', m => { m.fe.served.guardianBehaviorAccepted = false; }],
  ['only historical SDK test sent', 'mail.acceptance', m => { m.mail.actualServiceSent = 0; }],
  ['unknown service send count', 'mail.acceptance', m => { m.mail.actualServiceSent = null; }],
  ['service send budget exceeded', 'mail.acceptance', m => { m.mail.actualServiceSent = 2; }],
  ['three service actions with only one receipt', 'mail.acceptance', m => {
    m.mail.plannedMessages = 3; m.mail.plannedActions = ['SIGNUP_VERIFY', 'PASSWORD_RESET', 'WITHDRAWAL_COMPLETE'];
  }],
]) test(`reopen rejects ${name}`, () => {
  const m = syntheticReopen(); corrupt(m);
  const result = checkManifest(m, 'reopen');
  assert.ok(result.missing.includes(expected));
  assert.equal(result.executionAuthorized, false);
});

test('malformed or absent values fail without exposing field contents', () => {
  assert.equal(checkManifest(null).metadataComplete, false);
  assert.equal(checkManifest({}, 'unknown').metadataComplete, false);
  const m = syntheticComplete(); m.mail.observed.evidence = '';
  const result = checkManifest(m);
  assert.equal(JSON.stringify(result).includes('no-reply@uteum.com'), false);
});

test('CLI reads only the selected local manifest and blocks the unfilled example', () => {
  const script = fileURLToPath(new URL('./check-manifest.mjs', import.meta.url));
  const fixture = fileURLToPath(new URL('../../../docs/qa/launch-ops/readiness-manifest.example.json', import.meta.url));
  const result = spawnSync(process.execPath, [script, fixture], { encoding: 'utf8' });
  assert.equal(result.status, 2);
  assert.equal(JSON.parse(result.stdout).executionAuthorized, false);
});
