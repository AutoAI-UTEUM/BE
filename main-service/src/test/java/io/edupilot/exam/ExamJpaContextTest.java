package io.edupilot.exam;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import io.edupilot.classroom.Classroom;
import io.edupilot.classroom.ClassroomColor;
import io.edupilot.classroom.ClassroomRepository;
import io.edupilot.quiz.QuizOption;
import io.edupilot.quiz.RubricCriterion;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;

@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.MOCK,
	properties = {
		"spring.datasource.url=jdbc:h2:mem:exam-jpa;MODE=MySQL;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.flyway.enabled=false",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"edupilot.cors.allowed-origins=http://localhost:5173",
		"edupilot.ai.base-url=http://localhost:8000",
		"edupilot.ai.internal-token=test-internal-token",
		"edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
		"edupilot.storage.root-directory=build/test-storage/exam-jpa"
	}
)
@ActiveProfiles("jpa-context")
class ExamJpaContextTest {

	@Autowired private UserRepository userRepository;
	@Autowired private ClassroomRepository classroomRepository;
	@Autowired private ExamRepository examRepository;
	@Autowired private ExamQuestionRepository questionRepository;
	@Autowired private ExamSubmissionRepository submissionRepository;
	@Autowired private ExamAnswerRepository answerRepository;

	@Test
	void persistsFourTableAggregateWithoutJpaCascade() {
		User instructor = userRepository.save(User.create(
			"exam-instructor@example.com", "hash", "Instructor", UserRole.INSTRUCTOR
		));
		User learner = userRepository.save(User.create(
			"exam-learner@example.com", "hash", "Learner", UserRole.LEARNER
		));
		Classroom classroom = classroomRepository.save(Classroom.create(
			instructor,
			"Exam classroom",
			LocalDate.of(2026, 9, 1),
			LocalDate.of(2026, 12, 15),
			ClassroomColor.BLUE,
			null,
			"EXAMTESTCODE"
		));
		Exam exam = examRepository.save(Exam.create(
			classroom, 1, "Midterm", null, true
		));
		ExamQuestion question = questionRepository.save(ExamQuestion.create(
			exam,
			1,
			ExamQuestionType.MCQ,
			new BigDecimal("10.00"),
			new ExamPublicQuestion(
				"Choose one",
				List.of(new QuizOption("A", "Answer A"))
			),
			new ExamPrivateAnswer(
				"A",
				null,
				"Explanation",
				null,
				null,
				List.of(new RubricCriterion("Accuracy", BigDecimal.ONE))
			),
			"1.0"
		));
		ExamSubmission submission = submissionRepository.save(ExamSubmission.create(
			exam,
			learner,
			1,
			"request-1",
			new BigDecimal("10.00"),
			Instant.parse("2026-08-03T00:00:00Z")
		));
		answerRepository.saveAndFlush(ExamAnswer.create(
			submission, question, "A", new BigDecimal("10.00")
		));

		assertThat(questionRepository.findByExam_IdOrderByQuestionNo(exam.getId()))
			.singleElement()
			.satisfies(saved -> {
				assertThat(saved.getPublicQuestion().question()).isEqualTo("Choose one");
				assertThat(saved.getPrivateAnswer().answerChoiceId()).isEqualTo("A");
			});
		assertThat(submissionRepository.findByExam_IdAndUser_IdAndRequestId(
			exam.getId(), learner.getId(), "request-1"
		)).isPresent();
		assertThat(answerRepository.findBySubmission_IdOrderByQuestion_Id(
			submission.getId()
		)).hasSize(1);
	}
}
