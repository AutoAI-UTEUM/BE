package io.edupilot.assessment;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.ai.dto.QuizAssessmentResponse;
import io.edupilot.material.LearningMaterial;
import io.edupilot.material.LearningMaterialRepository;
import io.edupilot.memory.LearnerMemoryCandidate;
import io.edupilot.memory.LearnerMemoryCandidateRepository;
import io.edupilot.memory.MemoryEvidenceRef;
import io.edupilot.quiz.QuizPostGradingContext;
import io.edupilot.quiz.QuizSubmission;
import io.edupilot.quiz.QuizSubmissionRepository;
import io.edupilot.session.LearningSession;
import io.edupilot.session.LearningSessionRepository;
import io.edupilot.session.SessionStatus;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;

@Service
public class AssessmentPersistenceService {

	private final LearningSessionRepository sessionRepository;
	private final QuizSubmissionRepository submissionRepository;
	private final QuizAssessmentRepository assessmentRepository;
	private final LearnerMemoryCandidateRepository candidateRepository;
	private final UserRepository userRepository;
	private final LearningMaterialRepository materialRepository;

	public AssessmentPersistenceService(
		LearningSessionRepository sessionRepository,
		QuizSubmissionRepository submissionRepository,
		QuizAssessmentRepository assessmentRepository,
		LearnerMemoryCandidateRepository candidateRepository,
		UserRepository userRepository,
		LearningMaterialRepository materialRepository
	) {
		this.sessionRepository = sessionRepository;
		this.submissionRepository = submissionRepository;
		this.assessmentRepository = assessmentRepository;
		this.candidateRepository = candidateRepository;
		this.userRepository = userRepository;
		this.materialRepository = materialRepository;
	}

	@Transactional
	public AssessmentSaveResult save(
		QuizPostGradingContext context,
		QuizAssessmentResponse response
	) {
		LearningSession session = sessionRepository.findOwnedForUpdate(
				context.sessionId(),
				context.userId()
			)
			.orElse(null);
		if (session == null || session.getStatus() != SessionStatus.ACTIVE) {
			return AssessmentSaveResult.discarded();
		}
		QuizAssessment existing = assessmentRepository
			.findBySubmission_Id(context.submissionId())
			.orElse(null);
		if (existing != null) {
			return AssessmentSaveResult.saved(existing.getId());
		}
		QuizSubmission submission = submissionRepository
			.findByIdAndUser_Id(context.submissionId(), context.userId())
			.filter(candidate ->
				candidate.getQuizId().equals(context.quizId()))
			.orElse(null);
		if (submission == null) {
			return AssessmentSaveResult.discarded();
		}

		QuizAssessment assessment = assessmentRepository.saveAndFlush(
			QuizAssessment.create(
				session,
				submission,
				toData(response)
			)
		);
		User user = userRepository.getReferenceById(context.userId());
		LearningMaterial material = materialRepository.getReferenceById(
			context.materialId()
		);
		List<MemoryEvidenceRef> evidenceRefs = List.of(
			new MemoryEvidenceRef(
				"QUIZ_ASSESSMENT",
				assessment.getId(),
				context.sessionId()
			)
		);
		for (QuizAssessmentResponse.MemoryCandidate candidate
			: response.memoryCandidates()) {
			candidateRepository.save(
				LearnerMemoryCandidate.create(
					user,
					material,
					candidate.type(),
					candidate.content(),
					candidate.confidence(),
					evidenceRefs,
					response.schemaVersion()
				)
			);
		}
		candidateRepository.flush();
		return AssessmentSaveResult.saved(assessment.getId());
	}

	private QuizAssessmentData toData(QuizAssessmentResponse response) {
		return new QuizAssessmentData(
			response.schemaVersion(),
			response.understandingSummary(),
			List.copyOf(response.strengths()),
			List.copyOf(response.weaknesses()),
			List.copyOf(response.suspectedMisconceptions()),
			response.recommendedNextDirection(),
			response.memoryCandidates().stream()
				.map(candidate ->
					new QuizAssessmentData.AssessmentMemoryCandidate(
						candidate.type(),
						candidate.content(),
						candidate.confidence()
					))
				.toList(),
			List.copyOf(response.evidence())
		);
	}

	public record AssessmentSaveResult(boolean applied, Long assessmentId) {
		static AssessmentSaveResult discarded() {
			return new AssessmentSaveResult(false, null);
		}

		static AssessmentSaveResult saved(Long assessmentId) {
			return new AssessmentSaveResult(true, assessmentId);
		}
	}
}
