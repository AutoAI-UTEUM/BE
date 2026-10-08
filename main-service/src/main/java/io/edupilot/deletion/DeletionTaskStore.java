package io.edupilot.deletion;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import io.edupilot.material.LearningMaterialRepository;
import io.edupilot.material.MaterialStatus;

@Service
public class DeletionTaskStore {
	private final DeletionIntentRepository intents;
	private final LearningMaterialRepository materials;
	private final DeletionProperties policy;
	private final Clock clock;
	public DeletionTaskStore(DeletionIntentRepository intents,LearningMaterialRepository materials,DeletionProperties policy,Clock clock) {
		this.intents=intents;this.materials=materials;this.policy=policy;this.clock=clock;
	}
	@Transactional(propagation=Propagation.REQUIRES_NEW,readOnly=true)
	public List<Long> candidates() {
		List<DeletionKind> kinds=Arrays.stream(DeletionKind.values()).filter(policy::permits).toList();
		return kinds.isEmpty()?List.of():intents.candidates(clock.instant(),kinds,PageRequest.of(0,policy.batchSize()));
	}
	@Transactional(propagation=Propagation.REQUIRES_NEW)
	public DeletionClaim claim(Long id) {
		DeletionIntentRepository.Target snapshot=intents.target(id).orElse(null);
		if(snapshot==null||snapshot.getKind()==DeletionKind.ACCOUNT) { return null; }
		// Material first, then intent: matches material deletion's lock order.
		if(snapshot.getSourceMaterialKey()!=null) {
			materials.findByStorageKeyForUpdate(snapshot.getSourceMaterialKey()).ifPresent(material->{
				if(material.isActive()&&intents.existsByKeyHash(DeletionJournal.hash(
					DeletionKind.ORIGINAL_PDF.name()+"\n"+material.getStorageKey()))) { material.delete(); materials.flush(); }
			});
		}
		DeletionIntent task=intents.findForUpdate(id).orElse(null);
		if(task==null||!task.keyHash().equals(snapshot.getKeyHash())||task.getStatus()==DeletionStatus.DONE
			||task.getStatus()==DeletionStatus.FAILED) { return null; }
		Instant now=clock.instant();
		if(task.getStatus()==DeletionStatus.LEASED) {
			if(task.leaseUntil().isAfter(now)) { return null; }
			task.recover(now);
		}
		if(!policy.permits(task.getKind())) { task.waitForPolicy(); return null; }
		if(!policy.policyVersion().equals(task.policyVersion())||task.getStatus()==DeletionStatus.POLICY_PENDING) {
			task.activate(policy);
		}
		if(task.getRetainUntil().isAfter(now)) { task.waitUntilRetention(); return null; }
		if(task.nextAttemptAt().isAfter(now)) { return null; }
		if(task.getAttempts()>=policy.maxAttempts()) { task.exhaust(); return null; }
		if(inUse(task)) { task.referenceInUse(now.plus(policy.retryDelay())); return null; }
		task.claim(now,policy.leaseDuration());
		return task.claimSnapshot();
	}
	@Transactional(propagation=Propagation.REQUIRES_NEW)
	public boolean mayExecute(DeletionClaim claim) {
		DeletionIntent task=intents.findForUpdate(claim.id()).orElse(null);
		if(task==null||!task.owns(claim,clock.instant())) { return false; }
		if(!policy.permits(task.getKind())||!policy.policyVersion().equals(task.policyVersion())) {
			task.waitForPolicy(); return false;
		}
		if(inUse(task)) { task.referenceInUse(clock.instant().plus(policy.retryDelay())); return false; }
		return true;
	}
	@Transactional(propagation=Propagation.REQUIRES_NEW)
	public boolean complete(DeletionClaim claim) {
		DeletionIntent task=intents.findForUpdate(claim.id()).orElse(null);
		if(task==null||!task.owns(claim,clock.instant())) { return false; }
		task.done(); return true;
	}
	@Transactional(propagation=Propagation.REQUIRES_NEW)
	public void fail(DeletionClaim claim) {
		DeletionIntent task=intents.findForUpdate(claim.id()).orElse(null);
		if(task!=null&&task.owns(claim,clock.instant())) {
			task.failed(clock.instant(),policy,claim.kind()==DeletionKind.EXTERNAL_AI?"EXTERNAL_DELETE_FAILED":"STORAGE_DELETE_FAILED");
		}
	}
	/** Explicit operator retry after investigating a terminal failure; no public endpoint. */
	@Transactional(propagation=Propagation.REQUIRES_NEW)
	public boolean retryFailed(Long id) {
		DeletionIntent task=intents.findForUpdate(id).orElse(null);
		if(task==null||task.getStatus()!=DeletionStatus.FAILED) { return false; }
		task.restore(task.requestedAt(),task.getRetainUntil(),"retry_"+java.util.UUID.randomUUID()); return true;
	}
	private boolean inUse(DeletionIntent task) {
		return task.getKind()==DeletionKind.EXTERNAL_AI
			&& materials.existsByXaiFileIdAndStatus(task.resourceKey(),MaterialStatus.ACTIVE);
	}
}
