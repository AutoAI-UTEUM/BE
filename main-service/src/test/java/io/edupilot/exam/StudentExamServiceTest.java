package io.edupilot.exam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import io.edupilot.classroom.ClassroomService;
import io.edupilot.exam.dto.ExamAnswerRequest;
import io.edupilot.exam.dto.SubmitExamRequest;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;

@ExtendWith(MockitoExtension.class)
class StudentExamServiceTest {
	@Mock private ClassroomService classroomService;
	@Mock private ExamRepository examRepository;
	@Mock private ExamQuestionRepository questionRepository;
	@Mock private ExamSubmissionRepository submissionRepository;
	@Mock private ExamAnswerRepository answerRepository;
	@Mock private ExamAttemptStartRepository attemptStartRepository;
	@Mock private ExamReviewPolicy reviewPolicy;
	@Mock private UserRepository userRepository;
	@Mock private ExamSubmissionPersistenceService persistenceService;
	@Mock private Clock clock;
	@Mock private ExamSubmission submission;
	@InjectMocks private StudentExamService service;

	@Test
	void concurrentDuplicateRecoveryStillMasksFailedResults() {
		when(submissionRepository.findByExam_IdAndUser_IdAndRequestId(10L, 2L, "same-request"))
			.thenReturn(Optional.empty(), Optional.of(submission));
		when(persistenceService.create(eq(2L), eq(UserRole.LEARNER), eq(10L), any()))
			.thenThrow(new DataIntegrityViolationException("Concurrent request"));
		when(submission.getId()).thenReturn(20L);
		when(submission.getAttemptNo()).thenReturn(1);
		when(submission.getStatus()).thenReturn(SubmissionStatus.GRADING_FAILED);
		when(submission.getExamStatus()).thenReturn(ExamStatus.PUBLISHED);
		when(submission.getMaxScore()).thenReturn(BigDecimal.TEN);
		ExamQuestion question = ExamQuestion.create(mock(Exam.class), 1, ExamQuestionType.SHORT,
			BigDecimal.TEN, new ExamPublicQuestion("Question", List.of()),
			new ExamPrivateAnswer(null, null, null, "Reference", null, List.of()), "1.0");
		ExamAnswer answer = ExamAnswer.create(submission, question, "Original answer", BigDecimal.TEN);
		answer.recordGrade(BigDecimal.TEN, Verdict.CORRECT, "Revealing feedback");
		when(answerRepository.findBySubmission_IdOrderByQuestion_Id(20L)).thenReturn(List.of(answer));

		var response = service.submit(2L, UserRole.LEARNER, 10L,
			new SubmitExamRequest("same-request", List.of(new ExamAnswerRequest("q1", "Changed answer"))));

		assertThat(response.submissionId()).isEqualTo(20L);
		assertThat(response.attemptNo()).isEqualTo(1);
		assertThat(response.score()).isNull();
		assertThat(response.normalizedScore()).isNull();
		assertThat(response.gradedAt()).isNull();
		assertThat(response.items()).singleElement().satisfies(item -> {
			assertThat(item.answer()).isEqualTo("Original answer");
			assertThat(item.score()).isNull();
			assertThat(item.verdict()).isNull();
			assertThat(item.feedback()).isNull();
		});
		verify(persistenceService).create(eq(2L), eq(UserRole.LEARNER), eq(10L), any());
		assertThat(answer.getScore()).isEqualByComparingTo("10.00");
	}
}
