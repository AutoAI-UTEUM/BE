package io.edupilot;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.test.context.bean.override.mockito.MockitoBean;

import io.edupilot.classroom.ClassroomService;
import io.edupilot.classroom.ClassroomWeekService;
import io.edupilot.classroom.ClassroomWeekMaterialRepository;
import io.edupilot.classroom.ClassroomNoticeService;
import io.edupilot.exam.InstructorExamService;
import io.edupilot.exam.ExamAiGradingService;
import io.edupilot.exam.ExamSubmissionPersistenceService;
import io.edupilot.exam.StudentExamService;
import io.edupilot.material.MaterialAccessService;
import io.edupilot.report.ReportCriterionCatalog;
import io.edupilot.report.ReportSnapshotBuilder;
import io.edupilot.session.LearningProgressService;
import io.edupilot.schedule.ScheduleService;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@MockitoBean(types = {
	ClassroomService.class,
	ClassroomWeekService.class,
	MaterialAccessService.class,
	LearningProgressService.class,
	ClassroomWeekMaterialRepository.class,
	ClassroomNoticeService.class,
	ScheduleService.class,
	InstructorExamService.class,
	StudentExamService.class,
	ExamAiGradingService.class,
	ExamSubmissionPersistenceService.class,
	ReportCriterionCatalog.class,
	ReportSnapshotBuilder.class
})
public @interface Epic10ServiceMocks {
}
