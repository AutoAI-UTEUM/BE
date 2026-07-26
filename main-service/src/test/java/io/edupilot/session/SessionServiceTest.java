package io.edupilot.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.diagnosis.DiagnosisService;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.LearningMaterial;
import io.edupilot.material.LearningMaterialRepository;
import io.edupilot.session.dto.PendingDiagnosisResponse;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;

@ExtendWith(MockitoExtension.class)
class SessionServiceTest {

	private static final Instant NOW = Instant.parse("2026-07-25T10:00:00Z");

	@Mock
	private LearningSessionRepository sessionRepository;

	@Mock
	private LearningMaterialRepository materialRepository;

	@Mock
	private UserRepository userRepository;

	@Mock
	private DiagnosisService diagnosisService;

	private SessionService sessionService;
	private User owner;
	private LearningMaterial material;

	@BeforeEach
	void setUp() {
		sessionService = new SessionService(
			sessionRepository,
			materialRepository,
			userRepository,
			new StateReducer(),
			Clock.fixed(NOW, ZoneOffset.UTC),
			diagnosisService
		);
		owner = User.create("owner@example.com", "hash", "소유자");
		ReflectionTestUtils.setField(owner, "id", 1L);
		material = LearningMaterial.create(owner, "자료", "materials/test.pdf");
		ReflectionTestUtils.setField(material, "id", 10L);
	}

	@Test
	void createsReadySessionAndReusesExistingActiveSession() {
		material.markReady(3);
		when(materialRepository.findByIdForUpdate(10L))
			.thenReturn(Optional.of(material));
		when(userRepository.getReferenceById(1L)).thenReturn(owner);
		when(sessionRepository.findByUser_IdAndMaterial_IdAndStatus(
			1L,
			10L,
			SessionStatus.ACTIVE
		)).thenReturn(Optional.empty());
		when(sessionRepository.saveAndFlush(any(LearningSession.class)))
			.thenAnswer(invocation -> persisted(invocation.getArgument(0), 100L));

		var created = sessionService.create(1L, 10L);

		assertThat(created.reused()).isFalse();
		assertThat(created.currentPage()).isEqualTo(1);
		assertThat(created.pageStatus()).isEqualTo(PageStatus.NOT_EXPLAINED);
		assertThat(created.uiActions().getFirst().content())
			.isEqualTo("강의를 시작할까요?");

		LearningSession existing = persisted(
			LearningSession.create(owner, material),
			100L
		);
		when(sessionRepository.findByUser_IdAndMaterial_IdAndStatus(
			1L,
			10L,
			SessionStatus.ACTIVE
		)).thenReturn(Optional.of(existing));

		var reused = sessionService.create(1L, 10L);

		assertThat(reused.reused()).isTrue();
		assertThat(reused.sessionId()).isEqualTo(100L);
	}

	@Test
	void detailRestoresPendingDiagnosisFromStoredReference() {
		material.markReady(3);
		LearningSession session = LearningSession.create(owner, material);
		ReflectionTestUtils.setField(session, "id", 100L);
		session.startDiagnosis(
			30L,
			UiAction.diagnosisQuestion("진단 질문", 30L)
		);
		when(sessionRepository.findByIdAndUser_Id(100L, 1L))
			.thenReturn(Optional.of(session));
		when(diagnosisService.findPending(100L, 30L))
			.thenReturn(Optional.of(
				new PendingDiagnosisResponse(30L, "진단 질문")
			));

		var response = sessionService.detail(1L, 100L);

		assertThat(response.pendingDiagnosis().diagnosisId()).isEqualTo(30L);
		assertThat(response.pendingDiagnosis().prompt())
			.isEqualTo("진단 질문");
	}

	@Test
	void rejectsProcessingAndOtherOwnersMaterials() {
		when(materialRepository.findByIdForUpdate(10L))
			.thenReturn(Optional.of(material));

		assertThatThrownBy(() -> sessionService.create(1L, 10L))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.MATERIAL_PROCESSING)
			);

		when(materialRepository.findByIdForUpdate(10L))
			.thenReturn(Optional.empty());
		assertThatThrownBy(() -> sessionService.create(1L, 10L))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.MATERIAL_NOT_FOUND)
			);
	}

	@Test
	void movesPageAndKeepsSamePageIdempotent() {
		material.markReady(3);
		LearningSession session = persisted(
			LearningSession.create(owner, material),
			100L
		);
		when(sessionRepository.findOwnedForUpdate(100L, 1L))
			.thenReturn(Optional.of(session));

		var moved = sessionService.movePage(1L, 100L, 2);

		assertThat(moved.currentPage()).isEqualTo(2);
		assertThat(moved.pageStatus()).isEqualTo(PageStatus.NOT_EXPLAINED);
		assertThat(moved.uiActions().getFirst().content())
			.isEqualTo("현재 페이지를 설명할까요?");
		verify(sessionRepository).flush();

		org.mockito.Mockito.clearInvocations(sessionRepository);
		var unchanged = sessionService.movePage(1L, 100L, 2);

		assertThat(unchanged).isEqualTo(moved);
		verify(sessionRepository, never()).flush();
	}

	@Test
	void rejectsOutOfRangeInactiveAndLiveTurnMutations() {
		material.markReady(2);
		LearningSession session = persisted(
			LearningSession.create(owner, material),
			100L
		);
		when(sessionRepository.findOwnedForUpdate(100L, 1L))
			.thenReturn(Optional.of(session));

		assertError(
			() -> sessionService.movePage(1L, 100L, 3),
			ErrorCode.PAGE_OUT_OF_RANGE
		);

		ReflectionTestUtils.setField(session, "activeTurnRequestId", "request-1");
		ReflectionTestUtils.setField(session, "activeTurnStartedAt", NOW);
		assertError(
			() -> sessionService.complete(1L, 100L),
			ErrorCode.SESSION_STATE_CONFLICT
		);

		ReflectionTestUtils.setField(session, "activeTurnRequestId", null);
		ReflectionTestUtils.setField(session, "activeTurnStartedAt", null);
		session.complete();
		assertError(
			() -> sessionService.movePage(1L, 100L, 1),
			ErrorCode.SESSION_NOT_ACTIVE
		);
	}

	private LearningSession persisted(LearningSession session, Long id) {
		ReflectionTestUtils.setField(session, "id", id);
		ReflectionTestUtils.setField(session, "updatedAt", NOW);
		return session;
	}

	private void assertError(Runnable operation, ErrorCode errorCode) {
		assertThatThrownBy(operation::run)
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode()).isEqualTo(errorCode)
			);
	}
}
