package io.edupilot.report;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.assessment.QuizAssessment;
import io.edupilot.assessment.QuizAssessmentRepository;
import io.edupilot.classroom.ClassroomMemberRepository;
import io.edupilot.classroom.ClassroomRepository;
import io.edupilot.classroom.ClassroomWeekMaterialRepository;
import io.edupilot.classroom.ClassroomWeekRepository;
import io.edupilot.diagnosis.Diagnosis;
import io.edupilot.diagnosis.DiagnosisRepository;
import io.edupilot.exam.ExamSubmission;
import io.edupilot.exam.ExamSubmissionRepository;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.LearningMaterial;
import io.edupilot.material.MaterialProcessingStatus;
import io.edupilot.material.MaterialStatus;
import io.edupilot.memory.LearnerMemory;
import io.edupilot.memory.LearnerMemoryRepository;
import io.edupilot.quiz.QuizSubmission;
import io.edupilot.quiz.QuizSubmissionRepository;
import io.edupilot.session.LearningProgressService;
import io.edupilot.session.LearningSession;
import io.edupilot.session.LearningSessionRepository;
import io.edupilot.session.QaMessage;
import io.edupilot.session.QaMessageRepository;
import io.edupilot.session.SessionStatus;

@Service
public class ReportSnapshotBuilder {

	private static final List<SessionStatus> REPORT_SESSION_STATUSES = List.of(
		SessionStatus.ACTIVE,
		SessionStatus.COMPLETED
	);

	private final ClassroomRepository classroomRepository;
	private final ClassroomMemberRepository memberRepository;
	private final ClassroomWeekRepository weekRepository;
	private final ClassroomWeekMaterialRepository weekMaterialRepository;
	private final LearningSessionRepository sessionRepository;
	private final QaMessageRepository qaMessageRepository;
	private final QuizSubmissionRepository quizSubmissionRepository;
	private final QuizAssessmentRepository assessmentRepository;
	private final DiagnosisRepository diagnosisRepository;
	private final LearnerMemoryRepository memoryRepository;
	private final ExamSubmissionRepository examSubmissionRepository;
	private final LearningProgressService progressService;
	private final ReportSnapshotComputer computer;
	private final Clock clock;

	public ReportSnapshotBuilder(
		ClassroomRepository classroomRepository,
		ClassroomMemberRepository memberRepository,
		ClassroomWeekRepository weekRepository,
		ClassroomWeekMaterialRepository weekMaterialRepository,
		LearningSessionRepository sessionRepository,
		QaMessageRepository qaMessageRepository,
		QuizSubmissionRepository quizSubmissionRepository,
		QuizAssessmentRepository assessmentRepository,
		DiagnosisRepository diagnosisRepository,
		LearnerMemoryRepository memoryRepository,
		ExamSubmissionRepository examSubmissionRepository,
		LearningProgressService progressService,
		ReportSnapshotComputer computer,
		Clock clock
	) {
		this.classroomRepository = classroomRepository;
		this.memberRepository = memberRepository;
		this.weekRepository = weekRepository;
		this.weekMaterialRepository = weekMaterialRepository;
		this.sessionRepository = sessionRepository;
		this.qaMessageRepository = qaMessageRepository;
		this.quizSubmissionRepository = quizSubmissionRepository;
		this.assessmentRepository = assessmentRepository;
		this.diagnosisRepository = diagnosisRepository;
		this.memoryRepository = memoryRepository;
		this.examSubmissionRepository = examSubmissionRepository;
		this.progressService = progressService;
		this.computer = computer;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public ReportSnapshot build(
		Long instructorId,
		Long classroomId,
		Long studentId,
		ReportScope scope,
		List<ReportCriterionDefinition> catalog
	) {
		validateAccess(instructorId, classroomId, studentId, scope);
		Instant sourceDataAsOf = clock.instant();
		Integer weekNumber = scope.weekNumber();
		List<LearningMaterial> materials = weekMaterialRepository.findReportMaterials(
			classroomId,
			weekNumber,
			sourceDataAsOf,
			MaterialStatus.ACTIVE,
			MaterialProcessingStatus.READY
		);

		List<ReportSnapshotInput.SourceRecord> sources = new ArrayList<>();
		collectSessions(sources, classroomId, studentId, weekNumber);
		collectQuestions(sources, classroomId, studentId, weekNumber);
		collectQuizSources(sources, classroomId, studentId, weekNumber);
		collectMemories(sources, classroomId, studentId, weekNumber);
		collectExamSubmissions(sources, classroomId, studentId, weekNumber);

		LearningProgressService.ReportProgress progress =
			progressService.calculateReportProgress(studentId, materials);
		return computer.compute(new ReportSnapshotInput(
			catalog,
			sources,
			new ReportSnapshotInput.ProgressRecord(
				progress.explainedPages(),
				progress.totalPages(),
				progress.progressRate(),
				progress.progressDataAvailable()
			),
			sourceDataAsOf
		));
	}

	private void validateAccess(
		Long instructorId,
		Long classroomId,
		Long studentId,
		ReportScope scope
	) {
		classroomRepository.findWithInstructorById(classroomId)
			.filter(classroom -> classroom.getInstructorId().equals(instructorId))
			.orElseThrow(() -> new BusinessException(ErrorCode.CLASSROOM_NOT_FOUND));
		if (!memberRepository.existsByClassroom_IdAndUser_Id(classroomId, studentId)) {
			throw new BusinessException(ErrorCode.CLASSROOM_NOT_FOUND);
		}
		if (scope.type() == ReportScopeType.WEEK
			&& !weekRepository.existsByClassroom_IdAndWeekNumber(
				classroomId,
				scope.weekNumber()
			)) {
			throw new BusinessException(ErrorCode.WEEK_NOT_FOUND);
		}
	}

	private void collectSessions(
		List<ReportSnapshotInput.SourceRecord> sources,
		Long classroomId,
		Long studentId,
		Integer weekNumber
	) {
		for (LearningSession session : sessionRepository.findReportSessions(
			classroomId,
			studentId,
			weekNumber,
			REPORT_SESSION_STATUSES
		)) {
			sources.add(source(
				ReportSourceType.SESSION,
				"session:" + session.getId(),
				session.getUpdatedAt(),
				"학습 세션 활동",
				facts(
					"materialId", session.getMaterialId(),
					"status", session.getStatus().name()
				),
				null,
				null,
				null
			));
		}
	}

	private void collectQuestions(
		List<ReportSnapshotInput.SourceRecord> sources,
		Long classroomId,
		Long studentId,
		Integer weekNumber
	) {
		for (QaMessage message : qaMessageRepository.findReportQuestions(
			classroomId,
			studentId,
			weekNumber
		)) {
			sources.add(source(
				ReportSourceType.QA_QUESTION,
				"qa-message:" + message.getId(),
				message.getCreatedAt(),
				truncate(message.getContent(), 80),
				facts("characterCount", message.getContent().codePointCount(
					0, message.getContent().length()
				)),
				null,
				null,
				null
			));
		}
	}

	private void collectQuizSources(
		List<ReportSnapshotInput.SourceRecord> sources,
		Long classroomId,
		Long studentId,
		Integer weekNumber
	) {
		List<QuizSubmission> submissions = quizSubmissionRepository.findReportSubmissions(
			classroomId,
			studentId,
			weekNumber
		);
		for (QuizSubmission submission : submissions) {
			sources.add(source(
				ReportSourceType.QUIZ_SUBMISSION,
				"quiz-submission:" + submission.getId(),
				submission.getCreatedAt(),
				"통합 학습 퀴즈 제출",
				facts(
					"quizId", submission.getQuizId(),
					"quizType", submission.getQuizType().name(),
					"passed", submission.isPassed(),
					"attemptNo", submission.getAttemptNo()
				),
				submission.getScore(),
				submission.getMaxScore(),
				null
			));
		}
		if (submissions.isEmpty()) {
			return;
		}
		List<Long> submissionIds = submissions.stream()
			.map(QuizSubmission::getId)
			.toList();
		for (QuizAssessment assessment : assessmentRepository.findReportAssessments(
			classroomId,
			studentId,
			weekNumber,
			submissionIds
		)) {
			sources.add(source(
				ReportSourceType.QUIZ_ASSESSMENT,
				"quiz-assessment:" + assessment.getId(),
				assessment.getCreatedAt(),
				"퀴즈 평가 결과",
				facts(
					"submissionId", assessment.getSubmissionId(),
					"passed", assessment.isPassed(),
					"strengthCount", assessment.getAssessment().strengths().size(),
					"weaknessCount", assessment.getAssessment().weaknesses().size(),
					"misconceptionCount",
					assessment.getAssessment().suspectedMisconceptions().size()
				),
				null,
				null,
				null
			));
		}
		for (Diagnosis diagnosis : diagnosisRepository.findReportDiagnoses(
			classroomId,
			studentId,
			weekNumber,
			submissionIds
		)) {
			sources.add(source(
				ReportSourceType.DIAGNOSIS,
				"diagnosis:" + diagnosis.getId(),
				diagnosis.getCreatedAt(),
				"오개념 진단 활동",
				facts(
					"submissionId", diagnosis.getSubmissionId(),
					"status", diagnosis.getStatus().name(),
					"focusConceptCount",
					diagnosis.getDiagnosisResult().focusConcepts().size(),
					"misconceptionCount",
					diagnosis.getDiagnosisResult().suspectedMisconceptions().size()
				),
				null,
				null,
				null
			));
		}
	}

	private void collectMemories(
		List<ReportSnapshotInput.SourceRecord> sources,
		Long classroomId,
		Long studentId,
		Integer weekNumber
	) {
		for (LearnerMemory memory : memoryRepository.findReportMemories(
			classroomId,
			studentId,
			weekNumber
		)) {
			sources.add(source(
				ReportSourceType.MEMORY,
				"memory:" + memory.getId(),
				memory.getUpdatedAt(),
				"학습자 메모리 갱신",
				facts(
					"strengthCount", memory.getStrengths().size(),
					"weaknessCount", memory.getWeaknesses().size(),
					"misconceptionCount", memory.getMisconceptions().size(),
					"targetDifficulty", memory.getTargetDifficulty()
				),
				null,
				null,
				null
			));
		}
	}

	private void collectExamSubmissions(
		List<ReportSnapshotInput.SourceRecord> sources,
		Long classroomId,
		Long studentId,
		Integer weekNumber
	) {
		for (ExamSubmission submission :
			examSubmissionRepository.findRepresentativeReportSubmissions(
				classroomId,
				studentId,
				weekNumber
			)) {
			sources.add(source(
				ReportSourceType.EXAM_SUBMISSION,
				"exam-submission:" + submission.getId(),
				submission.getGradedAt(),
				"별도 시험 채점 완료",
				facts(
					"examId", submission.getExamId(),
					"attemptNo", submission.getAttemptNo()
				),
				submission.getScore(),
				submission.getMaxScore(),
				submission.getNormalizedScore()
			));
		}
	}

	private ReportSnapshotInput.SourceRecord source(
		ReportSourceType sourceType,
		String sourceRef,
		Instant occurredAt,
		String publicLabel,
		Map<String, Object> minimalFact,
		BigDecimal score,
		BigDecimal maxScore,
		BigDecimal normalizedScore
	) {
		return new ReportSnapshotInput.SourceRecord(
			sourceType,
			sourceRef,
			occurredAt,
			publicLabel,
			minimalFact,
			score,
			maxScore,
			normalizedScore
		);
	}

	private Map<String, Object> facts(Object... entries) {
		Map<String, Object> facts = new LinkedHashMap<>();
		for (int index = 0; index < entries.length; index += 2) {
			Object value = entries[index + 1];
			if (value != null) {
				facts.put((String) entries[index], value);
			}
		}
		return facts;
	}

	private String truncate(String value, int maxCodePoints) {
		int codePoints = value.codePointCount(0, value.length());
		if (codePoints <= maxCodePoints) {
			return value;
		}
		return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
	}
}
