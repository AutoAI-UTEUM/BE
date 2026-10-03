package io.edupilot.guardian;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import io.edupilot.ai.AiClient;
import io.edupilot.auth.*;
import io.edupilot.global.error.*;
import io.edupilot.mail.EmailService;
import io.edupilot.policy.*;
import io.edupilot.user.*;

@SpringBootTest(properties={
 "spring.datasource.url=jdbc:h2:mem:guardian-foundation;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
 "spring.datasource.username=sa","spring.datasource.password=","spring.datasource.driver-class-name=org.h2.Driver",
 "spring.flyway.enabled=false","spring.jpa.hibernate.ddl-auto=create-drop",
 "edupilot.cors.allowed-origins=http://localhost:5173","edupilot.ai.base-url=http://localhost:8000",
 "edupilot.ai.internal-token=synthetic-internal-token",
 "edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
 "edupilot.storage.root-directory=build/test-storage/guardian-foundation",
 "edupilot.policy.signup-consent-required=true"
})
@ActiveProfiles("jpa-context")
class GuardianFoundationJpaTest {
 @DynamicPropertySource static void isolatedMysql(DynamicPropertyRegistry registry) {
  String url=System.getenv("GUARDIAN_FOUNDATION_MYSQL_URL");if(url==null||url.isBlank())return;
  if(!url.matches("^jdbc:mysql://127\\.0\\.0\\.1:33316/guardian_foundation_synthetic(?:\\?.*)?$"))throw new IllegalArgumentException("Guardian tests require disposable loopback database");
  registry.add("spring.datasource.url",()->url);registry.add("spring.datasource.username",()->"root");registry.add("spring.datasource.password",()->"");
  registry.add("spring.datasource.driver-class-name",()->"com.mysql.cj.jdbc.Driver");
 }
 MockMvc mvc;
 @Autowired WebApplicationContext context;
 @Autowired UserRepository users;
 @Autowired GuardianVerificationRequestRepository requests;
 @Autowired GuardianIntakeService intake;
 @Autowired AgeEligibilityGate gate;
 @Autowired UserService userService;
 @Autowired PolicyDocumentRepository documents;
 @Autowired PolicyConsentRepository consents;
 @Autowired PlatformTransactionManager transactions;
 @Autowired JdbcTemplate jdbc;
 @Autowired io.edupilot.deletion.DeletionJournalLockRepository deletionLocks;
 @Autowired RefreshTokenRepository refreshTokens;
 @Autowired AuthSessionRepository authSessions;
 @MockitoBean AiClient ai;
 @MockitoBean EmailService mail;
 @MockitoBean EmailVerificationService emailVerification;
 @MockitoBean GoogleIdTokenVerifier google;
 @MockitoSpyBean ManualGuardianIntakeProvider provider;
 ManualGuardianIntakeProvider providerSpy;
 @BeforeEach void cleanSyntheticRows() {
  providerSpy=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(provider);
  mvc=MockMvcBuilders.webAppContextSetup(context).apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
  if(System.getenv("GUARDIAN_FOUNDATION_MYSQL_URL")!=null){assertThat(jdbc.queryForObject("select @@port",Integer.class)).isEqualTo(33316);assertThat(jdbc.queryForObject("select database()",String.class)).isEqualTo("guardian_foundation_synthetic");}
  requests.deleteAll();consents.deleteAll();documents.deleteAll();refreshTokens.deleteAll();authSessions.deleteAll();users.deleteAll();
  if(!deletionLocks.existsById(1))deletionLocks.saveAndFlush(io.edupilot.deletion.DeletionJournalLock.initial());
  var actor=users.saveAndFlush(User.create("synthetic-admin@example.com","hash","Synthetic admin",UserRole.ADMIN));
  Instant now=Instant.now();for(var type:List.of(PolicyType.TERMS,PolicyType.PRIVACY))
   documents.saveAndFlush(PolicyDocument.create(type,"1.0","Synthetic","Synthetic document","Synthetic summary",true,now.minusSeconds(60),actor.getId(),now));
 }
 @Test void localSignupCapturesBirthdateAndRetainsUnknownWithoutGuardianApproval() throws Exception {
  mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(localJson(true,true)))
   .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("dateOfBirth"))));
  var user=users.findByEmail("synthetic-new@example.com").orElseThrow();assertThat(user.getDateOfBirth()).isEqualTo(LocalDate.of(2014,1,1));
  assertThat(user.getAgeVerificationState()).isEqualTo(AgeVerificationState.UNKNOWN);assertGate(user.getId(),ErrorCode.AGE_VERIFICATION_REQUIRED);
  assertThat(requests.count()).isZero();verifyNoInteractions(ai);
 }
 @Test void missingBirthdateDoesNotCreateLocalAccount() throws Exception {
  mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(localJson(false,true)))
   .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
  assertThat(users.findByEmail("synthetic-new@example.com")).isEmpty();verifyNoInteractions(emailVerification,ai);
 }
 @Test void requiredConsentRemainsEnforcedWithBirthdatePresent() throws Exception {
  mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(localJson(true,false)))
   .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("POLICY_CONSENT_REQUIRED"));
  assertThat(users.findByEmail("synthetic-new@example.com")).isEmpty();verifyNoInteractions(emailVerification,ai);
 }
 @Test void newGoogleSignupCapturesBirthdateAndNeverApprovesAge() throws Exception {
  when(google.verify("synthetic-new-token")).thenReturn(new GoogleProfile("synthetic-new-sub","synthetic-google@example.com","Synthetic"));
  mvc.perform(post("/api/auth/google").contentType(MediaType.APPLICATION_JSON).content("""
   {"idToken":"synthetic-new-token","role":"LEARNER","dateOfBirth":"2014-01-01",
    "consents":[{"type":"TERMS","version":"1.0"},{"type":"PRIVACY","version":"1.0"}]}
   """)).andExpect(status().isOk());
  var user=users.findByGoogleSub("synthetic-new-sub").orElseThrow();assertThat(user.getDateOfBirth()).isEqualTo(LocalDate.of(2014,1,1));
  assertGate(user.getId(),ErrorCode.AGE_VERIFICATION_REQUIRED);verifyNoInteractions(ai);
 }
 @Test void existingGoogleLoginDoesNotRequireOrFabricateBirthdate() throws Exception {
  var user=googleUser(false);when(google.verify("synthetic-existing-token")).thenReturn(new GoogleProfile(user.getGoogleSub(),user.getEmail(),"Synthetic"));
  mvc.perform(post("/api/auth/google").contentType(MediaType.APPLICATION_JSON).content("{\"idToken\":\"synthetic-existing-token\"}"))
   .andExpect(status().isOk());
  var saved=users.findById(user.getId()).orElseThrow();assertThat(saved.getDateOfBirth()).isNull();assertThat(saved.getAgeVerificationState()).isEqualTo(AgeVerificationState.UNKNOWN);
  assertGate(user.getId(),ErrorCode.AGE_VERIFICATION_REQUIRED);assertThat(requests.count()).isZero();
 }
 @Test void newGoogleAccountRequiresBirthdateAfterCurrentConsent() throws Exception {
  when(google.verify("synthetic-missing-token")).thenReturn(new GoogleProfile("synthetic-missing-sub","synthetic-missing@example.com","Synthetic"));
  mvc.perform(post("/api/auth/google").contentType(MediaType.APPLICATION_JSON).content("""
   {"idToken":"synthetic-missing-token","role":"LEARNER",
    "consents":[{"type":"TERMS","version":"1.0"},{"type":"PRIVACY","version":"1.0"}]}
   """)).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("SIGNUP_REQUIRED"));
  assertThat(users.findByGoogleSub("synthetic-missing-sub")).isEmpty();verifyNoInteractions(emailVerification,ai);
 }
 @Test void manualIntakeIsDurableIdempotentPendingAndNotApproval() {
  var user=googleUser(true);var first=intake.request(user.getId());var repeated=intake.request(user.getId());
  assertThat(first.requestId()).isEqualTo(repeated.requestId());assertThat(first.provider()).isEqualTo("manual");assertThat(first.status()).isEqualTo(GuardianVerificationRequest.Status.PENDING);
  assertThat(requests.count()).isEqualTo(1);assertThat(users.findById(user.getId()).orElseThrow().getAgeVerificationState()).isEqualTo(AgeVerificationState.MANUAL_PENDING);
  assertGate(user.getId(),ErrorCode.GUARDIAN_VERIFICATION_PENDING);verifyNoInteractions(ai,mail);
 }
 @Test void rolledBackIntakeHasNoRequestOrPendingState() {
  var user=googleUser(true);new TransactionTemplate(transactions).executeWithoutResult(status->{intake.request(user.getId());status.setRollbackOnly();});
  assertThat(requests.count()).isZero();assertThat(users.findById(user.getId()).orElseThrow().getAgeVerificationState()).isEqualTo(AgeVerificationState.UNKNOWN);
 }
 @Test void providerFailureDoesNotProduceFalsePendingOrApproval() {
  var user=googleUser(true);doThrow(new IllegalStateException("Synthetic unavailable")).when(providerSpy).request(any());
  assertThatThrownBy(()->intake.request(user.getId())).isInstanceOf(IllegalStateException.class);
  assertThat(requests.count()).isZero();assertThat(users.findById(user.getId()).orElseThrow().getAgeVerificationState()).isEqualTo(AgeVerificationState.UNKNOWN);
 }
 @Test void withdrawalClearsBirthdateCancelsPendingAndPreventsNewIntake() {
  var user=googleUser(true);var pending=intake.request(user.getId());userService.withdrawGoogle(user.getId(),user.getGoogleSub());
  var saved=users.findById(user.getId()).orElseThrow();assertThat(saved.getDateOfBirth()).isNull();assertThat(saved.getAgeVerificationState()).isEqualTo(AgeVerificationState.UNKNOWN);
  assertThat(requests.findById(pending.requestId()).orElseThrow().getStatus()).isEqualTo(GuardianVerificationRequest.Status.CANCELLED);
  assertThatThrownBy(()->intake.request(user.getId())).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.errorCode()).isEqualTo(ErrorCode.USER_INACTIVE));
 }
 @Test void concurrentIntakeThenWithdrawalCannotResurrectPendingAccountState() throws Exception {
  var user=googleUser(true);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
  doAnswer(i->{entered.countDown();if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Synthetic barrier timeout");return i.callRealMethod();}).when(providerSpy).request(any());
  try(var executor=Executors.newFixedThreadPool(2)) {
   var request=executor.submit(()->intake.request(user.getId()));assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
   var withdraw=executor.submit(()->userService.withdrawGoogle(user.getId(),user.getGoogleSub()));
   try{assertThatThrownBy(()->withdraw.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);}finally{release.countDown();}
   request.get(10,TimeUnit.SECONDS);withdraw.get(10,TimeUnit.SECONDS);
  }finally{release.countDown();}
  assertThat(users.findById(user.getId()).orElseThrow().getAgeVerificationState()).isEqualTo(AgeVerificationState.UNKNOWN);
  assertThat(requests.findByUser_Id(user.getId()).orElseThrow().getStatus()).isEqualTo(GuardianVerificationRequest.Status.CANCELLED);
 }
 User googleUser(boolean date) {
  var user=User.createGoogle("synthetic-"+UUID.randomUUID()+"@example.com","hash","Synthetic",UserRole.LEARNER,null,false,null,null,null,"synthetic-sub-"+UUID.randomUUID());
  if(date)user.recordSignupDateOfBirth(LocalDate.of(2014,1,1));return users.saveAndFlush(user);
 }
 void assertGate(Long id,ErrorCode code){assertThatThrownBy(()->gate.requireEligible(id)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.errorCode()).isEqualTo(code));}
 String localJson(boolean date,boolean consent){return "{\"email\":\"synthetic-new@example.com\",\"password\":\"StrongPass123!\",\"name\":\"Synthetic\",\"role\":\"LEARNER\""
  +(date?",\"dateOfBirth\":\"2014-01-01\"":"")+(consent?",\"consents\":[{\"type\":\"TERMS\",\"version\":\"1.0\"},{\"type\":\"PRIVACY\",\"version\":\"1.0\"}]":"")+"}";}
}
