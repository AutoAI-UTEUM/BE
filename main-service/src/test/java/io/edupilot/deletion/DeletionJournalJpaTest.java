package io.edupilot.deletion;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.io.ByteArrayInputStream;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import io.edupilot.ai.AiClient;
import io.edupilot.ai.dto.ExtractedPage;
import io.edupilot.mail.EmailService;
import io.edupilot.material.*;
import io.edupilot.material.storage.FileStorage;
import io.edupilot.user.*;

@SpringBootTest(properties={
 "spring.datasource.url=jdbc:h2:mem:deletion-journal;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
 "spring.datasource.username=sa","spring.datasource.password=","spring.datasource.driver-class-name=org.h2.Driver",
 "spring.flyway.enabled=false","spring.jpa.hibernate.ddl-auto=create-drop",
 "edupilot.cors.allowed-origins=http://localhost:5173","edupilot.ai.base-url=http://localhost:8000",
 "edupilot.ai.internal-token=synthetic-internal-token",
 "edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
 "edupilot.storage.root-directory=build/test-storage/deletion-journal"
})
@ActiveProfiles("jpa-context")
class DeletionJournalJpaTest {
 @DynamicPropertySource static void isolatedMysql(DynamicPropertyRegistry registry) {
  String url=System.getenv("DELETION_JOURNAL_MYSQL_URL"); if(url==null||url.isBlank()) return;
  if(!url.matches("^jdbc:mysql://127\\.0\\.0\\.1:33316/deletion_journal_synthetic(?:\\?.*)?$"))
   throw new IllegalArgumentException("Deletion tests require the disposable loopback database");
  registry.add("spring.datasource.url",()->url); registry.add("spring.datasource.username",()->"root");
  registry.add("spring.datasource.password",()->""); registry.add("spring.datasource.driver-class-name",()->"com.mysql.cj.jdbc.Driver");
 }
 @Autowired DeletionJournal journal;
 @Autowired DeletionIntentRepository intents;
 @Autowired DeletionJournalLockRepository locks;
 @Autowired DeletionTaskStore store;
 @Autowired DeletionRestoreService restore;
 @Autowired DeletionReplay replay;
 @Autowired UserRepository users;
 @Autowired UserService userService;
 @Autowired LearningMaterialRepository materials;
 @Autowired MaterialService materialService;
 @Autowired MaterialPageRepository pages;
 @Autowired MaterialExtractionPersistenceService extraction;
 @Autowired MaterialXaiFileBackfillPersistenceService backfill;
 @Autowired MaterialRenderStorage render;
 @Autowired PlatformTransactionManager transactions;
 @Autowired MutableClock clock;
 @Autowired JdbcTemplate jdbc;
 @MockitoBean AiClient ai;
 @MockitoBean EmailService mail;
 @MockitoBean FileStorage files;
 @MockitoBean DeletionProperties policy;
 @MockitoBean DeletionWorker scheduledWorker;
 Map<DeletionKind,Integer> days;
 boolean enabled;
 @BeforeEach void prepareSyntheticState() {
  if(System.getenv("DELETION_JOURNAL_MYSQL_URL")!=null) {
   assertThat(jdbc.queryForObject("select @@port",Integer.class)).isEqualTo(33316);
   assertThat(jdbc.queryForObject("select database()",String.class)).isEqualTo("deletion_journal_synthetic");
  }
  pages.deleteAll(); materials.deleteAll(); users.deleteAll(); intents.deleteAll();
  if(!locks.existsById(1)) locks.saveAndFlush(DeletionJournalLock.initial());
  clock.now.set(Instant.now().plusSeconds(60)); days=new EnumMap<>(DeletionKind.class); enabled=false;
  when(policy.enabled()).thenAnswer(i->enabled);
  when(policy.permits(any())).thenAnswer(i->enabled&&days.containsKey(i.getArgument(0)));
  when(policy.days(any())).thenAnswer(i->days.get(i.getArgument(0)));
  when(policy.policyVersion()).thenReturn("SYNTHETIC_POLICY");
  when(policy.leaseDuration()).thenReturn(Duration.ofSeconds(10));
  when(policy.retryDelay()).thenReturn(Duration.ofSeconds(2));
  when(policy.maxAttempts()).thenReturn(2); when(policy.batchSize()).thenReturn(25);
 }
 @Test void unknownRetentionPreservesPdfRendersAvatarAndExternalFiles() {
  var f=fixture("synthetic-external");
  tx(()->{var u=users.findByIdForUpdate(f.user()).orElseThrow(); u.replaceAvatar("avatars/synthetic.png");});
  userService.withdrawGoogle(f.user(),f.subject());
  assertThat(intents.findAll()).hasSize(5);
  assertThat(intents.findAll().stream().filter(i->i.getKind()!=DeletionKind.ACCOUNT))
   .allSatisfy(i->assertThat(i.getStatus()).isEqualTo(DeletionStatus.POLICY_PENDING));
  worker().tick(); intents.findAll().forEach(i->worker().process(i.getId()));
  verifyNoInteractions(files,ai); assertThat(store.candidates()).isEmpty();
  assertThat(materials.findById(f.material()).orElseThrow().isActive()).isFalse();
 }
 @Test void rollbackPreservesAccountMaterialAndHasNoCommittedTombstone() {
  var f=fixture("synthetic-external");
  new TransactionTemplate(transactions).executeWithoutResult(status->{userService.withdrawGoogle(f.user(),f.subject()); status.setRollbackOnly();});
  assertThat(users.findById(f.user()).orElseThrow().isActive()).isTrue();
  assertThat(materials.findById(f.material()).orElseThrow().isActive()).isTrue();
  assertThat(intents.count()).isZero(); verifyNoInteractions(files,ai);
 }
 @Test void journalUnavailableRollsBackLogicalDeletion() {
  var f=fixture(null); locks.deleteAll();
  assertThatThrownBy(()->materialService.delete(f.user(),f.material())).isInstanceOf(IllegalStateException.class);
  assertThat(materials.findById(f.material()).orElseThrow().isActive()).isTrue(); verifyNoInteractions(files,ai);
 }
 @Test void journalUnavailableRollsBackAccountAndAllOwnedMaterialChanges() {
  var f=fixture("synthetic-failure");locks.deleteAll();
  assertThatThrownBy(()->userService.withdrawGoogle(f.user(),f.subject())).isInstanceOf(IllegalStateException.class);
  assertThat(users.findById(f.user()).orElseThrow().isActive()).isTrue();
  assertThat(materials.findById(f.material()).orElseThrow().isActive()).isTrue();assertThat(intents.count()).isZero();
 }
 @Test void concurrentRenderThenWithdrawalQueuesFinalImagesWithoutLockInversion() throws Exception {
  var f=fixture(null);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
  doAnswer(i->{entered.countDown();if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Synthetic barrier timeout");return null;})
   .when(files).storePageImage(any(),any());
  try(var executor=Executors.newFixedThreadPool(2)) {
   var write=executor.submit(()->render.store(f.key(),f.key().replace(".pdf","-pages/1.jpg"),new ByteArrayInputStream(new byte[]{1})));
   assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
   var cancellation=executor.submit(()->userService.withdrawGoogle(f.user(),f.subject()));
   try{assertThatThrownBy(()->cancellation.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);}
   finally{release.countDown();}
   assertThat(write.get(10,TimeUnit.SECONDS)).isTrue();cancellation.get(10,TimeUnit.SECONDS);
  } finally{release.countDown();}
  assertThat(materials.findById(f.material()).orElseThrow().isActive()).isFalse();
  assertThat(intent(DeletionKind.RENDERED_PAGES).getStatus()).isEqualTo(DeletionStatus.POLICY_PENDING);
  assertThat(render.store(f.key(),f.key().replace(".pdf","-pages/2.jpg"),new ByteArrayInputStream(new byte[]{2}))).isFalse();
  verify(files,times(1)).storePageImage(any(),any());verifyNoInteractions(ai);
 }
 @Test void duplicateRequestsAndWorkersHaveOneCurrentClaim() throws Exception {
  journal.recordExternal("synthetic-shared"); journal.recordExternal("synthetic-shared"); permit(DeletionKind.EXTERNAL_AI,0);
  Long id=intents.findAll().getFirst().getId(); CountDownLatch start=new CountDownLatch(1);
  try(var executor=Executors.newFixedThreadPool(2)) {
   Callable<DeletionClaim> task=()->{start.await(); return store.claim(id);};
   var a=executor.submit(task);var b=executor.submit(task);start.countDown();
   assertThat(Arrays.asList(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS)).stream().filter(Objects::nonNull)).hasSize(1);
  }
  assertThat(intents.count()).isEqualTo(1); assertThat(intents.findById(id).orElseThrow().getAttempts()).isEqualTo(1);
 }
 @Test void successfulKindsAreNotRepeatedWhenOneProviderFails() {
  var f=fixture("synthetic-retry"); materialService.delete(f.user(),f.material());
  permit(DeletionKind.ORIGINAL_PDF,0); permit(DeletionKind.RENDERED_PAGES,0); permit(DeletionKind.EXTERNAL_AI,0);
  doAnswer(i->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return null;}).when(files).delete(any());
  doThrow(new IllegalStateException("synthetic failure")).doNothing().when(ai).deleteFile("synthetic-retry");
  store.candidates().forEach(id->worker().process(id));
  assertThat(intent(DeletionKind.EXTERNAL_AI).getStatus()).isEqualTo(DeletionStatus.RETRY);
  assertThat(intent(DeletionKind.ORIGINAL_PDF).getStatus()).isEqualTo(DeletionStatus.DONE);
  clock.advance(3); store.candidates().forEach(id->worker().process(id));
  assertThat(intent(DeletionKind.EXTERNAL_AI).getStatus()).isEqualTo(DeletionStatus.DONE);
  verify(files,times(1)).delete(f.key()); verify(files,times(1)).deleteMaterialRenders(f.key());
  verify(ai,times(2)).deleteFile("synthetic-retry");
 }
 @Test void expiredLeaseRecoveredWithNewTokenAndOldWorkerCannotComplete() {
  journal.recordExternal("synthetic-restart");permit(DeletionKind.EXTERNAL_AI,0);Long id=intents.findAll().getFirst().getId();
  var old=store.claim(id); clock.advance(11);var recovered=store.claim(id);
  assertThat(recovered.token()).isNotEqualTo(old.token()); assertThat(store.complete(old)).isFalse();
  assertThat(store.complete(recovered)).isTrue();
 }
 @Test void retryLimitTracksFailureAndRequiresExplicitOperatorRetry() {
  journal.recordExternal("synthetic-terminal");permit(DeletionKind.EXTERNAL_AI,0);Long id=intents.findAll().getFirst().getId();
  doThrow(new IllegalStateException("sensitive provider body should not be logged")).when(ai).deleteFile(any());
  worker().process(id); clock.advance(3);worker().process(id);
  assertThat(intents.findById(id).orElseThrow().getStatus()).isEqualTo(DeletionStatus.FAILED);
  assertThat(store.candidates()).isEmpty(); assertThat(store.retryFailed(id)).isTrue();
  assertThat(store.retryFailed(id)).isFalse(); assertThat(intents.findById(id).orElseThrow().getAttempts()).isZero();
 }
 @Test void approvedDelayAndPolicyChangeNeverShortenRecordedDeadline() {
  journal.recordExternal("synthetic-retain");permit(DeletionKind.EXTERNAL_AI,10);Long id=intents.findAll().getFirst().getId();
  assertThat(store.claim(id)).isNull();var deadline=intents.findById(id).orElseThrow().getRetainUntil();
  days.put(DeletionKind.EXTERNAL_AI,0);when(policy.policyVersion()).thenReturn("SYNTHETIC_SHORTER");
  assertThat(store.claim(id)).isNull();assertThat(intents.findById(id).orElseThrow().getRetainUntil()).isEqualTo(deadline);
  assertThat(store.candidates()).isEmpty();verifyNoInteractions(ai,files);
 }
 @Test void unapprovedKindsDoNotStarveApprovedKinds() {
  var f=fixture("synthetic-eligible");materialService.delete(f.user(),f.material());permit(DeletionKind.EXTERNAL_AI,0);
  assertThat(store.candidates()).containsExactly(intent(DeletionKind.EXTERNAL_AI).getId());
 }
 @Test void activeExternalReferenceIsHeldAndQueuedIdentifierCannotReattach() {
  var f=fixture("synthetic-in-use"); journal.recordExternal("synthetic-in-use");permit(DeletionKind.EXTERNAL_AI,0);
  worker().process(intent(DeletionKind.EXTERNAL_AI).getId());
  assertThat(intent(DeletionKind.EXTERNAL_AI).getStatus()).isEqualTo(DeletionStatus.REFERENCE_PENDING);verifyNoInteractions(ai);
  tx(()->{var m=materials.findByIdForUpdate(f.material()).orElseThrow();m.replaceXaiFileId(null);m.markReady(1);});
  assertThat(backfill.attachIfStillEligible(f.material(),"synthetic-in-use")).isFalse();
 }
 @Test void lateExtractionBackfillAndRenderCannotResurrectDeletedMaterial() {
  var f=fixture(null);materialService.delete(f.user(),f.material());
  assertThat(extraction.complete(f.material(),List.of(new ExtractedPage(1,"synthetic text")),"synthetic-late").applied()).isFalse();
  assertThat(backfill.attachIfStillEligible(f.material(),"synthetic-late")).isFalse();
  assertThat(render.store(f.key(),f.key().replace(".pdf","-pages/1.jpg"),new ByteArrayInputStream(new byte[]{1}))).isFalse();
  assertThat(pages.count()).isZero();verifyNoInteractions(files,ai);
 }
 @Test void restoredActiveMaterialTombstoneRejectsLateRenderAndExtractionBeforeReplay() {
  var f=fixture(null);materialService.delete(f.user(),f.material());
  jdbc.update("update learning_materials set status='ACTIVE' where id=?",f.material());
  assertThat(render.store(f.key(),f.key().replace(".pdf","-pages/1.jpg"),new ByteArrayInputStream(new byte[]{1}))).isFalse();
  assertThat(extraction.complete(f.material(),List.of(new ExtractedPage(1,"synthetic")),null).applied()).isFalse();verifyNoInteractions(files);
 }
 @Test void restoreEpochReopensCompletedCleanupExactlyOnceAndReappliesLogicalDeletion() {
  var f=fixture(null);materialService.delete(f.user(),f.material());permit(DeletionKind.ORIGINAL_PDF,0);
  var id=intent(DeletionKind.ORIGINAL_PDF).getId();worker().process(id);var exported=journal.exportPage(0,100).entries();
  jdbc.update("update learning_materials set status='ACTIVE' where id=?",f.material());
  assertThat(restore.restoreBatch(exported,"synthetic_restore_1")).contains(DeletionReplay.Result.APPLIED);
  assertThat(materials.findById(f.material()).orElseThrow().isActive()).isFalse();worker().process(id);
  long generation=intents.findById(id).orElseThrow().getGeneration();
  restore.restoreBatch(exported,"synthetic_restore_1");assertThat(intents.findById(id).orElseThrow().getStatus()).isEqualTo(DeletionStatus.DONE);
  assertThat(intents.findById(id).orElseThrow().getGeneration()).isEqualTo(generation);verify(files,times(2)).delete(f.key());
 }
 @Test void emptyJournalRestorePreservesExportedDeadlineAgainstShorterCurrentPolicy() {
  journal.recordExternal("synthetic-retention-restore");permit(DeletionKind.EXTERNAL_AI,30);
  Long originalId=intent(DeletionKind.EXTERNAL_AI).getId();assertThat(store.claim(originalId)).isNull();
  Instant originalDeadline=intents.findById(originalId).orElseThrow().getRetainUntil();
  var exported=journal.exportPage(0,100).entries();intents.deleteAll(); // Backup predates every deletion intent.
  days.put(DeletionKind.EXTERNAL_AI,7);when(policy.policyVersion()).thenReturn("SYNTHETIC_SHORTER_RESTORE");clock.advance(8*86400L);
  restore.restoreBatch(exported,"synthetic_empty_restore");var restored=intent(DeletionKind.EXTERNAL_AI);
  assertThat(restored.getRetainUntil()).isEqualTo(originalDeadline);
  worker().process(restored.getId());assertThat(intents.findById(restored.getId()).orElseThrow().getStatus()).isNotEqualTo(DeletionStatus.DONE);
  assertThat(intents.findById(restored.getId()).orElseThrow().getRetainUntil()).isEqualTo(originalDeadline);verifyNoInteractions(ai,files);
 }
 @Test void restoreImportRaisesExistingDeadlineToExportedMaximum() {
  journal.recordExternal("synthetic-existing-retention");permit(DeletionKind.EXTERNAL_AI,30);
  assertThat(store.claim(intent(DeletionKind.EXTERNAL_AI).getId())).isNull();Instant originalDeadline=intent(DeletionKind.EXTERNAL_AI).getRetainUntil();
  var exported=journal.exportPage(0,100).entries();intents.deleteAll();
  days.put(DeletionKind.EXTERNAL_AI,7);when(policy.policyVersion()).thenReturn("SYNTHETIC_SHORTER_EXISTING");
  journal.recordExternal("synthetic-existing-retention");assertThat(store.claim(intent(DeletionKind.EXTERNAL_AI).getId())).isNull();
  assertThat(intent(DeletionKind.EXTERNAL_AI).getRetainUntil()).isBefore(originalDeadline);
  restore.restoreBatch(exported,"synthetic_existing_restore");assertThat(intent(DeletionKind.EXTERNAL_AI).getRetainUntil()).isEqualTo(originalDeadline);
 }
 @Test void replayQueuesRestoredAvatarFromBackupBeforeAvatarReplacement() {
  var f=fixture(null);String originalEmail=users.findById(f.user()).orElseThrow().getEmail();
  String oldAvatar="avatars/"+UUID.randomUUID()+".png",newAvatar="avatars/"+UUID.randomUUID()+".png";
  tx(()->users.findByIdForUpdate(f.user()).orElseThrow().replaceAvatar(oldAvatar)); // Earlier backup contains A.
  tx(()->users.findByIdForUpdate(f.user()).orElseThrow().replaceAvatar(newAvatar)); // Later account uses B.
  userService.withdrawGoogle(f.user(),f.subject());var exported=journal.exportPage(0,100).entries();
  assertThat(intents.findAll().stream().filter(i->i.getKind()==DeletionKind.AVATAR).map(DeletionIntent::resourceKey)).containsExactly(newAvatar);
  jdbc.update("update users set status='ACTIVE',email=?,avatar_key=? where id=?",originalEmail,oldAvatar,f.user());
  restore.restoreBatch(exported,"synthetic_avatar_backup");
  assertThat(users.findById(f.user()).orElseThrow().getAvatarKey()).isNull();
  assertThat(intents.findAll().stream().filter(i->i.getKind()==DeletionKind.AVATAR).map(DeletionIntent::resourceKey)).containsExactlyInAnyOrder(oldAvatar,newAvatar);
  verifyNoInteractions(files,ai);
 }
 @Test void restoredAccountIsReanonymizedWithoutCompletionMail() {
  var f=fixture("synthetic-restore-account");User before=users.findById(f.user()).orElseThrow();String original=before.getEmail();
  userService.withdrawGoogle(f.user(),f.subject());var export=journal.exportPage(0,100).entries();clearInvocations(mail);
  jdbc.update("update users set status='ACTIVE',email=?,name='Synthetic restored' where id=?",original,f.user());
  jdbc.update("update learning_materials set status='ACTIVE' where id=?",f.material());
  assertThat(restore.restoreBatch(export,"synthetic_account_restore")).contains(DeletionReplay.Result.APPLIED);
  assertThat(users.findById(f.user()).orElseThrow().isActive()).isFalse();
  assertThat(materials.findById(f.material()).orElseThrow().isActive()).isFalse();verifyNoInteractions(mail,ai,files);
 }
 @Test void restoredNumericAccountIdWithDifferentIdentityIsPreserved() {
  var f=fixture(null);var u=users.findById(f.user()).orElseThrow();
  tx(()->journal.recordAccount(u.getId(),u.getCreatedAt(),u.getEmail()));Long id=intent(DeletionKind.ACCOUNT).getId();
  jdbc.update("update users set email='different-synthetic@example.com' where id=?",f.user());
  assertThat(replay.reapply(id)).isEqualTo(DeletionReplay.Result.IDENTITY_MISMATCH);assertThat(users.findById(f.user()).orElseThrow().isActive()).isTrue();
 }
 @Test void deletedAccountCannotPublishNewMaterialAndTemporaryUploadIsCleaned() {
  var f=fixture(null);userService.withdrawGoogle(f.user(),f.subject());when(files.store(any())).thenReturn("materials/synthetic-unpublished.pdf");
  var file=new org.springframework.mock.web.MockMultipartFile("file","synthetic.pdf","application/pdf","%PDF-synthetic".getBytes());
  assertThatThrownBy(()->materialService.upload(f.user(),file,"Synthetic")).isInstanceOf(io.edupilot.global.error.BusinessException.class);
  verify(files).delete("materials/synthetic-unpublished.pdf");assertThat(materials.count()).isEqualTo(1);
 }
 @Test void deletedAccountCannotAttachNewAvatarAndUnpublishedUploadIsCleaned() {
  var f=fixture(null);userService.withdrawGoogle(f.user(),f.subject());when(files.storeAvatar(any(),eq("png"))).thenReturn("avatars/synthetic-unpublished.png");
  byte[] png={(byte)0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a,1,2,3,4};
  var file=new org.springframework.mock.web.MockMultipartFile("file","synthetic.png","image/png",png);
  assertThatThrownBy(()->userService.uploadAvatar(f.user(),file)).isInstanceOf(io.edupilot.global.error.BusinessException.class);
  assertThat(users.findById(f.user()).orElseThrow().getAvatarKey()).isNull();verify(files).delete("avatars/synthetic-unpublished.png");
 }
 @Test void exportUsesStableCursorAndInvalidImportLeavesJournalUntouched() {
  journal.recordExternal("synthetic-page-a");journal.recordExternal("synthetic-page-b");
  var first=journal.exportPage(0,1);assertThat(first.hasNext()).isTrue();assertThat(journal.exportPage(first.nextId(),1).entries()).hasSize(1);
  assertThatThrownBy(()->journal.importForRestore(first.entries(),"../invalid")).isInstanceOf(IllegalArgumentException.class);
  assertThat(intents.findAll()).allSatisfy(i->assertThat(i.getGeneration()).isZero());
 }
 DeletionIntent intent(DeletionKind kind) { return intents.findAll().stream().filter(i->i.getKind()==kind).findFirst().orElseThrow(); }
 void permit(DeletionKind kind,int retention) {enabled=true;days.put(kind,retention);}
 DeletionWorker worker() {return new DeletionWorker(store,files,ai,policy,Runnable::run);}
 void tx(Runnable action) {new TransactionTemplate(transactions).executeWithoutResult(s->action.run());}
 Fixture fixture(String fileId) {
  String subject="synthetic-sub-"+UUID.randomUUID();
  return new TransactionTemplate(transactions).execute(s->{
   var user=users.saveAndFlush(User.createGoogle("synthetic-"+UUID.randomUUID()+"@example.com","hash","Synthetic",UserRole.LEARNER,null,false,null,null,null,subject));
   String key="materials/"+UUID.randomUUID()+".pdf";var material=LearningMaterial.create(user,"Synthetic material",key);material.replaceXaiFileId(fileId);
   material=materials.saveAndFlush(material);return new Fixture(user.getId(),material.getId(),key,subject);
  });
 }
 record Fixture(Long user,Long material,String key,String subject) {}
 @TestConfiguration static class ClockConfiguration { @Bean @Primary MutableClock deletionTestClock(){return new MutableClock();} }
 static class MutableClock extends Clock {
  final AtomicReference<Instant> now=new AtomicReference<>(Instant.now());
  void advance(long seconds){now.updateAndGet(i->i.plusSeconds(seconds));}
  public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now.get();}
 }
}
