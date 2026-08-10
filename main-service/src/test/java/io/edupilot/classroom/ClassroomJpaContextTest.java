package io.edupilot.classroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;

import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.data.domain.PageRequest;

import io.edupilot.classroom.dto.CreateClassroomRequest;
import io.edupilot.classroom.dto.CreateJoinRequest;
import io.edupilot.classroom.dto.CreateClassroomWeekRequest;
import io.edupilot.classroom.dto.CreateClassroomNoticeRequest;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.LearningMaterial;
import io.edupilot.material.LearningMaterialRepository;
import io.edupilot.material.MaterialAccessService;
import io.edupilot.material.MaterialService;
import io.edupilot.material.storage.FileStorage;
import io.edupilot.session.SessionPageRecordRepository;
import io.edupilot.session.SessionService;
import io.edupilot.schedule.ScheduleService;
import io.edupilot.schedule.ScheduleType;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;

@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.MOCK,
	properties = {
		"spring.datasource.url=jdbc:h2:mem:classroom-jpa;MODE=MySQL;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.flyway.enabled=false",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"edupilot.cors.allowed-origins=http://localhost:5173",
		"edupilot.ai.base-url=http://localhost:8000",
		"edupilot.ai.internal-token=test-internal-token",
		"edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
		"edupilot.storage.root-directory=build/test-storage/classroom-jpa"
	}
)
@ActiveProfiles("jpa-context")
class ClassroomJpaContextTest {

	@Autowired
	private ClassroomRepository classroomRepository;
	@Autowired
	private ClassroomMemberRepository memberRepository;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private ClassroomService classroomService;
	@Autowired
	private ClassroomWeekService weekService;
	@Autowired
	private ClassroomWeekRepository weekRepository;
	@Autowired
	private LearningMaterialRepository materialRepository;
	@Autowired
	private MaterialAccessService materialAccessService;
	@Autowired
	private MaterialService materialService;
	@Autowired
	private SessionService sessionService;
	@Autowired
	private ClassroomNoticeService noticeService;
	@Autowired
	private ClassroomNoticeRepository noticeRepository;
	@Autowired
	private ScheduleService scheduleService;
	@MockitoBean
	private SessionPageRecordRepository pageRecordRepository;
	@MockitoBean
	private FileStorage fileStorage;

	@Test
	void persistsJoinApprovalAndQueriesOwnedAndMemberScopes() {
		assertThat(classroomRepository).isNotNull();
		User instructor = userRepository.save(User.create(
			"instructor@example.com", "hash", "홍강사", UserRole.INSTRUCTOR
		));
		User learner = userRepository.save(User.create(
			"learner@example.com", "hash", "김학습", UserRole.LEARNER
		));
		var classroom = classroomService.create(
			instructor.getId(),
			UserRole.INSTRUCTOR,
			new CreateClassroomRequest(
				"AI 기초",
				LocalDate.of(2026, 9, 1),
				LocalDate.of(2026, 12, 15),
				ClassroomColor.BLUE,
				null
			)
		);
		var request = classroomService.requestJoin(
			learner.getId(),
			UserRole.LEARNER,
			new CreateJoinRequest(" " + classroom.inviteCode().toLowerCase() + " ")
		);

		classroomService.approve(
			instructor.getId(),
			UserRole.INSTRUCTOR,
			classroom.classroomId(),
			request.requestId()
		);
		weekService.create(
			instructor.getId(),
			UserRole.INSTRUCTOR,
			classroom.classroomId(),
			new CreateClassroomWeekRequest(1, "Week 1", null)
		);
		LearningMaterial material = LearningMaterial.create(
			instructor,
			"Released material",
			"materials/released.pdf"
		);
		material.markReady(96);
		material = materialRepository.saveAndFlush(material);
		weekService.link(
			instructor.getId(),
			UserRole.INSTRUCTOR,
			classroom.classroomId(),
			1,
			material.getId()
		);
		assertThat(materialAccessService.requireAccessible(
			learner.getId(), material.getId()
		).getId()).isEqualTo(material.getId());
		var session = sessionService.create(learner.getId(), material.getId());
		when(pageRecordRepository.countDistinctByUserIdAndMaterialId(
			learner.getId(), material.getId()
		)).thenReturn(3L);

		assertThat(memberRepository.existsByClassroom_IdAndUser_Id(
			classroom.classroomId(),
			learner.getId()
		)).isTrue();
		assertThat(classroomService.list(
			instructor.getId(),
			UserRole.INSTRUCTOR,
			null,
			"AI",
			ClassroomSort.RECENT,
			0,
			20
		).items()).hasSize(1);
		assertThat(classroomService.list(
			learner.getId(),
			UserRole.LEARNER,
			null,
			"AI",
			ClassroomSort.NAME,
			0,
			20
		).items()).singleElement()
			.satisfies(item -> {
				assertThat(item.materialCount()).isEqualTo(1);
				assertThat(item.progressRate()).isEqualTo(3);
				assertThat(item.lastStudied().sessionId()).isEqualTo(session.sessionId());
				assertThat(item.pendingRequestCount()).isNull();
			});
		assertThat(weekService.list(
			learner.getId(), UserRole.LEARNER, classroom.classroomId()
		).items()).singleElement()
			.satisfies(week -> {
				assertThat(week.averageProgressRate()).isZero();
				assertThat(week.materials()).singleElement()
					.satisfies(item -> {
						assertThat(item.viewerCount()).isEqualTo(1);
						assertThat(item.viewRate()).isEqualTo(100);
					});
			});
		assertThat(materialService.list(learner.getId(), 0, 20).items()).isEmpty();

		weekService.unlink(
			instructor.getId(),
			UserRole.INSTRUCTOR,
			classroom.classroomId(),
			1,
			material.getId()
		);
		Long materialId = material.getId();
		assertThatThrownBy(() -> materialAccessService.requireAccessible(
			learner.getId(), materialId
		)).isInstanceOfSatisfying(BusinessException.class, exception ->
			assertThat(exception.errorCode()).isEqualTo(ErrorCode.MATERIAL_NOT_FOUND)
		);
		assertThat(sessionService.detail(learner.getId(), session.sessionId()).sessionId())
			.isEqualTo(session.sessionId());

		long materialCount = materialRepository.count();
		when(fileStorage.store(any(InputStream.class)))
			.thenReturn("materials/rollback.pdf");
		MockMultipartFile upload = new MockMultipartFile(
			"file",
			"rollback.pdf",
			"application/pdf",
			"%PDF-rollback".getBytes()
		);
		assertThatThrownBy(() -> materialService.upload(
			instructor.getId(),
			UserRole.INSTRUCTOR,
			upload,
			"Rollback material",
			classroom.classroomId(),
			99
		)).isInstanceOfSatisfying(BusinessException.class, exception ->
			assertThat(exception.errorCode()).isEqualTo(ErrorCode.WEEK_NOT_FOUND)
		);
		assertThat(materialRepository.count()).isEqualTo(materialCount);
		verify(fileStorage).delete("materials/rollback.pdf");

		var notice = noticeService.create(
			instructor.getId(),
			UserRole.INSTRUCTOR,
			classroom.classroomId(),
			new CreateClassroomNoticeRequest("Assignment", "Submit by Friday")
		);
		assertThat(noticeService.list(
			learner.getId(),
			UserRole.LEARNER,
			classroom.classroomId(),
			0,
			20
		).items()).singleElement()
			.satisfies(item -> assertThat(item.noticeId()).isEqualTo(notice.noticeId()));
		LocalDate today = LocalDate.now(ZoneOffset.UTC);
		assertThat(scheduleService.list(
			learner.getId(),
			UserRole.LEARNER,
			today,
			today,
			classroom.classroomId()
		).items()).extracting(item -> item.type())
			.contains(ScheduleType.WEEK_RELEASE, ScheduleType.NOTICE_PUBLISH);

		User outsider = userRepository.save(User.create(
			"outsider@example.com", "hash", "Outsider", UserRole.LEARNER
		));
		assertThatThrownBy(() -> noticeService.list(
			outsider.getId(),
			UserRole.LEARNER,
			classroom.classroomId(),
			0,
			20
		)).isInstanceOfSatisfying(BusinessException.class, exception ->
			assertThat(exception.errorCode()).isEqualTo(ErrorCode.CLASSROOM_NOT_FOUND)
		);
		assertThat(scheduleService.list(
			outsider.getId(),
			UserRole.LEARNER,
			today,
			today,
			null
		).items()).isEmpty();

		Classroom classroomEntity = classroomRepository.findById(
			classroom.classroomId()
		).orElseThrow();
		Instant rangeStart = Instant.parse("2026-09-01T00:00:00Z");
		ClassroomWeek boundaryWeek = weekRepository.saveAndFlush(
			ClassroomWeek.create(
				classroomEntity,
				2,
				"Boundary week",
				rangeStart,
				ClassroomWeekStatus.SCHEDULED,
				2
			)
		);
		ClassroomNotice boundaryNotice = noticeRepository.saveAndFlush(
			ClassroomNotice.create(
				classroomEntity,
				"Boundary notice",
				"Included",
				Instant.parse("2026-09-01T23:59:59.999999Z")
			)
		);
		noticeRepository.saveAndFlush(ClassroomNotice.create(
			classroomEntity,
			"Next day",
			"Excluded",
			Instant.parse("2026-09-02T00:00:00Z")
		));
		ClassroomNotice reservedNotice = noticeRepository.saveAndFlush(
			ClassroomNotice.create(
				classroomEntity,
				"Reserved notice",
				"Published at boundary",
				2,
				rangeStart,
				rangeStart.minusSeconds(1)
			)
		);
		assertThat(noticeRepository.findPublishedByClassroomId(
			classroom.classroomId(),
			rangeStart.minusNanos(1),
			PageRequest.of(0, 20)
		).getContent()).extracting(ClassroomNotice::getId)
			.doesNotContain(reservedNotice.getId());
		assertThat(noticeRepository.findPublishedByClassroomId(
			classroom.classroomId(),
			rangeStart,
			PageRequest.of(0, 20)
		).getContent()).extracting(ClassroomNotice::getId)
			.contains(reservedNotice.getId());
		assertThat(scheduleService.list(
			learner.getId(),
			UserRole.LEARNER,
			LocalDate.of(2026, 9, 1),
			LocalDate.of(2026, 9, 1),
			classroom.classroomId()
		).items()).extracting(item -> item.scheduleId())
			.containsExactly(
				"WEEK-" + boundaryWeek.getId(),
				"NOTICE-" + boundaryNotice.getId()
			);

		noticeService.delete(
			instructor.getId(),
			UserRole.INSTRUCTOR,
			classroom.classroomId(),
			notice.noticeId()
		);
		assertThat(noticeRepository.existsById(notice.noticeId())).isFalse();
		classroomService.complete(
			instructor.getId(), UserRole.INSTRUCTOR, classroom.classroomId()
		);
		assertThatThrownBy(() -> noticeService.create(
			instructor.getId(),
			UserRole.INSTRUCTOR,
			classroom.classroomId(),
			new CreateClassroomNoticeRequest("Closed", "No more updates")
		)).isInstanceOfSatisfying(BusinessException.class, exception ->
			assertThat(exception.errorCode()).isEqualTo(ErrorCode.CLASSROOM_COMPLETED)
		);
	}
}
