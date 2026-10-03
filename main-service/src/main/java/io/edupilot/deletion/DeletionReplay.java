package io.edupilot.deletion;

import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import io.edupilot.auth.RefreshTokenService;
import io.edupilot.auth.UserAccessGuard;
import io.edupilot.material.LearningMaterialRepository;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserWithdrawalHook;

@Service
public class DeletionReplay {
	private final DeletionIntentRepository intents;
	private final UserRepository users;
	private final LearningMaterialRepository materials;
	private final List<UserWithdrawalHook> hooks;
	private final RefreshTokenService tokens;
	private final UserAccessGuard access;
	private final DeletionJournal journal;
	public DeletionReplay(DeletionIntentRepository intents,UserRepository users,LearningMaterialRepository materials,
		List<UserWithdrawalHook> hooks,RefreshTokenService tokens,UserAccessGuard access,DeletionJournal journal) {
		this.intents=intents;this.users=users;this.materials=materials;this.hooks=hooks;this.tokens=tokens;this.access=access;
		this.journal=journal;
	}
	@Transactional(propagation=Propagation.REQUIRES_NEW)
	public Result reapply(Long id) {
		DeletionIntent intent=intents.findById(id).orElseThrow();
		if(intent.getKind()==DeletionKind.ACCOUNT) {
			var user=users.findByIdForUpdate(intent.sourceUserId()).orElse(null);
			if(user==null) { return Result.ABSENT; }
			if(!user.getCreatedAt().truncatedTo(ChronoUnit.MICROS).equals(intent.accountCreatedAt())) { return Result.IDENTITY_MISMATCH; }
			if(!user.isActive()&&user.getEmail().equals("deleted_"+user.getId())) { return Result.ALREADY_APPLIED; }
			if(!DeletionJournal.hash(user.getEmail()).equals(intent.originalEmailHash())) { return Result.IDENTITY_MISMATCH; }
			String restoredAvatar=user.getAvatarKey();
			user.withdraw(); users.flush(); hooks.forEach(hook->hook.onWithdraw(user.getId()));
			if(restoredAvatar!=null) { journal.recordAvatar(restoredAvatar); }
			tokens.revokeAll(user.getId()); access.invalidateAfterCommit(user.getId());
			return Result.APPLIED;
		}
		if(intent.getKind()==DeletionKind.ORIGINAL_PDF) {
			var material=materials.findByStorageKeyForUpdate(intent.sourceMaterialKey()).orElse(null);
			if(material==null) { return Result.ABSENT; }
			if(!material.isActive()) { return Result.ALREADY_APPLIED; }
			material.delete(); materials.flush(); return Result.APPLIED;
		}
		return Result.NOT_A_LOGICAL_TARGET;
	}
	public enum Result { APPLIED, ALREADY_APPLIED, ABSENT, IDENTITY_MISMATCH, NOT_A_LOGICAL_TARGET }
}
