package io.edupilot.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.session.dto.TurnRequest;
import io.edupilot.session.dto.TurnResponse;
import io.edupilot.session.dto.TurnStateResponse;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class SessionTurnServiceTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Mock
	private TurnClaimService claimService;

	@Mock
	private TurnPersistenceService persistenceService;

	@Test
	void futureQuizAndDiagnosisEventsUseStubBoundaryAndAlwaysRelease() throws Exception {
		SessionTurnService service = new SessionTurnService(
			claimService,
			persistenceService
		);
		TurnResponse response = new TurnResponse(
			"stub:request-1",
			100L,
			List.of(),
			List.of(),
			new TurnStateResponse(1, PageStatus.NOT_EXPLAINED)
		);
		when(persistenceService.persist(
			1L,
			100L,
			"request-1",
			"퀴즈 유형 선택: MCQ",
			null
		)).thenReturn(response);

		var actual = service.execute(
			1L,
			100L,
			new TurnRequest(
				"request-1",
				"QUIZ_TYPE_SELECTED",
				objectMapper.readTree("{\"quizType\":\"MCQ\"}")
			)
		);

		assertThat(actual).isEqualTo(response);
		verify(claimService).claim(1L, 100L, "request-1");
		verify(claimService).release(100L, "request-1");

		when(persistenceService.persist(
			1L,
			100L,
			"request-2",
			"답변",
			30L
		))
			.thenReturn(response);
		service.execute(
			1L,
			100L,
			new TurnRequest(
				"request-2",
				"DIAGNOSIS_ANSWER_SUBMITTED",
				objectMapper.readTree("{\"diagnosisId\":30,\"answer\":\"답변\"}")
			)
		);
		verify(claimService).release(100L, "request-2");
	}

	@Test
	void rejectsUnknownEventAndMalformedPayloadBeforeClaim() throws Exception {
		SessionTurnService service = new SessionTurnService(
			claimService,
			persistenceService
		);

		assertError(
			() -> service.execute(
				1L,
				100L,
				new TurnRequest(
					"request-1",
					"UNKNOWN",
					objectMapper.createObjectNode()
				)
			),
			ErrorCode.UNSUPPORTED_EVENT_TYPE
		);
		assertError(
			() -> service.execute(
				1L,
				100L,
				new TurnRequest(
					"request-2",
					"USER_QUESTION",
					objectMapper.createObjectNode()
				)
			),
			ErrorCode.VALIDATION_FAILED
		);
		verify(claimService, never()).claim(
			org.mockito.ArgumentMatchers.anyLong(),
			org.mockito.ArgumentMatchers.anyLong(),
			anyString()
		);
	}

	private void assertError(Runnable operation, ErrorCode errorCode) {
		assertThatThrownBy(operation::run)
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode()).isEqualTo(errorCode)
			);
	}
}
