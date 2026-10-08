package io.edupilot.deletion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import io.edupilot.material.LearningMaterial;

@Service
public class DeletionJournal {
	private final DeletionIntentRepository intents;
	private final DeletionJournalLockRepository locks;
	private final Clock clock;
	private final GeneralFileRetentionProperties generalFiles;
	public DeletionJournal(DeletionIntentRepository intents, DeletionJournalLockRepository locks, Clock clock,
		GeneralFileRetentionProperties generalFiles) {
		this.intents=intents; this.locks=locks; this.clock=clock; this.generalFiles=generalFiles;
	}
	@Transactional(propagation = Propagation.MANDATORY)
	public void recordMaterial(LearningMaterial material) {
		recordMaterial(material, false);
	}
	/** Owner-requested ordinary deletion only; withdrawal and legal/consent exceptions use no guessed period. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void recordRecoverableMaterial(LearningMaterial material) {
		recordMaterial(material, true);
	}
	private void recordMaterial(LearningMaterial material, boolean generalRecoverable) {
		lock();
		Instant now=clock.instant().truncatedTo(ChronoUnit.MICROS);
		Instant deadline=generalRecoverable ? generalFiles.deadline(now) : null;
		String key=material.getStorageKey();
		put(new DeletionSnapshot(DeletionKind.ORIGINAL_PDF,key,key,null,null,null,now,deadline),null);
		put(new DeletionSnapshot(DeletionKind.RENDERED_PAGES,key,key,null,null,null,now,deadline),null);
		if(material.getXaiFileId()!=null&&!material.getXaiFileId().isBlank()) {
			put(new DeletionSnapshot(DeletionKind.EXTERNAL_AI,material.getXaiFileId(),key,null,null,null,now,null),null);
		}
	}
	@Transactional(propagation = Propagation.MANDATORY)
	public void recordAccount(Long userId,Instant createdAt,String originalEmail) {
		lock();
		Instant created=createdAt.truncatedTo(ChronoUnit.MICROS);
		String emailHash=hash(originalEmail);
		put(new DeletionSnapshot(DeletionKind.ACCOUNT,accountKey(userId,created,emailHash),
			null,userId,created,emailHash,clock.instant().truncatedTo(ChronoUnit.MICROS),null),null);
	}
	@Transactional
	public void recordExternal(String fileId) {
		if(fileId==null||fileId.isBlank()) { return; }
		lock();
		put(new DeletionSnapshot(DeletionKind.EXTERNAL_AI,fileId,null,null,null,null,
			clock.instant().truncatedTo(ChronoUnit.MICROS),null),null);
	}
	@Transactional(propagation=Propagation.MANDATORY)
	public void recordAvatar(String key) {
		lock();
		put(new DeletionSnapshot(DeletionKind.AVATAR,key,null,null,null,null,
			clock.instant().truncatedTo(ChronoUnit.MICROS),null),null);
	}
	@Transactional(propagation=Propagation.MANDATORY)
	public boolean rejectsExternalAttachment(String fileId) {
		if(fileId==null) { return false; }
		lock();
		return intents.findByKeyHashForUpdate(hash(DeletionKind.EXTERNAL_AI.name()+"\n"+fileId)).isPresent();
	}
	@Transactional(propagation=Propagation.MANDATORY)
	public boolean materialIsTombstoned(String storageKey) {
		lock();
		return intents.findByKeyHashForUpdate(hash(DeletionKind.ORIGINAL_PDF.name()+"\n"+storageKey)).isPresent();
	}
	@Transactional(readOnly=true)
	public ExportPage exportPage(long afterId,int limit) {
		if(afterId<0||limit<1||limit>1000) { throw new IllegalArgumentException("Invalid journal page"); }
		List<DeletionIntent> rows=intents.findByIdGreaterThanOrderById(afterId,PageRequest.of(0,limit+1));
		List<DeletionIntent> page=rows.subList(0,Math.min(limit,rows.size()));
		return new ExportPage(page.stream().map(DeletionIntent::snapshot).toList(),
			page.isEmpty()?afterId:page.get(page.size()-1).getId(),rows.size()>limit);
	}
	public record ExportPage(List<DeletionSnapshot> entries,long nextId,boolean hasNext) {}
	/** Operator-controlled snapshots must be preserved separately from a backup older than deletion. */
	@Transactional(propagation=Propagation.REQUIRES_NEW)
	public List<Long> importForRestore(List<DeletionSnapshot> snapshots,String restoreEpoch) {
		if(snapshots==null||snapshots.size()>1000) { throw new IllegalArgumentException("Invalid journal batch"); }
		if(restoreEpoch==null||!restoreEpoch.matches("[A-Za-z0-9_-]{1,100}")) {
			throw new IllegalArgumentException("Invalid restore epoch");
		}
		snapshots.forEach(DeletionSnapshot::validate);
		lock();
		return snapshots.stream().map(snapshot->put(snapshot,restoreEpoch).getId()).toList();
	}
	private DeletionIntent put(DeletionSnapshot snapshot,String restoreEpoch) {
		snapshot.validate();
		String key=hash(snapshot.kind().name()+"\n"+snapshot.resourceKey());
		DeletionIntent existing=intents.findByKeyHashForUpdate(key).orElse(null);
		if(existing!=null) {
			if(!existing.resourceKey().equals(snapshot.resourceKey())||existing.getKind()!=snapshot.kind()) {
				throw new IllegalStateException("Deletion journal identity conflict");
			}
			if(restoreEpoch!=null) { existing.restore(snapshot.requestedAt(),snapshot.retainUntil(),restoreEpoch); }
			return existing;
		}
		DeletionIntent created=DeletionIntent.create(snapshot);
		if(restoreEpoch!=null) { created.restore(snapshot.requestedAt(),snapshot.retainUntil(),restoreEpoch); }
		return intents.saveAndFlush(created);
	}
	private void lock() {
		locks.lockSingleton().orElseThrow(()->new IllegalStateException("Deletion journal is unavailable"));
	}
	static String accountKey(Long id,Instant created,String emailHash) {
		return hash(id+"\n"+created.truncatedTo(ChronoUnit.MICROS)+"\n"+emailHash);
	}
	public static String hash(String value) {
		try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
		catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
	}
}
