package io.edupilot.classroom;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.classroom.dto.ClassroomStudentListResponse;
import io.edupilot.classroom.dto.ClassroomStudentResponse;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.session.LearningSessionRepository;
import io.edupilot.session.StudentLastActivity;
import io.edupilot.user.UserRole;

@Service
public class ClassroomStudentService {

	private final ClassroomService classroomService;
	private final ClassroomMemberRepository memberRepository;
	private final LearningSessionRepository sessionRepository;

	public ClassroomStudentService(
		ClassroomService classroomService,
		ClassroomMemberRepository memberRepository,
		LearningSessionRepository sessionRepository
	) {
		this.classroomService = classroomService;
		this.memberRepository = memberRepository;
		this.sessionRepository = sessionRepository;
	}

	@Transactional(readOnly = true)
	public ClassroomStudentListResponse list(
		Long instructorId,
		UserRole role,
		Long classroomId,
		int page,
		int size
	) {
		classroomService.requireStrictOwner(instructorId, role, classroomId);
		Page<ClassroomMember> members = memberRepository.findByClassroom_Id(
			classroomId,
			PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "joinedAt"))
		);
		List<Long> studentIds = members.stream()
			.map(ClassroomMember::getUserId)
			.toList();
		Map<Long, Instant> lastActiveByStudent = studentIds.isEmpty()
			? Map.of()
			: sessionRepository
				.findLastActivityByClassroomAndStudentIds(classroomId, studentIds)
				.stream()
				.collect(Collectors.toMap(
					StudentLastActivity::studentId,
					StudentLastActivity::lastActiveAt
				));
		List<ClassroomStudentResponse> items = members.stream()
			.map(member -> response(
				member,
				lastActiveByStudent.get(member.getUserId())
			))
			.toList();
		return new ClassroomStudentListResponse(
			items,
			members.getNumber(),
			members.getSize(),
			members.getTotalElements(),
			members.getTotalPages()
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
		Instant lastActiveAt
	) {
		return new ClassroomStudentResponse(
			member.getUserId(),
			member.getUserName(),
			member.getUserEmail(),
			member.getUserAffiliation(),
			member.getJoinedAt(),
			"ACTIVE",
			lastActiveAt
		);
	}
}
