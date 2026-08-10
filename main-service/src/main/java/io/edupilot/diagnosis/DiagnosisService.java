package io.edupilot.diagnosis;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.session.LearningSession;
import io.edupilot.session.LearningSessionRepository;
import io.edupilot.session.dto.PendingDiagnosisResponse;

@Service
public class DiagnosisService {

	private final DiagnosisRepository diagnosisRepository;
	private final RepairResultRepository repairResultRepository;
	private final LearningSessionRepository sessionRepository;

	public DiagnosisService(
		DiagnosisRepository diagnosisRepository,
		RepairResultRepository repairResultRepository,
		LearningSessionRepository sessionRepository
	) {
		this.diagnosisRepository = diagnosisRepository;
		this.repairResultRepository = repairResultRepository;
		this.sessionRepository = sessionRepository;
	}

	@Transactional
	public void answer(
		Long userId,
		Long sessionId,
		Long diagnosisId,
		String answer
	) {
		Diagnosis diagnosis = diagnosisRepository.findByIdForUpdate(diagnosisId)
			.orElseThrow(() ->
				new BusinessException(ErrorCode.DIAGNOSIS_NOT_FOUND));
		if (!diagnosis.getSessionId().equals(sessionId)
			|| !diagnosis.getUserId().equals(userId)) {
			throw new BusinessException(ErrorCode.DIAGNOSIS_NOT_FOUND);
		}
		LearningSession session = sessionRepository.findOwnedForUpdate(
				sessionId,
				userId
			)
			.orElseThrow(() ->
				new BusinessException(ErrorCode.DIAGNOSIS_NOT_FOUND));
		if (!diagnosisId.equals(session.getPendingDiagnosisId())
			|| diagnosis.getStatus() != DiagnosisStatus.PENDING) {
			throw new BusinessException(ErrorCode.DIAGNOSIS_NOT_PENDING);
		}
		if (!StringUtils.hasText(answer)) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		diagnosis.answer(answer.trim());
		diagnosisRepository.flush();
	}

	@Transactional
	public boolean completeDiagnosis(Long diagnosisId, String repairContent) {
		Diagnosis diagnosis = diagnosisRepository.findByIdForUpdate(diagnosisId)
			.orElseThrow(() ->
				new BusinessException(ErrorCode.DIAGNOSIS_NOT_FOUND));
		if (diagnosis.getStatus() != DiagnosisStatus.ANSWERED
			|| !StringUtils.hasText(repairContent)
			|| repairResultRepository.existsByDiagnosis_Id(diagnosisId)) {
			throw new BusinessException(ErrorCode.DIAGNOSIS_NOT_PENDING);
		}
		LearningSession session = sessionRepository.findOwnedForUpdate(
				diagnosis.getSessionId(),
				diagnosis.getUserId()
			)
			.orElseThrow(() ->
				new BusinessException(ErrorCode.DIAGNOSIS_NOT_FOUND));
		if (!diagnosisId.equals(session.getPendingDiagnosisId())) {
			throw new BusinessException(ErrorCode.DIAGNOSIS_NOT_PENDING);
		}
		repairResultRepository.save(
			RepairResult.create(
				diagnosis,
				session,
				repairContent.trim()
			)
		);
		diagnosis.complete();
		boolean currentPageDiagnosis = diagnosis.getQuizPageNumber()
			== session.getCurrentPage();
		session.completeDiagnosis(diagnosisId, currentPageDiagnosis);
		repairResultRepository.flush();
		diagnosisRepository.flush();
		sessionRepository.flush();
		return currentPageDiagnosis;
	}

	@Transactional(readOnly = true)
	public Optional<PendingDiagnosisResponse> findPending(
		Long sessionId,
		Long pendingDiagnosisId
	) {
		if (pendingDiagnosisId == null) {
			return Optional.empty();
		}
		return diagnosisRepository.findById(pendingDiagnosisId)
			.filter(diagnosis -> diagnosis.getSessionId().equals(sessionId))
			.filter(diagnosis ->
				diagnosis.getStatus() == DiagnosisStatus.PENDING
					|| diagnosis.getStatus() == DiagnosisStatus.ANSWERED)
			.map(diagnosis -> new PendingDiagnosisResponse(
				diagnosis.getId(),
				diagnosis.getDiagnosticPrompt()
			));
	}
}
