package io.edupilot.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.edupilot.ai.AiClient;
import io.edupilot.ai.AiClientException;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.memory.LearnerMemoryPromotionService;
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
	private TurnPreparationService preparationService;
	@Mock
	private TurnSnapshotService snapshotService;
	@Mock
	private AiClient aiClient;
	@Mock
	private TurnResponseValidator responseValidator;
	@Mock
	private TurnPersistenceService persistenceService;
	@Mock
	private LearnerMemoryPromotionService memoryPromotionService;

	@Test
	void retryableFailureUsesNewTurnIdAndAlwaysReleases() throws Exception {
		when(preparationService.prepare(
			1L,
			100L,
			"request-1",
			"질문",
			null
		)).thenReturn(new PreparedTurn(501L));
		when(snapshotService.build(1L, 100L, 501L))
			.thenReturn(new TurnSnapshot(
				Map.of("sessionId", 100L),
				Map.of(
					"qaThreadDigest",
					Map.of("threadRef", "qa-30")
				),
				10L
			));
		io.edupilot.ai.dto.TurnResponse aiResponse = aiResponse("ignored");
		when(aiClient.executeTurn(any()))
			.thenThrow(new AiClientException(
				ErrorCode.AI_SERVICE_UNAVAILABLE,
				true,
				null
			))
			.thenAnswer(invocation -> {
				io.edupilot.ai.dto.TurnRequest aiRequest =
					invocation.getArgument(0);
				return aiResponse(aiRequest.turnId());
			});
		TurnResponse publicResponse = new TurnResponse(
			"successful-turn",
			100L,
			List.of(),
			List.of(),
			new TurnStateResponse(1, PageStatus.EXPLAINED, null)
		);
		when(persistenceService.persist(
			any(),
			any(),
			anyString(),
			any(),
			any(),
			any(),
			any()
		)).thenReturn(new PersistedTurn(
			publicResponse.turnId(),
			publicResponse.sessionId(),
			publicResponse.messages(),
			publicResponse.uiActions(),
			publicResponse.state(),
			null,
			10L
		));

		TurnResponse actual = service().execute(
			1L,
			100L,
			new TurnRequest(
				"request-1",
				"USER_QUESTION",
				objectMapper.readTree("{\"message\":\"질문\"}")
			)
		);

		assertThat(actual).isEqualTo(publicResponse);
		ArgumentCaptor<io.edupilot.ai.dto.TurnRequest> requests =
			ArgumentCaptor.forClass(
				io.edupilot.ai.dto.TurnRequest.class
			);
		verify(aiClient, org.mockito.Mockito.times(2))
			.executeTurn(requests.capture());
		assertThat(requests.getAllValues())
			.extracting(io.edupilot.ai.dto.TurnRequest::turnId)
			.doesNotHaveDuplicates();
		verify(responseValidator).validate(
			any(),
			eq(requests.getAllValues().get(1).turnId()),
			eq("qa-30")
		);
		verify(claimService).claim(1L, 100L, "request-1");
		verify(claimService).release(100L, "request-1");
	}

	@Test
	void rejectsUnknownEventAndMalformedPayloadBeforeClaim()
		throws Exception {
		assertError(
			() -> service().execute(
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
			() -> service().execute(
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

	private SessionTurnService service() {
		return new SessionTurnService(
			claimService,
			preparationService,
			snapshotService,
			aiClient,
			responseValidator,
			persistenceService,
			memoryPromotionService
		);
	}

	private io.edupilot.ai.dto.TurnResponse aiResponse(String turnId) {
		return new io.edupilot.ai.dto.TurnResponse(
			"1.0",
			turnId,
			"ANSWER_USER_QUESTION",
			List.of(),
			List.of(),
			Map.of(),
			List.of(),
			List.of(),
			null,
			null
		);
	}

	private void assertError(Runnable operation, ErrorCode errorCode) {
		assertThatThrownBy(operation::run)
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode()).isEqualTo(errorCode)
			);
	}
}
