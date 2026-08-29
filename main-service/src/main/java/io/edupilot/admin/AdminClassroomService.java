package io.edupilot.admin;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.admin.dto.AdminClassroomDetailResponse;
import io.edupilot.admin.dto.AdminClassroomListResponse;
import io.edupilot.admin.dto.AdminClassroomMemberResponse;
import io.edupilot.admin.dto.AdminClassroomResponse;
import io.edupilot.classroom.Classroom;
import io.edupilot.classroom.ClassroomMemberRepository;
import io.edupilot.classroom.ClassroomRepository;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;

@Service
public class AdminClassroomService {

	private final ClassroomRepository classroomRepository;
	private final ClassroomMemberRepository memberRepository;

	public AdminClassroomService(
		ClassroomRepository classroomRepository,
		ClassroomMemberRepository memberRepository
	) {
		this.classroomRepository = classroomRepository;
		this.memberRepository = memberRepository;
	}

	@Transactional(readOnly = true)
	public AdminClassroomListResponse list(
		AdminListSort sort,
		int page,
		int size
	) {
		Page<Classroom> classrooms = classroomRepository.findAllForAdmin(
			PageRequest.of(page, size, classroomSort(sort))
		);
		var classroomIds = classrooms.getContent().stream()
			.map(Classroom::getId)
			.toList();
		Map<Long, ClassroomMemberRepository.ClassroomMemberCount> memberCounts =
			classroomIds.isEmpty()
				? Map.of()
				: memberRepository.countByClassroomIds(classroomIds).stream()
					.collect(Collectors.toMap(
						ClassroomMemberRepository.ClassroomMemberCount::getClassroomId,
						Function.identity()
					));
		Page<AdminClassroomResponse> responses = classrooms.map(classroom ->
			AdminClassroomResponse.from(
				classroom,
				memberCounts.containsKey(classroom.getId())
					? memberCounts.get(classroom.getId()).getMemberCount()
					: 0
			)
		);
		return new AdminClassroomListResponse(
			responses.getContent(),
			responses.getNumber(),
			responses.getSize(),
			responses.getTotalElements(),
			responses.getTotalPages()
		);
	}

	@Transactional(readOnly = true)
	public AdminClassroomDetailResponse detail(Long classroomId) {
		Classroom classroom = classroomRepository.findWithInstructorById(classroomId)
			.orElseThrow(() -> new BusinessException(ErrorCode.CLASSROOM_NOT_FOUND));
		var members = memberRepository.findByClassroom_Id(
			classroomId,
			Sort.by(
				Sort.Order.asc("joinedAt"),
				Sort.Order.asc("id")
			)
		).stream().map(AdminClassroomMemberResponse::from).toList();
		return AdminClassroomDetailResponse.from(classroom, members);
	}

	private Sort classroomSort(AdminListSort sort) {
		return switch (sort == null ? AdminListSort.RECENT : sort) {
			case RECENT -> Sort.by(
				Sort.Order.desc("createdAt"),
				Sort.Order.desc("id")
			);
			case NAME -> Sort.by(
				Sort.Order.asc("name"),
				Sort.Order.asc("id")
			);
		};
	}
}
