package io.edupilot.material;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import io.edupilot.guardian.GuardianConsentFence;
import io.edupilot.ai.AiClient;
import io.edupilot.auth.EmailVerificationGate;
import io.edupilot.material.MaterialXaiFileBackfillPersistenceService.UploadClaim;
import io.edupilot.material.storage.FileStorage;

@Service
public class MaterialXaiFileBackfillService {

	private final GuardianConsentFence consentFence;

	private static final Logger log = LoggerFactory.getLogger(
		MaterialXaiFileBackfillService.class
	);

	private final EmailVerificationGate emailVerification;
	private final MaterialXaiFileBackfillPersistenceService persistenceService;
	private final FileStorage fileStorage;
	private final AiClient aiClient;
	private final MaterialXaiFileLifecycleService lifecycleService;

	public MaterialXaiFileBackfillService(
		MaterialXaiFileBackfillPersistenceService persistenceService,
		FileStorage fileStorage,
		AiClient aiClient,
		MaterialXaiFileLifecycleService lifecycleService,
		EmailVerificationGate emailVerification,
		GuardianConsentFence consentFence
	) {
		this.consentFence = consentFence;
		this.emailVerification = emailVerification;
		this.persistenceService = persistenceService;
		this.fileStorage = fileStorage;
		this.aiClient = aiClient;
		this.lifecycleService = lifecycleService;
	}

	public void backfill(Long materialId) {
		Optional<UploadClaim> claim = persistenceService.claim(materialId);
		if (claim.isEmpty()) {
			return;
		}

		String uploadedFileId = null;
		try {
			GuardianConsentFence.Snapshot consent = consentFence.captureMaterialOwner(materialId);
			emailVerification.requireMaterialOwnerVerified(materialId);
			Resource resource = fileStorage.load(claim.get().storageKey());
			uploadedFileId = aiClient.uploadFile(resource);
			String resultFileId = uploadedFileId;
			if (!consentFence.complete(consent, () -> persistenceService.attachIfStillEligible(
				claim.get().materialId(), resultFileId))) {
				lifecycleService.deleteAfterCommit(uploadedFileId);
				return;
			}
			log.atInfo()
				.addKeyValue("materialId", materialId)
				.addKeyValue("fileId", uploadedFileId)
				.log("Material xAI file backfill completed");
		} catch (RuntimeException exception) {
			if (uploadedFileId != null) {
				lifecycleService.deleteAfterCommit(uploadedFileId);
			}
			log.atWarn()
				.addKeyValue("materialId", materialId)
				.addKeyValue("reason", exception.getClass().getSimpleName())
				.log("Material xAI file backfill deferred");
		}
	}
}
