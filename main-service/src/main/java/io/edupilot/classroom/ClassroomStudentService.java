package io.edupilot.classroom;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.classroom.dto.ClassroomStudentListResponse;
import io.edupilot.classroom.dto.ClassroomStudentResponse;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.MaterialProcessingStatus;
import io.edupilot.material.MaterialStatus;
import io.edupilot.quiz.QuizSubmissionRepository;
import io.edupilot.session.LearningProgressService;
import io.edupilot.session.LearningSessionRepository;
import io.edupilot.session.QaMessageRepository;
import io.edupilot.session.SessionStatus;
import io.edupilot.session.StudentLastActivity;
import io.edupilot.user.UserRole;

@Service
public class ClassroomStudentService {
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
	private final QuizSubmissionRepository quizSubmissionRepository;
	private final LearningProgressService progressService;
	private final Clock clock;

	public ClassroomStudentService(
		ClassroomService classroomService,
		ClassroomMemberRepository memberRepository,
		ClassroomWeekMaterialRepository weekMaterialRepository,
		LearningSessionRepository sessionRepository,
		QaMessageRepository qaMessageRepository,
		QuizSubmissionRepository quizSubmissionRepository,
		LearningProgressService progressService,
		Clock clock
	) {
		this.classroomService = classroomService;
		this.memberRepository = memberRepository;
		this.weekMaterialRepository = weekMaterialRepository;
		this.sessionRepository = sessionRepository;
		this.qaMessageRepository = qaMessageRepository;
		this.quizSubmissionRepository = quizSubmissionRepository;
		this.progressService = progressService;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public ClassroomStudentListResponse list(
		Long instructorId,
		UserRole role,
		Long classroomId,
		int page,
		int size
	) {
		return list(
			instructorId,
			role,
			classroomId,
			page,
			size,
			null,
			null
		);
	}

	@Transactional(readOnly = true)
	public ClassroomStudentListResponse list(
		Long instructorId,
		UserRole role,
		Long classroomId,
		int page,
		int size,
		String query,
		ClassroomStudentSort sort
	) {
		classroomService.requireStrictOwner(instructorId, role, classroomId);
		String normalizedQuery = query == null ? "" : query.trim();
		List<ClassroomMember> members = memberRepository.findByClassroom_Id(
			classroomId,
			Sort.by(Sort.Direction.DESC, "joinedAt")
		).stream()
			.filter(member -> normalizedQuery.isEmpty()
				|| member.getUserName().contains(normalizedQuery))
			.toList();
		List<Long> studentIds = members.stream()
			.map(ClassroomMember::getUserId)
			.toList();
		if (studentIds.isEmpty()) {
			return emptyResponse(page, size);
		}

		Instant now = clock.instant();
		var materials = weekMaterialRepository.findReportMaterials(
			classroomId,
			null,
			MaterialStatus.ACTIVE,
			MaterialProcessingStatus.READY
		);
		Map<Long, Integer> progressByStudent = progressService
			.calculateStudentProgressRates(classroomId, materials, studentIds);
		Map<Long, Instant> lastActiveByStudent = studentIds.isEmpty()
			? Map.of()
			: sessionRepository
				.findLastActivityByClassroomAndStudentIds(classroomId, studentIds)
				.stream()
				.collect(Collectors.toMap(
					StudentLastActivity::studentId,
					StudentLastActivity::lastActiveAt
				));
		var materialIds = materials.stream()
			.map(material -> material.getId())
			.toList();
		Map<Long, Long> questionCountByStudent = materialIds.isEmpty()
			? Map.of()
			: qaMessageRepository.findRecentQuestionCountsByStudentIds(
				classroomId,
				studentIds,
				materialIds,
				PROGRESS_STATUSES,
				now.minus(LAST_7_DAYS)
			).stream().collect(Collectors.toMap(
				QaMessageRepository.StudentQuestionCount::getStudentId,
				QaMessageRepository.StudentQuestionCount::getQuestionCount
			));
		Map<Long, Long> quizCountByStudent = quizSubmissionRepository
			.findSubmissionCountsByStudentIds(
				classroomId,
				studentIds,
				PROGRESS_STATUSES
			).stream().collect(Collectors.toMap(
				QuizSubmissionRepository.StudentQuizSubmissionCount::getStudentId,
				QuizSubmissionRepository.StudentQuizSubmissionCount::getSubmissionCount
			));
		List<ClassroomStudentResponse> items = members.stream()
			.map(member -> response(
				member,
				lastActiveByStudent.get(member.getUserId()),
				progressByStudent.getOrDefault(member.getUserId(), 0),
				questionCountByStudent.getOrDefault(member.getUserId(), 0L),
				quizCountByStudent.getOrDefault(member.getUserId(), 0L)
			))
			.sorted(comparator(sort))
			.toList();
		int totalElements = items.size();
		int fromIndex = (int) Math.min((long) page * size, totalElements);
		int toIndex = Math.min(fromIndex + size, totalElements);
		return new ClassroomStudentListResponse(
			items.subList(fromIndex, toIndex),
			page,
			size,
			totalElements,
			(totalElements + size - 1) / size
		);
	}

	@Transactional
	public void remove(
		Long instructorId,
		UserRole role,
		Long classroomId,
		Long studentId
	) {
		classroomService.requireStrictOwnerForUpdate(instructorId, role, classroomId);
		ClassroomMember member = memberRepository
			.findByClassroom_IdAndUser_Id(classroomId, studentId)
			.orElseThrow(() -> new BusinessException(ErrorCode.CLASSROOM_NOT_FOUND));
		memberRepository.delete(member);
	}

	private ClassroomStudentResponse response(
		ClassroomMember member,
		Instant lastActiveAt,
		int averageProgressRate,
		long aiQuestionCountLast7Days,
		long quizSubmissionCount
	) {
		return new ClassroomStudentResponse(
			member.getUserId(),
			member.getUserName(),
			member.getUserEmail(),
			member.getUserAffiliation(),
			member.getJoinedAt(),
			"ACTIVE",
			lastActiveAt,
			averageProgressRate,
			aiQuestionCountLast7Days,
			quizSubmissionCount
		);
	}

	private Comparator<ClassroomStudentResponse> comparator(
		ClassroomStudentSort sort
	) {
		if (sort == null) {
			return (left, right) -> 0;
		}
		return switch (sort) {
			case NAME -> Comparator.comparing(ClassroomStudentResponse::name);
			case LOW_PROGRESS -> Comparator.comparingInt(
				ClassroomStudentResponse::averageProgressRate
			);
			case RECENT_ACTIVITY -> Comparator.comparing(
				ClassroomStudentResponse::lastActiveAt,
				Comparator.nullsLast(Comparator.reverseOrder())
			);
		};
	}

	private ClassroomStudentListResponse emptyResponse(int page, int size) {
		return new ClassroomStudentListResponse(
			List.of(),
			page,
			size,
			0,
			0
		);
	}
}
