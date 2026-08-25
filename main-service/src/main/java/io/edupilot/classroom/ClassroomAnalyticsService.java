package io.edupilot.classroom;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.classroom.dto.ClassroomAnalyticsMaterialResponse;
import io.edupilot.classroom.dto.ClassroomAnalyticsResponse;
import io.edupilot.classroom.dto.ClassroomQuestionByPageResponse;
import io.edupilot.classroom.dto.ClassroomStudentLearningAnalyticsResponse;
import io.edupilot.classroom.dto.ClassroomStudentMaterialAnalyticsResponse;
import io.edupilot.classroom.dto.ClassroomStudentQuestionByPageResponse;
import io.edupilot.classroom.dto.ClassroomStudentQuizAnalyticsResponse;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.MaterialProcessingStatus;
import io.edupilot.material.MaterialStatus;
import io.edupilot.quiz.QuizRepository;
import io.edupilot.quiz.QuizSubmissionRepository;
import io.edupilot.session.LearningProgressService;
import io.edupilot.session.LearningSessionRepository;
import io.edupilot.session.QaMessageRepository;
import io.edupilot.session.SessionStatus;
import io.edupilot.user.UserRole;

@Service
public class ClassroomAnalyticsService {

	private static final Duration LAST_7_DAYS = Duration.ofDays(7);
	private static final List<SessionStatus> PROGRESS_STATUSES = List.of(
		SessionStatus.ACTIVE,
		SessionStatus.COMPLETED
	);

	private final ClassroomService classroomService;
	private final ClassroomMemberRepository memberRepository;
	private final ClassroomWeekMaterialRepository weekMaterialRepository;
	private final LearningSessionRepository sessionRepository;
	private final QaMessageRepository qaMessageRepository;
	private final QuizRepository quizRepository;
	private final QuizSubmissionRepository quizSubmissionRepository;
	private final LearningProgressService progressService;
	private final Clock clock;

	public ClassroomAnalyticsService(
		ClassroomService classroomService,
		ClassroomMemberRepository memberRepository,
		ClassroomWeekMaterialRepository weekMaterialRepository,
		LearningSessionRepository sessionRepository,
		QaMessageRepository qaMessageRepository,
		QuizRepository quizRepository,
		QuizSubmissionRepository quizSubmissionRepository,
		LearningProgressService progressService,
		Clock clock
	) {
		this.classroomService = classroomService;
		this.memberRepository = memberRepository;
		this.weekMaterialRepository = weekMaterialRepository;
		this.sessionRepository = sessionRepository;
		this.qaMessageRepository = qaMessageRepository;
		this.quizRepository = quizRepository;
		this.quizSubmissionRepository = quizSubmissionRepository;
		this.progressService = progressService;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public ClassroomStudentLearningAnalyticsResponse getStudentLearningAnalytics(
		Long instructorId,
		UserRole role,
		Long classroomId,
		Long studentId,
		ClassroomQuestionPeriod questionPeriod
	) {
		classroomService.requireStrictOwner(instructorId, role, classroomId);
		if (!memberRepository.existsByClassroom_IdAndUser_Id(
			classroomId,
			studentId
		)) {
			throw new BusinessException(ErrorCode.CLASSROOM_NOT_FOUND);
		}

		Instant now = clock.instant();
		var materials = weekMaterialRepository.findReportMaterials(
			classroomId,
			null,
			MaterialStatus.ACTIVE,
			MaterialProcessingStatus.READY
		);
		if (materials.isEmpty()) {
			return new ClassroomStudentLearningAnalyticsResponse(
				List.of(),
				List.of(),
				List.of(),
				now
			);
		}

		Map<Long, String> materialTitles = new LinkedHashMap<>();
		for (var material : materials) {
			materialTitles.putIfAbsent(material.getId(), material.getTitle());
		}
		List<Long> materialIds = List.copyOf(materialTitles.keySet());

		Map<Long, Integer> weekNumbers = new LinkedHashMap<>();
		for (var week : weekMaterialRepository.findMinimumWeekNumbers(
			classroomId,
			materialIds
		)) {
			weekNumbers.put(week.getMaterialId(), week.getWeekNumber());
		}

		Map<Long, LearningSessionRepository.StudentMaterialSession> latestSessions =
			new LinkedHashMap<>();
		for (var session : sessionRepository.findStudentMaterialSessions(
			studentId,
			materialIds,
			PROGRESS_STATUSES
		)) {
			latestSessions.putIfAbsent(session.getMaterialId(), session);
		}
		Map<Long, Integer> progressRates = progressService
			.calculateStudentMaterialProgressRates(studentId, materials);

		Instant questionSince = questionPeriod == ClassroomQuestionPeriod.ALL
			? null
			: now.minus(LAST_7_DAYS);
		var questionCounts = qaMessageRepository.findStudentQuestionCounts(
			classroomId,
			studentId,
			materialIds,
			PROGRESS_STATUSES,
			questionSince
		);

		var quizSummaries = quizRepository.findStudentQuizSummaries(
			studentId,
			materialIds,
			PROGRESS_STATUSES
		);
		Map<Long, QuizSubmissionRepository.StudentLatestQuizSubmission>
			latestSubmissions = new LinkedHashMap<>();
		if (!quizSummaries.isEmpty()) {
			var quizIds = quizSummaries.stream()
				.map(QuizRepository.StudentQuizSummary::getQuizId)
				.toList();
			for (var submission : quizSubmissionRepository
				.findLatestByStudentAndQuizIds(studentId, quizIds)) {
				latestSubmissions.put(submission.getQuizId(), submission);
			}
		}

		return new ClassroomStudentLearningAnalyticsResponse(
			materials.stream().map(material -> {
				var latestSession = latestSessions.get(material.getId());
				return new ClassroomStudentMaterialAnalyticsResponse(
					material.getId(),
					material.getTitle(),
					weekNumbers.get(material.getId()),
					progressRates.getOrDefault(material.getId(), 0),
					latestSession != null,
					latestSession == null ? null : latestSession.getCurrentPage(),
					latestSession == null ? null : latestSession.getUpdatedAt()
				);
			}).toList(),
			questionCounts.stream()
				.map(count -> new ClassroomStudentQuestionByPageResponse(
					count.getMaterialId(),
					materialTitles.get(count.getMaterialId()),
					weekNumbers.get(count.getMaterialId()),
					count.getPageNumber(),
					count.getQuestionCount()
				))
				.toList(),
			quizSummaries.stream().map(quiz -> {
				var submission = latestSubmissions.get(quiz.getQuizId());
				return new ClassroomStudentQuizAnalyticsResponse(
					quiz.getQuizId(),
					quiz.getMaterialId(),
					materialTitles.get(quiz.getMaterialId()),
					weekNumbers.get(quiz.getMaterialId()),
					quiz.getTitle(),
					quiz.getQuizType(),
					quiz.getPageNumber(),
					submission != null,
					submission == null ? null : submission.getScore(),
					submission == null ? null : submission.getMaxScore(),
					submission == null ? null : submission.getPassed(),
					submission == null ? null : submission.getSubmittedAt()
				);
			}).toList(),
			now
		);
	}

	@Transactional(readOnly = true)
	public ClassroomAnalyticsResponse getAnalytics(
		Long instructorId,
		UserRole role,
		Long classroomId
	) {
		classroomService.requireStrictOwner(instructorId, role, classroomId);
		Instant now = clock.instant();
		Instant sevenDaysAgo = now.minus(LAST_7_DAYS);
		var materials = weekMaterialRepository.findReportMaterials(
			classroomId,
			null,
			MaterialStatus.ACTIVE,
			MaterialProcessingStatus.READY
		);
		var learnerIds = memberRepository.findUserIdsByClassroomId(classroomId);
		long learnerCount = learnerIds.size();
		var progress = materials.isEmpty()
			? new LearningProgressService.ClassroomProgressSnapshot(
				learnerCount,
				Map.of()
			)
			: progressService.calculateClassroomProgressSnapshot(
				classroomId,
				materials,
				learnerCount
			);

		Map<Long, Long> viewersByMaterial = new LinkedHashMap<>();
		var materialIds = materials.stream().map(material -> material.getId()).toList();
		if (!materialIds.isEmpty()) {
			for (var count : sessionRepository.findMaterialViewerCounts(
				classroomId,
				materialIds,
				PROGRESS_STATUSES
			)) {
				viewersByMaterial.put(count.getMaterialId(), count.getViewerCount());
			}
		}

		var questionCounts = materialIds.isEmpty()
			? List.<QaMessageRepository.ClassroomQuestionCount>of()
			: qaMessageRepository.findClassroomQuestionCounts(
				classroomId,
				materialIds,
				PROGRESS_STATUSES,
				sevenDaysAgo
			);
		long recentQuestionCount = questionCounts.stream()
			.map(QaMessageRepository.ClassroomQuestionCount::getQuestionCountLast7Days)
			.filter(java.util.Objects::nonNull)
			.mapToLong(Long::longValue)
			.sum();

		long activeLearnerCount = learnerIds.isEmpty()
			? 0
			: sessionRepository.findLastActivityByClassroomAndStudentIds(
				classroomId,
				learnerIds
			).stream()
				.filter(activity -> !activity.lastActiveAt().isBefore(sevenDaysAgo))
				.count();

		return new ClassroomAnalyticsResponse(
			learnerCount,
			progress.averageProgressRate(materials),
			recentQuestionCount,
			learnerCount - activeLearnerCount,
			now,
			materials.stream().map(material -> {
				long viewerCount = viewersByMaterial.getOrDefault(material.getId(), 0L);
				return new ClassroomAnalyticsMaterialResponse(
					material.getId(),
					material.getTitle(),
					viewerCount,
					roundedRate(viewerCount, learnerCount),
					progress.materialAverageProgressRate(material)
				);
			}).toList(),
			questionCounts.stream()
				.map(count -> new ClassroomQuestionByPageResponse(
					count.getMaterialId(),
					count.getPageNumber(),
					count.getQuestionCount()
				))
				.toList()
		);
	}

	private int roundedRate(long numerator, long denominator) {
		if (numerator == 0 || denominator < 1) {
			return 0;
		}
		return (int) Math.round(numerator * 100.0 / denominator);
	}
}
