package io.edupilot.diagnosis;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.ai.dto.DiagnosisResponse;
import io.edupilot.quiz.QuizPostGradingContext;
import io.edupilot.quiz.QuizSubmission;
import io.edupilot.quiz.QuizSubmissionRepository;
import io.edupilot.session.LearningSession;
import io.edupilot.session.LearningSessionRepository;
import io.edupilot.session.SessionStatus;
import io.edupilot.session.UiAction;

@Service
public class DiagnosisPersistenceService {

	private final LearningSessionRepository sessionRepository;
	private final QuizSubmissionRepository submissionRepository;
	private final DiagnosisRepository diagnosisRepository;

	public DiagnosisPersistenceService(
		LearningSessionRepository sessionRepository,
		QuizSubmissionRepository submissionRepository,
		DiagnosisRepository diagnosisRepository
	) {
		this.sessionRepository = sessionRepository;
		this.submissionRepository = submissionRepository;
		this.diagnosisRepository = diagnosisRepository;
	}

	@Transactional
	public Optional<UiAction> savePending(
		QuizPostGradingContext context,
		DiagnosisResponse response
	) {
		LearningSession session = sessionRepository.findOwnedForUpdate(
				context.sessionId(),
				context.userId()
			)
			.orElse(null);
		if (session == null || session.getStatus() != SessionStatus.ACTIVE) {
			return Optional.empty();
		}
		Diagnosis existing = diagnosisRepository
			.findBySubmission_Id(context.submissionId())
			.orElse(null);
		if (existing != null) {
			UiAction action = UiAction.diagnosisQuestion(
				existing.getDiagnosticPrompt(),
				existing.getId()
			);
			session.startDiagnosis(existing.getId(), action);
			return Optional.of(action);
		}
		QuizSubmission submission = submissionRepository
			.findByIdAndUser_Id(context.submissionId(), context.userId())
			.filter(candidate ->
				candidate.getQuizId().equals(context.quizId()))
			.orElse(null);
		if (submission == null) {
			return Optional.empty();
		}
		Diagnosis diagnosis = diagnosisRepository.saveAndFlush(
			Diagnosis.pending(
				session,
				submission,
				response.diagnosticPrompt(),
				new DiagnosisData(
					response.schemaVersion(),
					response.focusConcepts(),
					response.suspectedMisconceptions(),
					response.evidence(),
					response.repairHint()
				)
			)
		);
		UiAction action = UiAction.diagnosisQuestion(
			diagnosis.getDiagnosticPrompt(),
			diagnosis.getId()
		);
		session.startDiagnosis(diagnosis.getId(), action);
		sessionRepository.flush();
		return Optional.of(action);
	}
}
