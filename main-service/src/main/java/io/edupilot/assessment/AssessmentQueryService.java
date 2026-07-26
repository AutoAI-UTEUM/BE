package io.edupilot.assessment;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssessmentQueryService {

	private static final int SESSION_WINDOW = 5;
	private static final int PROMOTION_WINDOW = 20;

	private final QuizAssessmentRepository assessmentRepository;

	public AssessmentQueryService(
		QuizAssessmentRepository assessmentRepository
	) {
		this.assessmentRepository = assessmentRepository;
	}

	@Transactional(readOnly = true)
	public List<QuizAssessment> recentForSession(Long sessionId) {
		return assessmentRepository
			.findTop5BySession_IdOrderByCreatedAtDescIdDesc(sessionId);
	}

	@Transactional(readOnly = true)
	public List<QuizAssessment> recentForPromotion(
		Long userId,
		Long materialId
	) {
		return assessmentRepository.findRecentByUserAndMaterial(
			userId,
			materialId,
			PageRequest.of(0, PROMOTION_WINDOW)
		);
	}

	int sessionWindow() {
		return SESSION_WINDOW;
	}
}
