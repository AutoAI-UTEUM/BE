package io.edupilot.guardian;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;
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
 @Autowired JwtProperties jwtProperties;
 @Autowired EmailVerificationGate businessGate;
 @Autowired UserAccessGuard access;
 @Autowired AuthSessionRepository authSessions;
 @MockitoBean AiClient ai;
 @MockitoBean EmailService mail;
 @MockitoBean EmailVerificationService emailVerification;
 @MockitoBean GoogleIdTokenVerifier google;
 @MockitoBean Clock clock;
 @MockitoSpyBean ManualGuardianIntakeProvider provider;
 ManualGuardianIntakeProvider providerSpy;
 @BeforeEach void cleanSyntheticRows() {
  when(clock.instant()).thenReturn(Instant.parse("2026-10-05T00:00:00Z"));when(clock.getZone()).thenReturn(ZoneOffset.UTC);
  providerSpy=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(provider);
  mvc=MockMvcBuilders.webAppContextSetup(context).apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
  if(System.getenv("GUARDIAN_FOUNDATION_MYSQL_URL")!=null){assertThat(jdbc.queryForObject("select @@port",Integer.class)).isEqualTo(33316);assertThat(jdbc.queryForObject("select database()",String.class)).isEqualTo("guardian_foundation_synthetic");}
  requests.deleteAll();consents.deleteAll();documents.deleteAll();refreshTokens.deleteAll();authSessions.deleteAll();users.deleteAll();
  if(!deletionLocks.existsById(1))deletionLocks.saveAndFlush(io.edupilot.deletion.DeletionJournalLock.initial());
  var actor=users.saveAndFlush(User.create("synthetic-admin@example.com","hash","Synthetic admin",UserRole.ADMIN));
  Instant now=clock.instant();for(var type:List.of(PolicyType.TERMS,PolicyType.PRIVACY))
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
 static Stream<Arguments> signupCalendarCases() {
  return Stream.of(false,true).flatMap(googleSignup->Stream.of(
   Arguments.of(googleSignup,"2026-10-04T14:59:59.999999999Z","2026-10-05",false),
   Arguments.of(googleSignup,"2026-10-04T15:00:00Z","2026-10-05",true),
   Arguments.of(googleSignup,"2026-12-31T14:59:59.999999999Z","2027-01-01",false),
   Arguments.of(googleSignup,"2026-12-31T15:00:00Z","2027-01-01",true),
   Arguments.of(googleSignup,"2028-02-28T15:00:00Z","2028-02-29",true),
   Arguments.of(googleSignup,"2028-02-28T15:00:00Z","2028-03-01",false)));
 }
 @ParameterizedTest @MethodSource("signupCalendarCases")
 void localAndNewGoogleValidateKoreanDateBeforeAccountOrEmailWork(boolean googleSignup,String instant,String date,boolean accepted) throws Exception {
  when(clock.instant()).thenReturn(Instant.parse(instant));
  String json=localJson(true,true).replace("2014-01-01",date);
  String path="/api/auth/signup",email="synthetic-new@example.com";
  if(googleSignup){path="/api/auth/google";email="synthetic-calendar@example.com";
   when(google.verify("synthetic-calendar-token")).thenReturn(new GoogleProfile("synthetic-calendar-sub",email,"Synthetic"));
   json=json.replace("\"email\":\"synthetic-new@example.com\",\"password\":\"StrongPass123!\",\"name\":\"Synthetic\",","\"idToken\":\"synthetic-calendar-token\",");}
  var response=mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json));
  if(accepted){response.andExpect(status().isOk());User saved=users.findByEmail(email).orElseThrow();
   assertThat(saved.getDateOfBirth()).isEqualTo(LocalDate.parse(date));assertThat(saved.getAgeVerificationState()).isEqualTo(AgeVerificationState.UNKNOWN);
   verify(emailVerification).signup(any(User.class),anyString());
  }else{response.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
   assertThat(users.findByEmail(email)).isEmpty();verifyNoInteractions(emailVerification);}
  assertThat(requests.count()).isZero();verifyNoInteractions(ai,mail);
 }
 static Stream<Arguments> signupYearCases(){return Stream.of(false,true).flatMap(googleSignup->Stream.of(2011,2012).map(year->Arguments.of(googleSignup,year)));}
 @ParameterizedTest @MethodSource("signupYearCases")
 void currentSignupConsentAndEmailEvidencePrecedeTheApprovedYearBoundary(boolean googleSignup,int birthYear) throws Exception {
  String date=birthYear+"-12-31",email=googleSignup?"synthetic-year-google@example.com":"synthetic-new@example.com";
  String path=googleSignup?"/api/auth/google":"/api/auth/signup";
  String json=localJson(true,true).replace("2014-01-01",date);
  if(googleSignup){when(google.verify("synthetic-year-token")).thenReturn(new GoogleProfile("synthetic-year-sub",email,"Synthetic"));
   json=json.replace("\"email\":\"synthetic-new@example.com\",\"password\":\"StrongPass123!\",\"name\":\"Synthetic\",","\"idToken\":\"synthetic-year-token\",");}
  String missingConsent=json.replace(",\"consents\":[{\"type\":\"TERMS\",\"version\":\"1.0\"},{\"type\":\"PRIVACY\",\"version\":\"1.0\"}]","");
  mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(missingConsent))
   .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("POLICY_CONSENT_REQUIRED"));
  assertThat(users.findByEmail(email)).isEmpty();verifyNoInteractions(emailVerification,ai);
  mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isOk());
  User saved=users.findByEmail(email).orElseThrow();
  // JWT parser uses wall-clock expiry. Only the synthetic token writer uses wall time; age policy stays fixed.
  String bearer="Bearer "+new JwtTokenProvider(jwtProperties,Clock.systemUTC()).createAccessToken(saved);
  mvc.perform(get("/api/materials").header("Authorization",bearer)).andExpect(status().isForbidden())
   .andExpect(jsonPath("$.error.code").value("EMAIL_VERIFICATION_REQUIRED"));
  saved.verifyEmail(clock.instant());users.saveAndFlush(saved);
  var access=mvc.perform(get("/api/materials").header("Authorization",bearer));
  if(birthYear==2011){access.andExpect(status().isOk());gate.requireEligible(saved.getId());}
  else{access.andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("AGE_VERIFICATION_REQUIRED"));assertGate(saved.getId(),ErrorCode.AGE_VERIFICATION_REQUIRED);}
  assertThat(users.findById(saved.getId()).orElseThrow().getAgeVerificationState()).isEqualTo(AgeVerificationState.UNKNOWN);
  assertThat(consents.countByPolicyTypeAndPolicyVersionAndUser_Status(PolicyType.TERMS,"1.0",UserStatus.ACTIVE)).isEqualTo(1);
  assertThat(requests.count()).isZero();verifyNoInteractions(ai,mail);
 }
 @Test void existingGoogleIgnoresSubmittedDobAndCannotReplaceTheCapturedBirthdate() throws Exception {
  User user=googleUser(true);when(google.verify("synthetic-existing-date-token")).thenReturn(new GoogleProfile(user.getGoogleSub(),user.getEmail(),"Synthetic"));
  mvc.perform(post("/api/auth/google").contentType(MediaType.APPLICATION_JSON)
   .content("{\"idToken\":\"synthetic-existing-date-token\",\"dateOfBirth\":\"9999-12-31\"}"))
   .andExpect(status().isOk());
  assertThat(users.findById(user.getId()).orElseThrow().getDateOfBirth()).isEqualTo(LocalDate.of(2014,1,1));
  assertThat(requests.count()).isZero();verifyNoInteractions(emailVerification,ai);
 }
 @Test void currentJpaAndHttpAccessUseTheKoreanNewYearWithoutChangingVerificationEvidence() throws Exception {
  User user=User.create("synthetic-new-year@example.com","synthetic-hash","Synthetic");
  user.recordSignupDateOfBirth(LocalDate.of(2012,12,31));user.verifyEmail(clock.instant());user=users.saveAndFlush(user);
  Long id=user.getId();String bearer="Bearer "+new JwtTokenProvider(jwtProperties,Clock.systemUTC()).createAccessToken(user);
  var principal=new AuthenticatedUser(id,UserRole.LEARNER);
  when(clock.instant()).thenReturn(Instant.parse("2026-12-31T14:59:59.999999999Z"));
  assertGate(id,ErrorCode.AGE_VERIFICATION_REQUIRED);
  assertThat(access.checkBusiness(principal)).isEqualTo(ErrorCode.AGE_VERIFICATION_REQUIRED);
  assertThatThrownBy(()->businessGate.requireVerified(id)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.errorCode()).isEqualTo(ErrorCode.AGE_VERIFICATION_REQUIRED));
  mvc.perform(get("/api/materials").header("Authorization",bearer)).andExpect(status().isForbidden());
  when(clock.instant()).thenReturn(Instant.parse("2026-12-31T15:00:00Z"));
  gate.requireEligible(id);businessGate.requireVerified(id);assertThat(access.checkBusiness(principal)).isNull();
  mvc.perform(get("/api/materials").header("Authorization",bearer)).andExpect(status().isOk());
  User current=users.findById(id).orElseThrow();assertThat(current.getAgeVerificationState()).isEqualTo(AgeVerificationState.UNKNOWN);
  assertThat(current.getAccessCohort()).isEqualTo(AccountAccessCohort.NEW_SIGNUP);assertThat(requests.count()).isZero();verifyNoInteractions(ai,mail);
 }
 User googleUser(boolean date) {
  var user=User.createGoogle("synthetic-"+UUID.randomUUID()+"@example.com","hash","Synthetic",UserRole.LEARNER,null,false,null,null,null,"synthetic-sub-"+UUID.randomUUID());
  if(date)user.recordSignupDateOfBirth(LocalDate.of(2014,1,1));return users.saveAndFlush(user);
 }
 void assertGate(Long id,ErrorCode code){assertThatThrownBy(()->gate.requireEligible(id)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.errorCode()).isEqualTo(code));}
 String localJson(boolean date,boolean consent){return "{\"email\":\"synthetic-new@example.com\",\"password\":\"StrongPass123!\",\"name\":\"Synthetic\",\"role\":\"LEARNER\""
  +(date?",\"dateOfBirth\":\"2014-01-01\"":"")+(consent?",\"consents\":[{\"type\":\"TERMS\",\"version\":\"1.0\"},{\"type\":\"PRIVACY\",\"version\":\"1.0\"}]":"")+"}";}
}
