package io.edupilot.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.classroom.Classroom;
import io.edupilot.classroom.ClassroomColor;
import io.edupilot.classroom.ClassroomService;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.report.dto.CreateReportCriterionRequest;
import io.edupilot.report.dto.UpdateReportCriterionRequest;
import io.edupilot.user.User;
import io.edupilot.user.UserRole;

@ExtendWith(MockitoExtension.class)
class ReportCriterionServiceTest {

	@Mock private ClassroomService classroomService;
	@Mock private ReportCriterionRepository criterionRepository;
	@Mock private ReportCriterionCatalog criterionCatalog;

	private ReportCriterionService service;
	private Classroom classroom;

	@BeforeEach
	void setUp() {
		service = new ReportCriterionService(
			classroomService, criterionRepository, criterionCatalog
		);
		User instructor = User.create(
			"teacher@example.com", "hash", "강사", UserRole.INSTRUCTOR
		);
		ReflectionTestUtils.setField(instructor, "id", 1L);
		classroom = Classroom.create(
			instructor,
			"강의실",
			LocalDate.of(2026, 9, 1),
			LocalDate.of(2026, 12, 1),
			ClassroomColor.BLUE,
			null,
			"INVITE"
		);
		ReflectionTestUtils.setField(classroom, "id", 30L);
		when(classroomService.requireOwnerForUpdate(
			1L, UserRole.INSTRUCTOR, 30L
		)).thenReturn(classroom);
	}

	@Test
	void rejectsTwelfthCustomCriterionWhenNineBuiltinsAndElevenAreActive() {
		when(criterionCatalog.defaultCriteria()).thenReturn(defaultCriteria(9));
		when(criterionRepository.countByClassroom_IdAndActiveTrue(30L))
			.thenReturn(11L);

		assertThatThrownBy(() -> service.create(
			1L, UserRole.INSTRUCTOR, 30L, createRequest("new-key", "새 기준")
		))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.REPORT_CRITERION_LIMIT_EXCEEDED)
			);
	}

	@Test
	void rejectsNameDuplicateAfterWhitespaceAndCaseNormalization() {
		when(criterionCatalog.defaultCriteria()).thenReturn(List.of(definition(
			"builtin", "Learning Attitude"
		)));
		when(criterionRepository.countByClassroom_IdAndActiveTrue(30L))
			.thenReturn(0L);

		assertThatThrownBy(() -> service.create(
			1L,
			UserRole.INSTRUCTOR,
			30L,
			createRequest("custom", "  LEARNING   attitude ")
		))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.REPORT_CRITERION_DUPLICATE)
			);
	}

	@Test
	void contentPatchDeactivatesCurrentRowAndCreatesNextVersion() {
		ReportCriterion current = criterion(10L, "custom_key", "기존 기준", 1, true);
		when(criterionRepository.findByIdAndClassroom_Id(10L, 30L))
			.thenReturn(java.util.Optional.of(current));
		when(criterionRepository
			.findByClassroom_IdAndCriterionKeyOrderByVersionDesc(30L, "custom_key"))
			.thenReturn(List.of(current));
		when(criterionCatalog.defaultCriteria()).thenReturn(defaultCriteria(9));
		when(criterionRepository
			.findByClassroom_IdAndActiveTrueOrderByCriterionKeyAscVersionDesc(30L))
			.thenReturn(List.of(current));
		when(criterionRepository.save(any(ReportCriterion.class)))
			.thenAnswer(invocation -> {
				ReportCriterion saved = invocation.getArgument(0);
				ReflectionTestUtils.setField(saved, "id", 11L);
				return saved;
			});

		var response = service.update(
			1L,
			UserRole.INSTRUCTOR,
			30L,
			10L,
			new UpdateReportCriterionRequest(
				"변경 기준", null, null, null, null, null, null
			)
		);

		assertThat(current.isActive()).isFalse();
		assertThat(response.criterionId()).isEqualTo(11L);
		assertThat(response.version()).isEqualTo("2");
		assertThat(response.active()).isTrue();
		ArgumentCaptor<ReportCriterion> captor = ArgumentCaptor.forClass(
			ReportCriterion.class
		);
		verify(criterionRepository).save(captor.capture());
		assertThat(captor.getValue().getName()).isEqualTo("변경 기준");
	}

	private CreateReportCriterionRequest createRequest(String key, String name) {
		return new CreateReportCriterionRequest(
			key,
			name,
			"설명",
			Map.of("summary", "평가"),
			List.of(ReportSourceType.SESSION),
			2,
			BigDecimal.ONE
		);
	}

	private List<ReportCriterionDefinition> defaultCriteria(int count) {
		return IntStream.range(0, count)
			.mapToObj(index -> definition("builtin_" + index, "기본 " + index))
			.toList();
	}

	private ReportCriterionDefinition definition(String key, String name) {
		return new ReportCriterionDefinition(
			key,
			name,
			"평가",
			EnumSet.of(ReportSourceType.SESSION),
			2,
			BigDecimal.ONE,
			"1.0"
		);
	}

	private ReportCriterion criterion(
		Long id,
		String key,
		String name,
		int version,
		boolean active
	) {
		ReportCriterion criterion = ReportCriterion.create(
			classroom,
			key,
			name,
			"설명",
			Map.of("summary", "평가"),
			List.of(ReportSourceType.SESSION.name()),
			2,
			BigDecimal.ONE,
			version,
			active
		);
		ReflectionTestUtils.setField(criterion, "id", id);
		return criterion;
	}
}
