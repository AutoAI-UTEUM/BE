import { readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

// Metadata lint only. No network, child processes, credentials, SQL, mail or deployment.
const SHA = /^[a-f0-9]{40}$/;
const HASH = /^[a-f0-9]{64}$/;
const DIGEST = /^sha256:[a-f0-9]{64}$/;
const BASELINE = '3343e608fa80dd6bdb5888454ad6fac5b09461ad';
const MIGRATIONS = {
  '60': 'V60__birthdate_correction_intake.sql',
  '61': 'V61__guardian_team_review.sql',
  '62': 'V62__guardian_team_access_generation.sql',
  '63': 'V63__guardian_team_mail_binding.sql',
};
const GUARDIAN_FE_CHECKS = ['intake', 'fragmentView', 'explicitDeclaration', 'currentGenerationReply',
  'adminReview', 'scopeSeparation', 'revocationAndExpiry'];
const SERVICE_MAIL_ACTIONS = ['SIGNUP_VERIFY', 'SIGNUP_REISSUE', 'PASSWORD_RESET', 'WITHDRAWAL_COMPLETE'];
const CAPABILITIES = 'reports,password-reset,exam-attempt-drafts,exam-learner-regrade,policy-consent';
const text = value => typeof value === 'string' && value.trim().length > 0;
const positive = value => Number.isSafeInteger(value) && value > 0;
const https = value => {
  if (!text(value)) return false;
  try { const url = new URL(value); return url.protocol === 'https:' && !url.username && !url.password
    && !url.search && !url.hash; } catch { return false; }
};

export function checkManifest(manifest, phase = 'prepare') {
  const missing = [];
  const need = (condition, name) => { if (!condition) missing.push(name); };
  need(['prepare', 'reopen'].includes(phase), 'phase');
  need(manifest?.version === 'launch-ops-v2-team', 'version');
  need(manifest?.environment === 'dev', 'environment');
  const be = manifest?.be ?? {};
  const fe = manifest?.fe ?? {};
  const mail = manifest?.mail ?? {};
  const window = manifest?.window ?? {};
  const recovery = manifest?.recovery ?? {};
  const guardian = manifest?.guardian ?? {};
  need(SHA.test(be.sourceSha ?? ''), 'be.sourceSha');
  need(be.reviewedBaselineSha === BASELINE && be.containsReviewedBaseline === true, 'be.containsReviewedBaseline');
  const hashes = Array.isArray(be.sourceFileHashes) ? be.sourceFileHashes : [];
  need(Object.values(MIGRATIONS).every(script => hashes.some(file =>
    file?.path === `main-service/src/main/resources/db/migration/${script}` && HASH.test(file?.sha256 ?? ''))),
    'be.migrationSourceHashes');
  for (const name of ['main', 'ai']) {
    const ci = be.ci?.[name];
    need(ci?.headSha === be.sourceSha && ci?.conclusion === 'success' && text(ci?.url), `be.ci.${name}`);
  }
  need(SHA.test(fe.sourceSha ?? '') && fe.contractReviewComplete === true, 'fe.sourceAndContract');
  need(fe.ci?.headSha === fe.sourceSha && fe.ci?.conclusion === 'success' && text(fe.ci?.url), 'fe.ci');
  need(fe.settings?.apiBaseUrl === '/api'
    && typeof fe.settings?.capabilities === 'string'
    && fe.settings.capabilities.split(',').sort().join(',') === CAPABILITIES.split(',').sort().join(',')
    && text(fe.settings?.googleClientIdHandle), 'fe.settings.api');
  need(text(fe.reviewedReadinessValue) && fe.settings?.authReadiness === fe.reviewedReadinessValue
    && text(fe.settings?.injectionEvidence), 'fe.settings.readiness');
  need(fe.guardianContract?.sourceSha === fe.sourceSha && text(fe.guardianContract?.reviewEvidence)
    && GUARDIAN_FE_CHECKS.every(name => fe.guardianContract?.[name] === true), 'fe.guardianContract');
  need(text(fe.artifact?.buildRunUrl) && fe.artifact?.sourceSha === fe.sourceSha
    && HASH.test(fe.artifact?.indexSha256 ?? '') && HASH.test(fe.artifact?.entrySha256 ?? '')
    && HASH.test(fe.artifact?.fileManifestSha256 ?? '') && /^\/assets\/[^?#]+\.js$/.test(fe.artifact?.entryPath ?? '')
    && Array.isArray(fe.artifact?.styles) && fe.artifact.styles.length > 0
    && fe.artifact.styles.every(style => /^\/assets\/[^?#]+\.css$/.test(style?.path ?? '')
      && HASH.test(style?.sha256 ?? '')), 'fe.artifact');
  need(mail.observed?.provider === 'ses' && mail.observed?.enabled === true
    && mail.observed?.from === 'no-reply@uteum.com' && mail.observed?.region === 'ap-northeast-2'
    && mail.observed?.baseUrl === 'https://dev.uteum.com'
    && text(mail.observed?.evidence), 'mail.observed');
  need(mail.ses?.sendingEnabled === true && mail.ses?.identityVerified === true
    && mail.ses?.region === mail.observed?.region && mail.ses?.senderFrom === mail.observed?.from
    && text(mail.ses?.accountEvidence) && text(mail.ses?.identityEvidence) && text(mail.ses?.runtimeRoleEvidence), 'mail.ses');
  need(mail.ses?.productionAccessEnabled === true || (mail.ses?.productionAccessEnabled === false
    && mail.ses?.approvedRecipientVerified === true), 'mail.sandboxRecipient');
  need(text(mail.keyContinuityEvidence) && text(mail.outboxDecisionEvidence)
    && mail.otherProducerIsolationConfirmed === true, 'mail.outboxAndProducers');
  need(text(mail.approvalRef) && text(mail.approvedInboxHandle) && Number.isInteger(mail.plannedMessages)
    && mail.plannedMessages >= 1 && mail.plannedMessages <= 5 && text(mail.incrementalBudgetApproval), 'mail.approvedScope');
  const trial = mail.sdkTrial ?? {};
  need(trial.identityCalls === 1 && trial.testSendCalls === 1 && trial.inboxConfirmed === true
    && trial.budgetsConsumed === true && trial.rerunPermitted === false
    && SHA.test(trial.sourceSha ?? '') && HASH.test(trial.jarSha256 ?? '')
    && text(trial.identityEvidence) && text(trial.sendEvidence) && text(trial.sendApprovalRef), 'mail.sdkTrialHistory');
  need(mail.approvedOperation === 'SERVICE_ACCEPTANCE' && text(mail.approvalRef)
    && mail.approvalRef !== trial.sendApprovalRef && mail.incrementalBudgetApproval !== trial.sendApprovalRef
    && Array.isArray(mail.plannedActions) && mail.plannedActions.length > 0
    && mail.plannedActions.includes('SIGNUP_VERIFY')
    && new Set(mail.plannedActions).size === mail.plannedActions.length
    && mail.plannedActions.every(action => SERVICE_MAIL_ACTIONS.includes(action))
    && mail.plannedMessages >= mail.plannedActions.length, 'mail.separateServiceApproval');
  need(recovery.strategy === 'roll-forward-preserve-gates-and-v60-v63-hooks'
    && recovery.hookPreserved === true && text(recovery.syntheticEvidence)
    && recovery.teamReadersPreserved === true && recovery.consentEpochAndDigestPreserved === true
    && text(recovery.beforeMigrationCancelPlan) && text(recovery.backupPlan), 'recovery.plan');
  need(text(window.owner) && text(window.feOwner) && text(window.mailOwner) && text(window.approvalRef)
    && text(window.pauseScopeAndBudget) && text(window.cancelOwner)
    && text(window.reopenCriteria), 'window.review');
  const policy = guardian.proposedPolicy ?? {};
  need(guardian.mode === 'TEAM_REVIEW' && policy.enabled === true && policy.policyConfirmed === true
    && text(policy.approvalRef) && text(policy.noticeVersion) && /^[A-Za-z0-9_.-]{1,100}$/.test(policy.noticeVersion)
    && HASH.test(policy.noticeDigest ?? '') && HASH.test(policy.configurationDigest ?? '')
    && https(policy.noticeUrl) && https(policy.portalBaseUrl)
    && ['collectionItemsReviewRef', 'purposesReviewRef', 'retentionReviewRef', 'refusalReviewRef',
      'relationshipReviewRef'].every(name => text(policy[name])), 'guardian.reviewedPolicy');
  need(Array.isArray(policy.requiredScopes) && policy.requiredScopes.length === 1
    && policy.requiredScopes[0] === 'SERVICE'
    && (policy.optionalAiScope === null || (policy.optionalAiScope === 'EXTERNAL_AI'
      && text(policy.optionalConsentReviewRef)))
    && (policy.optionalAiScope !== null || policy.optionalConsentReviewRef === null), 'guardian.scopeSeparation');
  need(positive(policy.linkTtlSeconds) && policy.linkTtlSeconds <= 86400
    && positive(policy.requestTtlSeconds) && policy.requestTtlSeconds <= 432000
    && policy.linkTtlSeconds <= policy.requestTtlSeconds
    && positive(policy.approvedEvidenceRetentionSeconds) && positive(policy.approvalValiditySeconds)
    && policy.approvedEvidenceRetentionSeconds >= policy.approvalValiditySeconds, 'guardian.reviewedDurations');
  const reply = guardian.reply ?? {};
  const reviewer = guardian.reviewer ?? {};
  need(reply.channel === 'EMAIL_REPLY' && reply.selectedInboxHandle === 'GUARDIAN_REPLY_INBOX'
    && text(reply.selectionRef) && text(reply.privateBindingEvidence)
    && reviewer.selectedAccountHandle === 'GUARDIAN_REVIEW_ACCOUNT'
    && reviewer.sameAccountAsReplyInbox === true && text(reviewer.selectionRef), 'guardian.selectedReplyAndReviewer');
  need(reviewer.matchedAccountCount === 1 && positive(reviewer.userId)
    && reviewer.role === 'ADMIN' && reviewer.status === 'ACTIVE' && reviewer.readOnlyCheck === true
    && text(reviewer.identityEvidence) && Array.isArray(policy.reviewerIds)
    && policy.reviewerIds.length === 1 && policy.reviewerIds[0] === reviewer.userId, 'guardian.existingActiveAdmin');
  need(text(guardian.cleanup?.syntheticEvidence) && text(guardian.cleanup?.externalMailboxPlan)
    && text(guardian.cleanup?.backupRestoreReplayPlan) && text(guardian.cleanup?.monitoringPlan), 'guardian.cleanupPlan');
  if (phase === 'reopen') {
    for (const name of ['main', 'ai']) {
      const image = be.runningImages?.[name];
      need(image?.sourceSha === be.sourceSha && DIGEST.test(image?.repoDigest ?? '')
        && text(image?.readbackEvidence), `be.runningImages.${name}`);
    }
    need(be.flyway?.latestVersion === '63' && be.flyway?.failedCount === 0
      && be.flyway?.checksumMatched === true && text(be.flyway?.evidence)
      && Array.isArray(be.flyway?.migrations) && Object.entries(MIGRATIONS).every(([version, script]) =>
        be.flyway.migrations.some(row => row?.version === version && row?.script === script
          && row?.success === true && Number.isSafeInteger(row?.checksum) && row?.checksumMatched === true)), 'be.flyway');
    need(fe.served?.indexSha256 === fe.artifact?.indexSha256
      && fe.served?.entrySha256 === fe.artifact?.entrySha256
      && fe.served?.fileManifestSha256 === fe.artifact?.fileManifestSha256 && fe.served?.verifyRouteStatus === 200
      && fe.served?.onBehaviorAccepted === true && fe.served?.guardianBehaviorAccepted === true
      && text(fe.served?.evidence), 'fe.served');
    need(text(mail.receiptEvidence) && text(mail.explicitConfirmAndStatusEvidence)
      && positive(mail.actualServiceSent) && mail.actualServiceSent <= mail.plannedMessages
      && Array.isArray(mail.plannedActions) && mail.actualServiceSent >= mail.plannedActions.length, 'mail.acceptance');
    need(recovery.deletedUserDobLeakCount === 0 && text(recovery.privateFreshBackupRef)
      && recovery.backupIntegrityChecked === true && text(recovery.currentDeletionJournalRef), 'recovery.runtime');
    need(window.roleSmokePassed === true && text(window.smokeEvidence), 'window.smoke');
    need(guardian.observed?.enabled === true && guardian.observed?.policyConfirmed === true
      && guardian.observed?.configurationDigest === policy.configurationDigest
      && guardian.observed?.reviewerUserId === reviewer.userId && text(guardian.observed?.allInstancesEvidence),
      'guardian.runtimeSnapshot');
    need(guardian.cleanup?.overdueContacts === 0 && guardian.cleanup?.overdueEvidence === 0
      && guardian.cleanup?.overdueEvents === 0 && guardian.cleanup?.overdueOperations === 0
      && text(guardian.cleanup?.runtimeEvidence), 'guardian.runtimeCleanup');
    need(text(guardian.acceptance?.syntheticRoleAndRevocationEvidence)
      && guardian.acceptance?.serviceWithoutAiAccepted === true
      && guardian.acceptance?.unapprovedAiDenied === true, 'guardian.runtimeAcceptance');
  }
  return { phase, metadataComplete: missing.length === 0, missing, executionAuthorized: false,
    note: 'Local metadata check only; independent operator approval and actual evidence are still required.' };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const result = checkManifest(JSON.parse(readFileSync(process.argv[2], 'utf8')), process.argv[3] ?? 'prepare');
    console.log(JSON.stringify(result, null, 2));
    process.exitCode = result.metadataComplete ? 0 : 2;
  } catch {
    console.error('Manifest could not be read or parsed. No operation was performed.');
    process.exitCode = 2;
  }
}
