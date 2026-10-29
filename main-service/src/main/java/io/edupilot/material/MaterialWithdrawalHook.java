package io.edupilot.material;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import io.edupilot.deletion.DeletionJournal;

import io.edupilot.user.UserWithdrawalHook;

@Component
public class MaterialWithdrawalHook implements UserWithdrawalHook {

	private final LearningMaterialRepository materialRepository;
	private final DeletionJournal deletionJournal;

	public MaterialWithdrawalHook(
		LearningMaterialRepository materialRepository,
		DeletionJournal deletionJournal
	) {
		this.materialRepository = materialRepository;
		this.deletionJournal = deletionJournal;
	}

	@Override
	@Transactional(propagation=Propagation.MANDATORY)
	public void onWithdraw(Long userId) {
		for (LearningMaterial material:materialRepository.findOwnedActiveForUpdate(userId)) {
			material.delete();
			deletionJournal.recordMaterial(material);
		}
		materialRepository.flush();
	}
}
