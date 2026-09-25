package io.edupilot.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.policy.dto.PolicyConsentChoice;
import io.edupilot.policy.dto.PublishPolicyRequest;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserStatus;

@ExtendWith(MockitoExtension.class)
class PolicyServiceTest {
	private static final Instant NOW = Instant.parse("2026-09-25T00:00:00Z");
	@Mock private PolicyDocumentRepository documents;
	@Mock private PolicyConsentRepository consents;
	@Mock private UserRepository users;
	private PolicyService service;

	@BeforeEach
	void setUp() {
		service = new PolicyService(documents, consents, users,
			Clock.fixed(NOW, ZoneOffset.UTC));
	}

	@Test
	void currentSelectsLatestEffectiveVersionAndDetailFindsHistory() {
		when(documents.findFirstByTypeAndEffectiveAtLessThanEqualOrderByEffectiveAtDescIdDesc(
			PolicyType.TERMS, NOW)).thenReturn(Optional.of(document(PolicyType.TERMS, "1.0")));
		when(documents.findByTypeAndVersion(PolicyType.TERMS, "0.9"))
			.thenReturn(Optional.of(document(PolicyType.TERMS, "0.9")));
		assertThat(service.current()).singleElement().satisfies(value ->
			assertThat(value.version()).isEqualTo("1.0"));
		assertThat(service.detail(PolicyType.TERMS, "0.9").content()).isEqualTo("검토 중 초안");
		assertError(() -> service.detail(PolicyType.TERMS, "9.9"), ErrorCode.POLICY_NOT_FOUND);
	}

	@Test
	void signupRequiresBothCurrentVersionsAndRecordsHistory() {
		currentDocuments("0.9", "0.9");
		assertError(() -> service.validateSignup(null), ErrorCode.POLICY_CONSENT_REQUIRED);
		assertError(() -> service.validateSignup(List.of(choice(PolicyType.TERMS, "0.9"))),
			ErrorCode.POLICY_CONSENT_REQUIRED);
		assertError(() -> service.validateSignup(List.of(choice(PolicyType.TERMS, "0.9"),
			choice(PolicyType.PRIVACY, "1.0"))), ErrorCode.POLICY_CONSENT_REQUIRED);
		User user = user();
		service.recordSignup(user, service.validateSignup(choices("0.9", "0.9")),
			"192.0.2.1", "test-agent");
		org.mockito.ArgumentCaptor<PolicyConsent> captured =
			org.mockito.ArgumentCaptor.forClass(PolicyConsent.class);
		verify(consents, times(2)).save(captured.capture());
		assertThat(captured.getAllValues()).extracting(PolicyConsent::getPolicyType)
			.containsExactly(PolicyType.TERMS, PolicyType.PRIVACY);
		assertThat(captured.getAllValues()).allSatisfy(value -> {
			assertThat(value.getUser()).isSameAs(user);
			assertThat(value.getAgreedAt()).isEqualTo(NOW);
			assertThat(value.getIp()).isEqualTo("192.0.2.1");
		});
	}

	@Test
	void pendingNewVersionAgreementIsIdempotentAndRejectsOldOrFutureVersion() {
		currentDocuments("1.0", "0.9");
		User user = user();
		when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(user));
		org.mockito.Mockito.lenient().when(consents.existsByUser_IdAndPolicyTypeAndPolicyVersion(
			7L, PolicyType.PRIVACY, "0.9")).thenReturn(true);
		assertThat(service.pendingForLogin(7L)).singleElement().satisfies(value -> {
			assertThat(value.type()).isEqualTo(PolicyType.TERMS);
			assertThat(value.version()).isEqualTo("1.0");
		});
		service.agree(7L, List.of(choice(PolicyType.TERMS, "1.0")),
			"192.0.2.1", "test-agent");
		verify(consents).saveAndFlush(any(PolicyConsent.class));
		org.mockito.Mockito.lenient().when(consents.existsByUser_IdAndPolicyTypeAndPolicyVersion(
			7L, PolicyType.TERMS, "1.0")).thenReturn(true);
		service.agree(7L, List.of(choice(PolicyType.TERMS, "1.0")),
			"192.0.2.1", "test-agent");
		verify(consents, times(1)).saveAndFlush(any(PolicyConsent.class));
		assertThat(service.pendingForLogin(7L)).isEmpty();
		assertError(() -> service.agree(7L, List.of(choice(PolicyType.TERMS, "0.9")),
			"192.0.2.1", null), ErrorCode.POLICY_VERSION_MISMATCH);
		assertError(() -> service.agree(7L, List.of(choice(PolicyType.TERMS, "2.0")),
			"192.0.2.1", null), ErrorCode.POLICY_VERSION_MISMATCH);
	}

	@Test
	void publishRequiresFutureUniqueVersionAndStatsUseActiveUsers() {
		PublishPolicyRequest request = new PublishPolicyRequest(PolicyType.TERMS, "1.0",
			"새 약관", "본문", "변경 요약", NOW.plusSeconds(60));
		when(documents.saveAndFlush(any(PolicyDocument.class)))
			.thenAnswer(invocation -> invocation.getArgument(0));
		assertThat(service.publish(3L, request).version()).isEqualTo("1.0");
		assertError(() -> service.publish(3L, new PublishPolicyRequest(
			PolicyType.TERMS, "1.1", "과거", "본문", null, NOW.minusSeconds(1))),
			ErrorCode.VALIDATION_FAILED);
		when(documents.existsByTypeAndVersion(PolicyType.TERMS, "1.0")).thenReturn(true);
		assertError(() -> service.publish(3L, request), ErrorCode.POLICY_VERSION_EXISTS);
		currentDocuments("0.9", "0.9");
		when(users.countByStatus(UserStatus.ACTIVE)).thenReturn(2L);
		when(consents.countByPolicyTypeAndPolicyVersionAndUser_Status(
			PolicyType.TERMS, "0.9", UserStatus.ACTIVE)).thenReturn(1L);
		assertThat(service.stats()).hasSize(2);
		assertThat(service.stats().get(0).consentRatePercent()).isEqualByComparingTo("50.00");
	}

	private void currentDocuments(String terms, String privacy) {
		when(documents.findFirstByTypeAndEffectiveAtLessThanEqualOrderByEffectiveAtDescIdDesc(
			PolicyType.TERMS, NOW)).thenReturn(Optional.of(document(PolicyType.TERMS, terms)));
		when(documents.findFirstByTypeAndEffectiveAtLessThanEqualOrderByEffectiveAtDescIdDesc(
			PolicyType.PRIVACY, NOW)).thenReturn(Optional.of(document(PolicyType.PRIVACY, privacy)));
	}

	private PolicyDocument document(PolicyType type, String version) {
		return PolicyDocument.create(type, version, type.name(), "검토 중 초안", null,
			NOW.minusSeconds(1), 0L, NOW.minusSeconds(3600));
	}

	private PolicyConsentChoice choice(PolicyType type, String version) {
		return new PolicyConsentChoice(type, version);
	}

	private List<PolicyConsentChoice> choices(String terms, String privacy) {
		return List.of(choice(PolicyType.TERMS, terms), choice(PolicyType.PRIVACY, privacy));
	}

	private User user() {
		User user = User.create("user@example.com", "hash", "사용자");
		ReflectionTestUtils.setField(user, "id", 7L);
		return user;
	}

	private void assertError(Runnable action, ErrorCode expected) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
			error -> assertThat(error.errorCode()).isEqualTo(expected));
	}
}
