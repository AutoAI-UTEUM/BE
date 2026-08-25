package io.edupilot;

import org.mockito.Mockito;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import io.edupilot.assessment.QuizAssessmentRepository;
import io.edupilot.diagnosis.DiagnosisRepository;
import io.edupilot.diagnosis.RepairResultRepository;
import io.edupilot.memory.LearnerMemoryCandidateRepository;
import io.edupilot.memory.LearnerMemoryRepository;
import io.edupilot.session.QaMessageRepository;
import io.edupilot.session.QaThreadRepository;
import io.edupilot.session.SessionPageRecordRepository;

@Configuration
@Profile("test")
class LearningSupportTestRepositoryConfig {

	@Bean
	QuizAssessmentRepository quizAssessmentRepository() {
		return Mockito.mock(QuizAssessmentRepository.class);
	}

	@Bean
	DiagnosisRepository diagnosisRepository() {
		return Mockito.mock(DiagnosisRepository.class);
	}

	@Bean
	RepairResultRepository repairResultRepository() {
		return Mockito.mock(RepairResultRepository.class);
	}

	@Bean
	LearnerMemoryRepository learnerMemoryRepository() {
		return Mockito.mock(LearnerMemoryRepository.class);
	}

	@Bean
	LearnerMemoryCandidateRepository learnerMemoryCandidateRepository() {
		return Mockito.mock(LearnerMemoryCandidateRepository.class);
	}

	@Bean
	QaThreadRepository qaThreadRepository() {
		return Mockito.mock(QaThreadRepository.class);
	}

	@Bean
	QaMessageRepository qaMessageRepository() {
		return Mockito.mock(QaMessageRepository.class);
	}

	@Bean
	SessionPageRecordRepository sessionPageRecordRepository() {
		return Mockito.mock(SessionPageRecordRepository.class);
	}
}
