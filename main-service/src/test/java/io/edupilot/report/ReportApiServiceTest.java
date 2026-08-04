package io.edupilot.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.classroom.Classroom;
import io.edupilot.classroom.ClassroomColor;
import io.edupilot.classroom.ClassroomMemberRepository;
import io.edupilot.classroom.ClassroomService;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.user.User;
import io.edupilot.user.UserRole;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class ReportApiServiceTest {

	@Mock private ClassroomService classroomService;
	@Mock private ClassroomMemberRepository memberRepository;
	@Mock private ReportGenerationService generationService;
	@Mock private ReportGenerationRepository generationRepository;
	@Mock private StudentReportRepository reportRepository;
	@Mock private ReportCriterionResultRepository resultRepository;
	@Mock private ReportEvidenceSnapshotRepository evidenceRepository;

	private ReportApiService service;

	@BeforeEach
	void setUp() {
		service = new ReportApiService(
			classroomService,
			memberRepository,
			generationService,
			generationRepository,
			reportRepository,
			resultRepository,
			evidenceRepository,
			new ReportGenerationProperties(
				Duration.ofMinutes(5),
				Duration.ofMinutes(10),
				5,
				new ReportGenerationProperties.Executor(1, 2, 50)
			),
			new ObjectMapper()
		);
	}

	@Test
	void listHidesNonMemberStudent() {
		when(memberRepository.existsByClassroom_IdAndUser_Id(30L, 40L))
			.thenReturn(false);

		assertThatThrownBy(() -> service.list(
			1L, UserRole.INSTRUCTOR, 30L, 40L
		))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode()).isEqualTo(ErrorCode.REPORT_NOT_FOUND)
			);
	}

	@Test
	void detailHidesReportFromNonManagingInstructor() {
		ReportGeneration generation = generation();
		when(generationRepository.findById(901L)).thenReturn(Optional.of(generation));
		doThrow(new BusinessException(ErrorCode.CLASSROOM_NOT_FOUND))
			.when(classroomService)
			.requireStrictOwner(2L, UserRole.INSTRUCTOR, 30L);

		assertThatThrownBy(() -> service.detail(
			2L, UserRole.INSTRUCTOR, "901"
		))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode()).isEqualTo(ErrorCode.CLASSROOM_NOT_FOUND)
			);
	}

	private ReportGeneration generation() {
		User instructor = user(1L, "teacher@example.com", UserRole.INSTRUCTOR);
		User student = user(40L, "student@example.com", UserRole.LEARNER);
		Classroom classroom = Classroom.create(
			instructor,
			"강의실",
			LocalDate.of(2026, 9, 1),
			LocalDate.of(2026, 12, 1),
			ClassroomColor.BLUE,
			null,
			"INVITE"
		);
		ReflectionTestUtils.setField(classroom, "id", 30L);
		ReportGeneration generation = ReportGeneration.create(
			classroom,
			student,
			instructor,
			"request-1",
			ReportScopeType.FULL,
			null,
			ReportGenerationService.scopeHash(ReportScope.full()),
			"1.0"
		);
		ReflectionTestUtils.setField(generation, "id", 901L);
		return generation;
	}

	private User user(Long id, String email, UserRole role) {
		User user = User.create(email, "hash", role.name(), role);
		ReflectionTestUtils.setField(user, "id", id);
		return user;
	}
}
