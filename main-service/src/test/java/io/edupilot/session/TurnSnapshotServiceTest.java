package io.edupilot.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.assessment.QuizAssessmentRepository;
import io.edupilot.diagnosis.DiagnosisRepository;
import io.edupilot.diagnosis.RepairResultRepository;
import io.edupilot.material.LearningMaterial;
import io.edupilot.material.MaterialPage;
import io.edupilot.material.MaterialPageRepository;
import io.edupilot.memory.LearnerMemoryCandidateRepository;
import io.edupilot.memory.LearnerMemoryRepository;
import io.edupilot.memory.MemoryCandidateStatus;
import io.edupilot.user.User;

@ExtendWith(MockitoExtension.class)
class TurnSnapshotServiceTest {

	@Mock
	private LearningSessionRepository sessionRepository;
	@Mock
	private MaterialPageRepository pageRepository;
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

		when(sessionRepository.findByIdAndUser_Id(100L, 1L))
			.thenReturn(Optional.of(session));
		when(messageRepository
			.findBySession_IdOrderByCreatedAtDescIdDesc(
				org.mockito.ArgumentMatchers.eq(100L),
				org.mockito.ArgumentMatchers.any(Pageable.class)
			))
			.thenReturn(List.of(current, previous));
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

		TurnSnapshot snapshot = service().build(1L, 100L, 501L);

		assertThat(snapshot.context().get("previousPageText")).isNull();
		assertThat((String) snapshot.context().get("currentPageText"))
			.hasSize(8_000);
		assertThat(snapshot.context().get("nextPageText"))
			.isEqualTo("next");
		assertThat((List<?>) snapshot.context().get("recentMessages"))
			.singleElement()
			.asString()
			.contains("이전 답변")
			.doesNotContain("현재 질문");
		assertThat(snapshot.context().get("learnerConfidence")).isNull();
		assertThat(snapshot.context().get("conversationSummary")).isNull();
	}

	private TurnSnapshotService service() {
		return new TurnSnapshotService(
			sessionRepository,
			pageRepository,
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
}
