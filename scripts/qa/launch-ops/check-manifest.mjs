import { readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

// Metadata lint only. No network, child processes, credentials, SQL, mail or deployment.
const SHA = /^[a-f0-9]{40}$/;
const HASH = /^[a-f0-9]{64}$/;
const DIGEST = /^sha256:[a-f0-9]{64}$/;
const BASELINE = '0a8f7bd99db13d20ddcf392766b2bd69ab4754a3';
const CAPABILITIES = 'reports,password-reset,exam-attempt-drafts,exam-learner-regrade,policy-consent';
const text = value => typeof value === 'string' && value.trim().length > 0;

export function checkManifest(manifest, phase = 'prepare') {
  const missing = [];
  const need = (condition, name) => { if (!condition) missing.push(name); };
  need(['prepare', 'reopen'].includes(phase), 'phase');
  need(manifest?.version === 'launch-ops-v1', 'version');
  need(manifest?.environment === 'dev', 'environment');
  const be = manifest?.be ?? {};
  const fe = manifest?.fe ?? {};
  const mail = manifest?.mail ?? {};
  const window = manifest?.window ?? {};
  const recovery = manifest?.recovery ?? {};
  need(SHA.test(be.sourceSha ?? ''), 'be.sourceSha');
  need(be.reviewedBaselineSha === BASELINE && be.containsReviewedBaseline === true, 'be.containsReviewedBaseline');
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
  need(text(fe.artifact?.buildRunUrl) && fe.artifact?.sourceSha === fe.sourceSha
    && HASH.test(fe.artifact?.indexSha256 ?? '') && HASH.test(fe.artifact?.entrySha256 ?? '')
    && HASH.test(fe.artifact?.fileManifestSha256 ?? '') && /^\/assets\/[^?#]+\.js$/.test(fe.artifact?.entryPath ?? '')
    && Array.isArray(fe.artifact?.styles) && fe.artifact.styles.length > 0
    && fe.artifact.styles.every(style => /^\/assets\/[^?#]+\.css$/.test(style?.path ?? '')
      && HASH.test(style?.sha256 ?? '')), 'fe.artifact');
  need(mail.observed?.provider === 'ses' && mail.observed?.enabled === true
    && text(mail.observed?.from) && text(mail.observed?.region) && mail.observed?.baseUrl === 'https://dev.uteum.com'
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
  need(recovery.strategy === 'roll-forward-preserve-gates-and-v60-hook'
    && recovery.hookPreserved === true && text(recovery.syntheticEvidence)
    && text(recovery.beforeMigrationCancelPlan) && text(recovery.backupPlan), 'recovery.plan');
  need(text(window.owner) && text(window.feOwner) && text(window.mailOwner) && text(window.approvalRef)
    && text(window.pauseScopeAndBudget) && text(window.cancelOwner)
    && text(window.reopenCriteria), 'window.review');
  if (phase === 'reopen') {
    for (const name of ['main', 'ai']) {
      const image = be.runningImages?.[name];
      need(image?.sourceSha === be.sourceSha && DIGEST.test(image?.repoDigest ?? '')
        && text(image?.readbackEvidence), `be.runningImages.${name}`);
    }
    need(be.flyway?.latestVersion === '60' && be.flyway?.failedCount === 0
      && be.flyway?.checksumMatched === true && text(be.flyway?.evidence), 'be.flyway');
    need(fe.served?.indexSha256 === fe.artifact?.indexSha256
      && fe.served?.entrySha256 === fe.artifact?.entrySha256
      && fe.served?.fileManifestSha256 === fe.artifact?.fileManifestSha256 && fe.served?.verifyRouteStatus === 200
      && fe.served?.onBehaviorAccepted === true && text(fe.served?.evidence), 'fe.served');
    need(text(mail.receiptEvidence) && text(mail.explicitConfirmAndStatusEvidence), 'mail.acceptance');
    need(recovery.deletedUserDobLeakCount === 0 && text(recovery.privateFreshBackupRef)
      && recovery.backupIntegrityChecked === true && text(recovery.currentDeletionJournalRef), 'recovery.runtime');
    need(window.roleSmokePassed === true && text(window.smokeEvidence), 'window.smoke');
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
