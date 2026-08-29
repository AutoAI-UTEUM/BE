package io.edupilot.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.diagnosis.DiagnosisService;
import io.edupilot.material.LearningMaterialRepository;
import io.edupilot.material.MaterialPageRepository;
import io.edupilot.material.MaterialOverviewRepository;
import io.edupilot.memory.LearnerMemoryCandidateRepository;
import io.edupilot.quiz.QuizProperties;
import io.edupilot.quiz.QuizService;
import io.edupilot.user.UserRepository;

@JdbcTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:turn-persistence;MODE=MySQL;DB_CLOSE_DELAY=-1",
	"spring.flyway.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
	SessionPageRecordRepository.class,
	TurnPersistenceService.class,
	QuizProposalPolicy.class,
	UiActionResolver.class,
	TurnPersistenceTransactionTest.ClockConfig.class
})
@Sql(scripts = "/session-page-record-test-schema.sql")
class TurnPersistenceTransactionTest {

	private static final Instant NOW = Instant.parse(
		"2026-08-01T12:00:00Z"
	);

	@Autowired
	private TurnPersistenceService persistenceService;
	@Autowired
	private SessionPageRecordRepository pageRecordRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@MockitoBean
	private LearningSessionRepository sessionRepository;
	@MockitoBean
	private ChatMessageRepository messageRepository;
	@MockitoBean
	private QaThreadRepository qaThreadRepository;
	@MockitoBean
	private QaMessageRepository qaMessageRepository;
	@MockitoBean
	private LearnerMemoryCandidateRepository candidateRepository;
	@MockitoBean
	private UserRepository userRepository;
	@MockitoBean
	private LearningMaterialRepository materialRepository;
	@MockitoBean
	private MaterialPageRepository materialPageRepository;
	@MockitoBean
	private MaterialOverviewRepository materialOverviewRepository;
	@MockitoBean
	private QuizService quizService;
	@MockitoBean
	private QuizProperties quizProperties;
	@MockitoBean
	private DiagnosisService diagnosisService;
	@MockitoBean
	private ConversationSummaryDispatcher summaryDispatcher;

	@BeforeEach
	void clearRecords() {
		jdbcTemplate.update("DELETE FROM session_page_records");
	}

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	void rollsBackExplainedPageWhenLaterTurnPersistenceFails() {
		LearningSession session = org.mockito.Mockito.mock(
			LearningSession.class
		);
		when(session.getStatus()).thenReturn(SessionStatus.ACTIVE);
		when(session.getActiveTurnRequestId()).thenReturn("request-1");
		when(session.getPageStatus())
			.thenReturn(PageStatus.EXPLAINING, PageStatus.EXPLAINED);
		when(session.getCurrentPage()).thenReturn(1);
		when(session.getMaterialPageCount()).thenReturn(3);
		when(session.getMaterialId()).thenReturn(10L);
		when(sessionRepository.findOwnedForUpdate(100L, 1L))
			.thenReturn(Optional.of(session));
		when(materialPageRepository.findTextLengthByMaterialIdAndPageNumber(
			10L,
			1
		)).thenReturn(Optional.of(200));
		when(quizProperties.proposalMinPageTextLength()).thenReturn(200);
		doThrow(new IllegalStateException("candidate flush failed"))
			.when(candidateRepository).flush();

		assertThatThrownBy(() -> persistenceService.persist(
			1L,
			100L,
			"request-1",
			TurnEventType.EXPLAIN_CURRENT_PAGE,
			null,
			501L,
			new io.edupilot.ai.dto.TurnResponse(
				"1.0",
				"turn-1",
				"EXPLAIN",
				List.of(),
				List.of(),
				Map.of("pageStatus", "EXPLAINED"),
				List.of(),
				null,
				List.of(),
				null,
				null
			)
		)).isInstanceOf(IllegalStateException.class);

		assertThat(pageRecordRepository.countBySessionId(100L)).isZero();
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ClockConfig {

		@Bean
		Clock clock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}
	}
}
