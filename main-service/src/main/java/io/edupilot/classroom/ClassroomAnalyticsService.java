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
import io.edupilot.material.MaterialProcessingStatus;
import io.edupilot.material.MaterialStatus;
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
	private final LearningProgressService progressService;
	private final Clock clock;

	public ClassroomAnalyticsService(
		ClassroomService classroomService,
		ClassroomMemberRepository memberRepository,
		ClassroomWeekMaterialRepository weekMaterialRepository,
		LearningSessionRepository sessionRepository,
		QaMessageRepository qaMessageRepository,
		LearningProgressService progressService,
		Clock clock
	) {
		this.classroomService = classroomService;
		this.memberRepository = memberRepository;
		this.weekMaterialRepository = weekMaterialRepository;
		this.sessionRepository = sessionRepository;
		this.qaMessageRepository = qaMessageRepository;
		this.progressService = progressService;
		this.clock = clock;
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
		var materials = weekMaterialRepository.findVisibleReportMaterials(
			classroomId,
			null,
			now,
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
