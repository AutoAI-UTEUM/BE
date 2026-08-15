package io.edupilot;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.test.context.bean.override.mockito.MockitoBean;

import io.edupilot.classroom.ClassroomService;
import io.edupilot.classroom.ClassroomAnalyticsService;
import io.edupilot.classroom.ClassroomStudentService;
import io.edupilot.classroom.ClassroomWeekService;
import io.edupilot.classroom.ClassroomWeekMaterialRepository;
import io.edupilot.classroom.ClassroomNoticeService;
import io.edupilot.exam.InstructorExamService;
import io.edupilot.exam.ExamAiGradingService;
import io.edupilot.exam.ExamDraftService;
import io.edupilot.exam.ExamDraftPreparationService;
import io.edupilot.exam.ExamSubmissionPersistenceService;
import io.edupilot.exam.StudentExamService;
import io.edupilot.material.MaterialAccessService;
import io.edupilot.material.MaterialOverviewService;
import io.edupilot.notification.NotificationBulkRepository;
import io.edupilot.notification.NotificationService;
import io.edupilot.notification.NotificationTriggerService;
import io.edupilot.report.ReportCriterionCatalog;
import io.edupilot.report.ReportCriterionService;
import io.edupilot.report.ReportAiGenerationService;
import io.edupilot.report.ReportApiService;
import io.edupilot.report.ReportGenerationPersistenceService;
import io.edupilot.report.ReportGenerationService;
import io.edupilot.report.ReportSnapshotBuilder;
import io.edupilot.session.LearningProgressService;
import io.edupilot.schedule.ScheduleService;
import io.edupilot.schedule.PersonalScheduleService;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@MockitoBean(types = {
	ClassroomService.class,
	ClassroomAnalyticsService.class,
	ClassroomStudentService.class,
	ClassroomWeekService.class,
	MaterialAccessService.class,
	MaterialOverviewService.class,
	NotificationService.class,
	NotificationTriggerService.class,
	NotificationBulkRepository.class,
	LearningProgressService.class,
	ClassroomWeekMaterialRepository.class,
	ClassroomNoticeService.class,
	ScheduleService.class,
	PersonalScheduleService.class,
	InstructorExamService.class,
	StudentExamService.class,
	ExamAiGradingService.class,
	ExamDraftService.class,
	ExamDraftPreparationService.class,
	ExamSubmissionPersistenceService.class,
	ReportCriterionCatalog.class,
	ReportCriterionService.class,
	ReportSnapshotBuilder.class,
	ReportAiGenerationService.class,
	ReportApiService.class,
	ReportGenerationPersistenceService.class,
	ReportGenerationService.class
})
public @interface Epic10ServiceMocks {
}
