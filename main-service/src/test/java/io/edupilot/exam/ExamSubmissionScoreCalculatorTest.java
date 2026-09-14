package io.edupilot.exam;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.classroom.Classroom;
import io.edupilot.classroom.ClassroomColor;
import io.edupilot.user.User;
import io.edupilot.user.UserRole;

class ExamSubmissionScoreCalculatorTest {

	private final ExamSubmissionScoreCalculator calculator =
		new ExamSubmissionScoreCalculator();

	@Test
	void usesManualScoresWithoutOverwritingAiScores() {
		User learner = User.create(
			"learner@example.com", "hash", "Learner", UserRole.LEARNER
		);
		Exam exam = exam();
		ExamSubmission submission = ExamSubmission.create(
			exam, learner, 1, "request", new BigDecimal("20.00"), Instant.EPOCH
		);
		ExamAnswer first = answer(submission, question(exam, 1, "10.00"), "4.00");
		ExamAnswer second = answer(submission, question(exam, 2, "10.00"), "10.00");
		first.recordManualScore(
			new BigDecimal("7.00"), 1L, Instant.parse("2026-08-03T00:00:00Z")
		);

		ExamSubmissionScoreCalculator.Result result = calculator.calculate(
			submission, List.of(first, second)
		);

		assertThat(first.getScore()).isEqualByComparingTo("4.00");
		assertThat(first.effectiveScore()).isEqualByComparingTo("7.00");
		assertThat(result.score()).isEqualByComparingTo("17.00");
		assertThat(result.normalizedScore()).isEqualByComparingTo("85.00");
	}

	private Exam exam() {
		User instructor = User.create(
			"instructor@example.com", "hash", "Instructor", UserRole.INSTRUCTOR
		);
		Classroom classroom = Classroom.create(
			instructor,
			"Classroom",
			LocalDate.of(2026, 9, 1),
			LocalDate.of(2026, 12, 1),
			ClassroomColor.BLUE,
			null,
			"CODE"
		);
		Exam exam = Exam.create(classroom, 1, "Exam", null, false);
		ReflectionTestUtils.setField(exam, "id", 1L);
		return exam;
	}

	private ExamQuestion question(Exam exam, int questionNo, String points) {
		ExamQuestion question = ExamQuestion.create(
			exam,
			questionNo,
			ExamQuestionType.SHORT,
			new BigDecimal(points),
			new ExamPublicQuestion("Question", List.of()),
			new ExamPrivateAnswer(null, null, null, "Reference", null, List.of()),
			"1.0"
		);
		ReflectionTestUtils.setField(question, "id", (long) questionNo);
		return question;
	}

	private ExamAnswer answer(
		ExamSubmission submission,
		ExamQuestion question,
		String score
	) {
		ExamAnswer answer = ExamAnswer.create(
			submission, question, "answer", question.getPoints()
		);
		answer.recordGrade(new BigDecimal(score), Verdict.PARTIAL, null);
		return answer;
	}
}
