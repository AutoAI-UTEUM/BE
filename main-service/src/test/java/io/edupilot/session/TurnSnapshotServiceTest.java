package io.edupilot.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.assessment.QuizAssessment;
import io.edupilot.assessment.QuizAssessmentData;
import io.edupilot.assessment.QuizAssessmentRepository;
import io.edupilot.ai.dto.OutlineResponse;
import io.edupilot.diagnosis.Diagnosis;
import io.edupilot.diagnosis.DiagnosisRepository;
import io.edupilot.diagnosis.DiagnosisStatus;
import io.edupilot.diagnosis.RepairResult;
import io.edupilot.diagnosis.RepairResultRepository;
import io.edupilot.material.LearningMaterial;
import io.edupilot.material.MaterialOverview;
import io.edupilot.material.MaterialOverviewRepository;
import io.edupilot.material.MaterialPage;
import io.edupilot.material.MaterialPageRepository;
import io.edupilot.memory.LearnerMemory;
import io.edupilot.memory.LearnerMemoryCandidate;
import io.edupilot.memory.LearnerMemoryCandidateRepository;
import io.edupilot.memory.LearnerMemoryRepository;
import io.edupilot.memory.MemoryCandidateStatus;
import io.edupilot.memory.MemoryEvidenceRef;
import io.edupilot.user.User;

@ExtendWith(MockitoExtension.class)
class TurnSnapshotServiceTest {

	@Mock
	private LearningSessionRepository sessionRepository;
	@Mock
	private MaterialPageRepository pageRepository;
	@Mock
	private MaterialOverviewRepository overviewRepository;
	@Mock
	private ChatMessageRepository messageRepository;
	@Mock
	private QaThreadRepository qaThreadRepository;
	@Mock
	private QaMessageRepository qaMessageRepository;
	@Mock
	private QuizAssessmentRepository assessmentRepository;
	@Mock
	private LearnerMemoryRepository memoryRepository;
	@Mock
	private LearnerMemoryCandidateRepository candidateRepository;
	@Mock
	private DiagnosisRepository diagnosisRepository;
	@Mock
	private RepairResultRepository repairRepository;

	@Test
	void buildsBoundedFirstPageSnapshotAndExcludesCurrentRequest() {
		LearningSession session = session();
		MaterialPage first = mock(MaterialPage.class);
		MaterialPage second = mock(MaterialPage.class);
		when(first.getTextContent()).thenReturn("x".repeat(8_100));
		when(second.getTextContent()).thenReturn("next");
		when(second.getCaption()).thenReturn("next diagram");
		when(pageRepository.findByMaterial_IdAndPageNumber(10L, 1))
			.thenReturn(Optional.of(first));
		when(pageRepository.findByMaterial_IdAndPageNumber(10L, 2))
			.thenReturn(Optional.of(second));

		ChatMessage previous = ChatMessage.ai(
			session,
			MessageType.QA,
			"이전 답변"
		);
		ReflectionTestUtils.setField(previous, "id", 500L);
		ChatMessage current = ChatMessage.user(
			session,
			"현재 질문",
			"request-1"
		);
		ReflectionTestUtils.setField(current, "id", 501L);
		ChatMessage failed = ChatMessage.user(
			session,
			"실패한 질문",
			"failed-request"
		);
		ReflectionTestUtils.setField(failed, "id", 499L);
		failed.markFailed();

		when(sessionRepository.findByIdAndUser_Id(100L, 1L))
			.thenReturn(Optional.of(session));
		when(messageRepository
			.findRecentContextMessages(
				org.mockito.ArgumentMatchers.eq(100L),
				org.mockito.ArgumentMatchers.any(Pageable.class)
			))
			.thenReturn(List.of(current, previous, failed));
		when(qaThreadRepository
			.findTopBySession_IdAndStatusOrderByUpdatedAtDescIdDesc(
				100L,
				QaThreadStatus.ACTIVE
			))
			.thenReturn(Optional.empty());
		when(assessmentRepository
			.findTop5BySession_IdOrderByCreatedAtDescIdDesc(100L))
			.thenReturn(List.of());
		when(assessmentRepository.findRecentByUserAndMaterial(
			org.mockito.ArgumentMatchers.eq(1L),
			org.mockito.ArgumentMatchers.eq(10L),
			org.mockito.ArgumentMatchers.any(Pageable.class)
		)).thenReturn(List.of());
		when(memoryRepository.findByUser_IdAndMaterial_Id(1L, 10L))
			.thenReturn(Optional.empty());
		when(candidateRepository
			.findByUser_IdAndMaterial_IdAndStatusOrderByCreatedAtDescIdDesc(
				1L,
				10L,
				MemoryCandidateStatus.CANDIDATE
			))
			.thenReturn(List.of());
		when(repairRepository
			.findTopBySession_IdOrderByCreatedAtDescIdDesc(100L))
			.thenReturn(Optional.empty());

		TurnSnapshot snapshot = service().build(1L, 100L, 501L, true);

		assertThat(snapshot.context().get("previousPageText")).isNull();
		assertThat((String) snapshot.context().get("currentPageText"))
			.hasSize(8_000);
		assertThat(snapshot.context().get("nextPageText"))
			.isEqualTo("next\n\n[그림 설명] next diagram");
		assertThat((List<?>) snapshot.context().get("recentMessages"))
			.singleElement()
			.asString()
			.contains("이전 답변")
			.doesNotContain("현재 질문", "실패한 질문");
		assertThat(snapshot.context().get("learnerConfidence")).isNull();
		assertThat(snapshot.context())
			.doesNotContainKey("conversationSummary");
	}

	@Test
	void contextUsesExactlyV06ContractKeysIncludingXaiFileId() {
		LearningSession session = session();
		LearningMaterial material = (LearningMaterial) ReflectionTestUtils
			.getField(session, "material");
		material.replaceXaiFileId("file-turn-snapshot");
		when(sessionRepository.findByIdAndUser_Id(100L, 1L))
			.thenReturn(Optional.of(session));
		when(messageRepository
			.findRecentContextMessages(
				org.mockito.ArgumentMatchers.eq(100L),
				org.mockito.ArgumentMatchers.any(Pageable.class)
			))
			.thenReturn(List.of());
		when(qaThreadRepository
			.findTopBySession_IdAndStatusOrderByUpdatedAtDescIdDesc(
				100L,
				QaThreadStatus.ACTIVE
			))
			.thenReturn(Optional.empty());
		when(assessmentRepository
			.findTop5BySession_IdOrderByCreatedAtDescIdDesc(100L))
			.thenReturn(List.of());
		when(assessmentRepository.findRecentByUserAndMaterial(
			org.mockito.ArgumentMatchers.eq(1L),
			org.mockito.ArgumentMatchers.eq(10L),
			org.mockito.ArgumentMatchers.any(Pageable.class)
		)).thenReturn(List.of());
		when(memoryRepository.findByUser_IdAndMaterial_Id(1L, 10L))
			.thenReturn(Optional.of(memory(session)));
		when(candidateRepository
			.findByUser_IdAndMaterial_IdAndStatusOrderByCreatedAtDescIdDesc(
				1L,
				10L,
				MemoryCandidateStatus.CANDIDATE
			))
			.thenReturn(List.of());
		when(repairRepository
			.findTopBySession_IdOrderByCreatedAtDescIdDesc(100L))
			.thenReturn(Optional.empty());

		TurnSnapshot snapshot = service().build(1L, 100L, 501L, true);

		assertThat(snapshot.context()).containsOnlyKeys(
			"xaiFileId",
			"currentPageText",
			"previousPageText",
			"nextPageText",
			"recentMessages",
			"qaThreadDigest",
			"quizAssessments",
			"learnerMemoryDigest",
			"learnerLevel",
			"learnerConfidence",
			"pendingDiagnosis",
			"latestRepair",
			"memory"
		);
		assertThat(snapshot.context().get("learnerMemoryDigest"))
			.isEqualTo("promoted digest");
		assertThat(snapshot.context().get("xaiFileId"))
			.isEqualTo("file-turn-snapshot");
		assertThat(snapshot.xaiFileAttached()).isTrue();
	}

	@Test
	void includesConversationSummaryOnlyWhenStored() {
		LearningSession session = session();
		session.applyConversationSummary("초반 대화 요약", 42L);
		when(sessionRepository.findByIdAndUser_Id(100L, 1L))
			.thenReturn(Optional.of(session));

		TurnSnapshot snapshot = service().build(1L, 100L, 501L, false);

		assertThat(snapshot.context())
			.containsEntry("conversationSummary", "초반 대화 요약")
			.hasSize(14);
	}

	@Test
	void excludesAllPageTextsWithoutLoadingPages() {
		LearningSession session = session();
		LearningMaterial material = (LearningMaterial) ReflectionTestUtils
			.getField(session, "material");
		material.replaceXaiFileId("file-hidden-with-page-context");
		when(sessionRepository.findByIdAndUser_Id(100L, 1L))
			.thenReturn(Optional.of(session));

		TurnSnapshot snapshot = service().build(1L, 100L, 501L, false);

		assertThat(snapshot.context())
			.containsEntry("xaiFileId", null)
			.containsEntry("currentPageText", null)
			.containsEntry("previousPageText", null)
			.containsEntry("nextPageText", null)
			.hasSize(13);
		assertThat(snapshot.xaiFileAttached()).isFalse();
		org.mockito.Mockito.verifyNoInteractions(pageRepository);
	}

	@Test
	void blankXaiFileIdIsNotMarkedAttached() {
		LearningSession session = session();
		LearningMaterial material = (LearningMaterial) ReflectionTestUtils
			.getField(session, "material");
		ReflectionTestUtils.setField(material, "xaiFileId", "   ");
		when(sessionRepository.findByIdAndUser_Id(100L, 1L))
			.thenReturn(Optional.of(session));

		TurnSnapshot snapshot = service().build(1L, 100L, 501L, true);

		assertThat(snapshot.context())
			.containsEntry("xaiFileId", "   ");
		assertThat(snapshot.xaiFileAttached()).isFalse();
	}

	@Test
	@SuppressWarnings("unchecked")
	void conversationResetExcludesOldConversationContextOnly() {
		Instant resetAt = Instant.parse("2026-07-25T10:00:00Z");
		LearningSession session = session();
		session.startNewConversation(resetAt);
		session.startDiagnosis(
			30L,
			UiAction.diagnosisQuestion("진단 질문", 30L)
		);

		ChatMessage oldMessage = ChatMessage.ai(
			session,
			MessageType.QA,
			"이전 대화"
		);
		ReflectionTestUtils.setField(oldMessage, "id", 500L);
		ReflectionTestUtils.setField(
			oldMessage,
			"createdAt",
			resetAt.minusSeconds(1)
		);
		ChatMessage markerTimeMessage = ChatMessage.ai(
			session,
			MessageType.QA,
			"마커 시각 대화"
		);
		ReflectionTestUtils.setField(markerTimeMessage, "id", 503L);
		ReflectionTestUtils.setField(
			markerTimeMessage,
			"createdAt",
			resetAt
		);
		ChatMessage newMessage = ChatMessage.ai(
			session,
			MessageType.QA,
			"새 대화"
		);
		ReflectionTestUtils.setField(newMessage, "id", 501L);
		ReflectionTestUtils.setField(
			newMessage,
			"createdAt",
			resetAt.plusSeconds(1)
		);
		ChatMessage current = ChatMessage.user(
			session,
			"현재 질문",
			"request-1"
		);
		ReflectionTestUtils.setField(current, "id", 502L);
		ReflectionTestUtils.setField(
			current,
			"createdAt",
			resetAt.plusSeconds(2)
		);

		QaThread oldThread = mock(QaThread.class);
		when(oldThread.getCreatedAt()).thenReturn(resetAt.minusSeconds(1));
		RepairResult oldRepair = mock(RepairResult.class);
		when(oldRepair.getCreatedAt()).thenReturn(resetAt.minusSeconds(1));
		Diagnosis diagnosis = mock(Diagnosis.class);
		when(diagnosis.getId()).thenReturn(30L);
		when(diagnosis.getSessionId()).thenReturn(100L);
		when(diagnosis.getDiagnosticPrompt()).thenReturn("진단 질문");
		when(diagnosis.getStatus()).thenReturn(DiagnosisStatus.PENDING);

		QuizAssessment assessment = mock(QuizAssessment.class);
		when(assessment.getId()).thenReturn(70L);
		when(assessment.isPassed()).thenReturn(true);
		when(assessment.getAssessment()).thenReturn(new QuizAssessmentData(
			"1.0",
			"평가 요약",
			List.of("강점"),
			List.of("약점"),
			List.of(),
			"다음 학습",
			List.of(),
			List.of("근거")
		));
		LearnerMemoryCandidate candidate = candidate(session, 1L, 100L);

		when(sessionRepository.findByIdAndUser_Id(100L, 1L))
			.thenReturn(Optional.of(session));
		when(messageRepository
			.findRecentContextMessages(
				org.mockito.ArgumentMatchers.eq(100L),
				org.mockito.ArgumentMatchers.any(Pageable.class)
			))
			.thenReturn(List.of(
				current,
				newMessage,
				markerTimeMessage,
				oldMessage
			));
		when(qaThreadRepository
			.findTopBySession_IdAndStatusOrderByUpdatedAtDescIdDesc(
				100L,
				QaThreadStatus.ACTIVE
			))
			.thenReturn(Optional.of(oldThread));
		when(assessmentRepository
			.findTop5BySession_IdOrderByCreatedAtDescIdDesc(100L))
			.thenReturn(List.of(assessment));
		when(assessmentRepository.findRecentByUserAndMaterial(
			org.mockito.ArgumentMatchers.eq(1L),
			org.mockito.ArgumentMatchers.eq(10L),
			org.mockito.ArgumentMatchers.any(Pageable.class)
		)).thenReturn(List.of(assessment));
		when(memoryRepository.findByUser_IdAndMaterial_Id(1L, 10L))
			.thenReturn(Optional.of(memory(session)));
		when(candidateRepository
			.findByUser_IdAndMaterial_IdAndStatusOrderByCreatedAtDescIdDesc(
				1L,
				10L,
				MemoryCandidateStatus.CANDIDATE
			))
			.thenReturn(List.of(candidate));
		when(diagnosisRepository.findById(30L))
			.thenReturn(Optional.of(diagnosis));
		when(repairRepository
			.findTopBySession_IdOrderByCreatedAtDescIdDesc(100L))
			.thenReturn(Optional.of(oldRepair));

		TurnSnapshot snapshot = service().build(1L, 100L, 502L, true);

		assertThat((List<?>) snapshot.context().get("recentMessages"))
			.singleElement()
			.asString()
			.contains("새 대화")
			.doesNotContain("이전 대화", "마커 시각 대화", "현재 질문");
		assertThat(snapshot.context().get("qaThreadDigest")).isNull();
		assertThat(snapshot.context().get("latestRepair")).isNull();
		assertThat((Map<String, Object>) snapshot.context()
			.get("pendingDiagnosis"))
			.containsEntry("diagnosisId", 30L);
		assertThat((List<?>) snapshot.context().get("quizAssessments"))
			.singleElement();
		assertThat(snapshot.context().get("learnerMemoryDigest"))
			.isEqualTo("promoted digest");
		assertThat(snapshot.context().get("learnerConfidence"))
			.isEqualTo("HIGH");
		assertThat((List<?>) ((Map<?, ?>) snapshot.context().get("memory"))
			.get("temporaryCandidates"))
			.singleElement();
		assertThat(snapshot.context()).hasSize(13);
	}

	@Test
	void includesOnlyLatestTenCandidatesForCurrentSession() {
		LearningSession session = session();
		when(sessionRepository.findByIdAndUser_Id(100L, 1L))
			.thenReturn(Optional.of(session));
		when(messageRepository
			.findRecentContextMessages(
				org.mockito.ArgumentMatchers.eq(100L),
				org.mockito.ArgumentMatchers.any(Pageable.class)
			))
			.thenReturn(List.of());
		when(qaThreadRepository
			.findTopBySession_IdAndStatusOrderByUpdatedAtDescIdDesc(
				100L,
				QaThreadStatus.ACTIVE
			))
			.thenReturn(Optional.empty());
		when(assessmentRepository
			.findTop5BySession_IdOrderByCreatedAtDescIdDesc(100L))
			.thenReturn(List.of());
		when(assessmentRepository.findRecentByUserAndMaterial(
			org.mockito.ArgumentMatchers.eq(1L),
			org.mockito.ArgumentMatchers.eq(10L),
			org.mockito.ArgumentMatchers.any(Pageable.class)
		)).thenReturn(List.of());
		when(memoryRepository.findByUser_IdAndMaterial_Id(1L, 10L))
			.thenReturn(Optional.empty());
		List<LearnerMemoryCandidate> candidates =
			IntStream.rangeClosed(1, 12)
				.mapToObj(index -> candidate(
					session,
					(long) index,
					index == 1 ? 200L : 100L
				))
				.toList();
		when(candidateRepository
			.findByUser_IdAndMaterial_IdAndStatusOrderByCreatedAtDescIdDesc(
				1L,
				10L,
				MemoryCandidateStatus.CANDIDATE
			))
			.thenReturn(candidates);
		when(repairRepository
			.findTopBySession_IdOrderByCreatedAtDescIdDesc(100L))
			.thenReturn(Optional.empty());

		TurnSnapshot snapshot = service().build(1L, 100L, 501L, true);

		@SuppressWarnings("unchecked")
		List<java.util.Map<String, Object>> temporaryCandidates =
			(List<java.util.Map<String, Object>>) (
				(java.util.Map<String, Object>) snapshot.context()
					.get("memory")
			).get("temporaryCandidates");
		assertThat(temporaryCandidates)
			.hasSize(10)
			.extracting(candidate -> candidate.get("candidateId"))
			.containsExactlyElementsOf(
				IntStream.rangeClosed(2, 11)
					.mapToObj(Long::valueOf)
					.toList()
			);
		assertThat(temporaryCandidates)
			.allSatisfy(candidate -> assertThat(candidate)
				.containsOnlyKeys(
					"candidateId",
					"type",
					"content",
					"confidence",
					"evidenceRefs"
				));
		assertThat(snapshot.context()).hasSize(13);
	}

	@Test
	@SuppressWarnings("unchecked")
	void quizSnapshotBuildsOrderedCaptionMergedCheckpointContext() {
		LearningSession session = session();
		ReflectionTestUtils.setField(session, "currentPage", 2);
		LearningMaterial material = (LearningMaterial) ReflectionTestUtils
			.getField(session, "material");
		MaterialPage first = MaterialPage.create(material, 1, "first text");
		first.updateCaption("first diagram");
		MaterialPage second = MaterialPage.create(material, 2, "second text");
		when(sessionRepository.findByIdAndUser_Id(100L, 1L))
			.thenReturn(Optional.of(session));
		when(pageRepository.findByMaterial_IdAndPageNumber(10L, 1))
			.thenReturn(Optional.of(first));
		when(pageRepository.findByMaterial_IdAndPageNumber(10L, 2))
			.thenReturn(Optional.of(second));
		when(pageRepository
			.findByMaterial_IdAndPageNumberBetweenOrderByPageNumberAsc(
				10L,
				1,
				2
			))
			.thenReturn(List.of(first, second));
		when(overviewRepository.findByMaterial_Id(10L))
			.thenReturn(Optional.of(overview(
				material,
				2,
				new OutlineResponse.QuizCheckpoint(
					2,
					new OutlineResponse.Coverage(1, 2)
				)
			)));

		TurnSnapshot snapshot = service().buildQuiz(1L, 100L, 501L);

		Map<String, Object> quizContext = (Map<String, Object>)
			snapshot.context().get("quizContext");
		assertThat((Map<String, Object>) quizContext.get("coverage"))
			.containsEntry("startPage", 1)
			.containsEntry("endPage", 2);
		assertThat((List<Map<String, Object>>) quizContext.get("pages"))
			.containsExactly(
				Map.of(
					"pageNumber", 1,
					"text", "first text\n\n[그림 설명] first diagram"
				),
				Map.of("pageNumber", 2, "text", "second text")
			);
		assertThat(snapshot.context().get("currentPageText"))
			.isEqualTo("second text");
	}

	@Test
	@SuppressWarnings("unchecked")
	void quizCheckpointContextDropsTextAfterTwelveThousandCharacters() {
		LearningSession session = session();
		ReflectionTestUtils.setField(session, "currentPage", 3);
		ReflectionTestUtils.setField(
			ReflectionTestUtils.getField(session, "material"),
			"pageCount",
			3
		);
		LearningMaterial material = (LearningMaterial) ReflectionTestUtils
			.getField(session, "material");
		MaterialPage first = MaterialPage.create(
			material,
			1,
			"a".repeat(11_999)
		);
		MaterialPage second = MaterialPage.create(material, 2, "bc");
		MaterialPage third = MaterialPage.create(material, 3, "tail");
		when(sessionRepository.findByIdAndUser_Id(100L, 1L))
			.thenReturn(Optional.of(session));
		when(pageRepository.findByMaterial_IdAndPageNumber(10L, 2))
			.thenReturn(Optional.of(second));
		when(pageRepository.findByMaterial_IdAndPageNumber(10L, 3))
			.thenReturn(Optional.of(third));
		when(pageRepository
			.findByMaterial_IdAndPageNumberBetweenOrderByPageNumberAsc(
				10L,
				1,
				3
			))
			.thenReturn(List.of(first, second, third));
		when(overviewRepository.findByMaterial_Id(10L))
			.thenReturn(Optional.of(overview(
				material,
				3,
				new OutlineResponse.QuizCheckpoint(
					3,
					new OutlineResponse.Coverage(1, 3)
				)
			)));

		TurnSnapshot snapshot = service().buildQuiz(1L, 100L, 501L);

		Map<String, Object> quizContext = (Map<String, Object>)
			snapshot.context().get("quizContext");
		List<Map<String, Object>> pages = (List<Map<String, Object>>)
			quizContext.get("pages");
		assertThat(pages)
			.extracting(page -> (String) page.get("text"))
			.containsExactly("a".repeat(11_999), "b", "");
		assertThat(pages.stream()
			.mapToInt(page -> ((String) page.get("text")).length())
			.sum()).isEqualTo(12_000);
	}

	@Test
	void nonCheckpointQuizKeepsCurrentPageContextOnly() {
		LearningSession session = session();
		ReflectionTestUtils.setField(session, "currentPage", 2);
		LearningMaterial material = (LearningMaterial) ReflectionTestUtils
			.getField(session, "material");
		MaterialPage first = MaterialPage.create(material, 1, "first");
		MaterialPage second = MaterialPage.create(material, 2, "current");
		when(sessionRepository.findByIdAndUser_Id(100L, 1L))
			.thenReturn(Optional.of(session));
		when(pageRepository.findByMaterial_IdAndPageNumber(10L, 1))
			.thenReturn(Optional.of(first));
		when(pageRepository.findByMaterial_IdAndPageNumber(10L, 2))
			.thenReturn(Optional.of(second));
		when(overviewRepository.findByMaterial_Id(10L))
			.thenReturn(Optional.of(overview(
				material,
				2,
				new OutlineResponse.QuizCheckpoint(
					1,
					new OutlineResponse.Coverage(1, 1)
				)
			)));

		TurnSnapshot snapshot = service().buildQuiz(1L, 100L, 501L);

		assertThat(snapshot.context())
			.containsEntry("currentPageText", "current")
			.containsEntry("quizContext", null);
	}

	private TurnSnapshotService service() {
		return new TurnSnapshotService(
			sessionRepository,
			pageRepository,
			overviewRepository,
			new io.edupilot.material.MaterialPageTextMerger(),
			messageRepository,
			qaThreadRepository,
			qaMessageRepository,
			assessmentRepository,
			memoryRepository,
			candidateRepository,
			diagnosisRepository,
			repairRepository
		);
	}

	private MaterialOverview overview(
		LearningMaterial material,
		int totalPages,
		OutlineResponse.QuizCheckpoint checkpoint
	) {
		MaterialOverview overview = MaterialOverview.createPending(material);
		overview.markReady(
			"overview",
			new OutlineResponse(
				"1.0",
				"summary",
				List.of(new OutlineResponse.Section(
					"section",
					1,
					totalPages,
					List.of("keyword")
				)),
				List.of(checkpoint),
				totalPages
			)
		);
		return overview;
	}

	private LearningSession session() {
		User user = User.create("user@example.com", "hash", "사용자");
		ReflectionTestUtils.setField(user, "id", 1L);
		LearningMaterial material = LearningMaterial.create(
			user,
			"자료",
			"materials/test.pdf"
		);
		ReflectionTestUtils.setField(material, "id", 10L);
		ReflectionTestUtils.setField(material, "pageCount", 2);
		LearningSession session = LearningSession.create(user, material);
		ReflectionTestUtils.setField(session, "id", 100L);
		return session;
	}

	private LearnerMemoryCandidate candidate(
		LearningSession session,
		Long id,
		Long evidenceSessionId
	) {
		User user = (User) ReflectionTestUtils.getField(session, "user");
		LearningMaterial material = (LearningMaterial)
			ReflectionTestUtils.getField(session, "material");
		LearnerMemoryCandidate candidate = LearnerMemoryCandidate.create(
			user,
			material,
			"WEAKNESS",
			"candidate-" + id,
			new BigDecimal("0.80"),
			List.of(new MemoryEvidenceRef(
				"TURN",
				500L + id,
				evidenceSessionId,
				"evidence-" + id
			)),
			"1.0"
		);
		ReflectionTestUtils.setField(candidate, "id", id);
		return candidate;
	}

	private LearnerMemory memory(LearningSession session) {
		User user = (User) ReflectionTestUtils.getField(session, "user");
		LearningMaterial material = (LearningMaterial)
			ReflectionTestUtils.getField(session, "material");
		LearnerMemory memory = LearnerMemory.create(user, material);
		ReflectionTestUtils.setField(memory, "strengths", List.of("strength"));
		ReflectionTestUtils.setField(memory, "weaknesses", List.of("weakness"));
		ReflectionTestUtils.setField(
			memory,
			"misconceptions",
			List.of("misconception")
		);
		ReflectionTestUtils.setField(
			memory,
			"explanationPreferences",
			List.of("preference")
		);
		ReflectionTestUtils.setField(
			memory,
			"preferredQuizTypes",
			List.of("MCQ")
		);
		ReflectionTestUtils.setField(memory, "targetDifficulty", "BALANCED");
		ReflectionTestUtils.setField(
			memory,
			"nextCoachingGoals",
			List.of("goal")
		);
		ReflectionTestUtils.setField(
			memory,
			"memoryDigest",
			"promoted digest"
		);
		return memory;
	}
}
