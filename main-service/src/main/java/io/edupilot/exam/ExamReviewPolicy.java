package io.edupilot.exam;

import org.springframework.stereotype.Component;

@Component
public class ExamReviewPolicy {

	public static boolean isStudentResultAvailable(ExamSubmission submission) {
		return submission.getStatus() == SubmissionStatus.GRADED
			|| submission.getStatus() == SubmissionStatus.GRADING_FAILED
				&& submission.getExamStatus() == ExamStatus.CLOSED;
	}

	public boolean isReviewAvailable(Exam exam, ExamSubmission mySubmission) {
		return exam.getStatus() == ExamStatus.CLOSED
			|| !exam.isAllowRetake() && mySubmission.getStatus() == SubmissionStatus.GRADED;
	}
}
