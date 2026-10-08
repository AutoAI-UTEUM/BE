package io.edupilot;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.test.context.bean.override.mockito.MockitoBean;

import io.edupilot.admin.AdminAiUsageService;
import io.edupilot.admin.AdminClassroomService;
import io.edupilot.admin.AdminUserService;
import io.edupilot.admin.xai.AdminXaiUsageService;
import io.edupilot.admin.xai.XaiAlertConfigService;
import io.edupilot.auth.AuthSessionRepository;
import io.edupilot.auth.PasswordResetTokenRepository;
import io.edupilot.auth.UserAccessGuard;
import io.edupilot.auth.EmailVerificationTokenRepository;
import io.edupilot.auth.EmailVerificationGate;
import io.edupilot.classroom.ClassroomService;
import io.edupilot.classroom.ClassroomRepository;
import io.edupilot.aiusage.AiQuotaService;
import io.edupilot.aiusage.AiUsageService;
import io.edupilot.classroom.ClassroomAnalyticsService;
import io.edupilot.classroom.ClassroomStudentService;
import io.edupilot.classroom.ClassroomWeekService;
import io.edupilot.classroom.ClassroomWeekMaterialRepository;
import io.edupilot.classroom.ClassroomNoticeService;
import io.edupilot.classroom.ClassroomResourceService;
import io.edupilot.exam.InstructorExamService;
import io.edupilot.exam.ExamAiGradingService;
import io.edupilot.exam.ExamAttemptDraftCleanupScheduler;
import io.edupilot.exam.ExamAttemptDraftService;
import io.edupilot.exam.ExamDraftService;
import io.edupilot.exam.ExamDraftPreparationService;
import io.edupilot.exam.ExamSubmissionPersistenceService;
import io.edupilot.exam.StudentExamService;
import io.edupilot.material.MaterialAccessService;
import io.edupilot.material.MaterialOutlinePersistenceService;
import io.edupilot.material.MaterialOverviewRepository;
import io.edupilot.material.MaterialOverviewService;
import io.edupilot.mail.EmailDeliveryRepository;
import io.edupilot.mail.EmailOutboxRepository;
import io.edupilot.mail.EmailOutboxStore;
import io.edupilot.mail.EmailQuotaLockRepository;
import io.edupilot.mail.EmailSendReservationRepository;
import io.edupilot.notification.NotificationBulkRepository;
import io.edupilot.notification.NotificationService;
import io.edupilot.notification.NotificationTriggerService;
import io.edupilot.policy.PolicyConsentRepository;
import io.edupilot.policy.PolicyDocumentRepository;
import io.edupilot.report.ReportCriterionCatalog;
import io.edupilot.report.ReportCriterionService;
import io.edupilot.report.ReportCriterionGenerationService;
import io.edupilot.report.ReportAiGenerationService;
import io.edupilot.report.ReportApiService;
import io.edupilot.report.ReportGenerationPersistenceService;
import io.edupilot.report.ReportGenerationService;
import io.edupilot.report.ReportSnapshotBuilder;
import io.edupilot.session.LearningProgressService;
import io.edupilot.session.QuizProposalPolicy;
import io.edupilot.schedule.ScheduleService;
import io.edupilot.schedule.PersonalScheduleService;
import io.edupilot.usernote.NoteImportService;
import io.edupilot.usernote.UserNoteService;
import io.edupilot.usernote.WrongAnswerNoteService;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@MockitoBean(types = {
	jakarta.persistence.EntityManager.class,
	io.edupilot.deletion.DeletionJournal.class,
	io.edupilot.deletion.DeletionIntentRepository.class,
	io.edupilot.deletion.DeletionJournalLockRepository.class,
	io.edupilot.deletion.DeletionWorker.class,
	io.edupilot.material.MaterialRenderStorage.class,
	io.edupilot.guardian.GuardianVerificationRequestRepository.class,
	io.edupilot.guardian.GuardianWithdrawalHook.class,
	io.edupilot.guardian.GuardianWebRequestRepository.class,
	io.edupilot.user.birthdate.BirthdateCorrectionRepository.class,
	io.edupilot.guardian.GuardianWebPersistence.class,
	io.edupilot.guardian.GuardianWebService.class,
	io.edupilot.guardian.GuardianWebWithdrawalHook.class,
	io.edupilot.guardian.GuardianConsentFence.class,
	io.edupilot.guardian.team.GuardianTeamService.class,
	io.edupilot.guardian.team.mail.GuardianTeamMailCleanup.class,
	AdminAiUsageService.class,
	AdminClassroomService.class,
	AdminUserService.class,
	AdminXaiUsageService.class,
	XaiAlertConfigService.class,
	AuthSessionRepository.class,
	PasswordResetTokenRepository.class,
	UserAccessGuard.class,
	EmailVerificationTokenRepository.class,
	EmailVerificationGate.class,
	AiUsageService.class,
	AiQuotaService.class,
	ClassroomService.class,
	ClassroomRepository.class,
	ClassroomAnalyticsService.class,
	ClassroomStudentService.class,
	ClassroomWeekService.class,
	MaterialAccessService.class,
	MaterialOutlinePersistenceService.class,
	MaterialOverviewRepository.class,
	MaterialOverviewService.class,
	EmailDeliveryRepository.class,
	EmailOutboxRepository.class,
	EmailOutboxStore.class,
	EmailQuotaLockRepository.class,
	EmailSendReservationRepository.class,
	NotificationService.class,
	NotificationTriggerService.class,
	NotificationBulkRepository.class,
	PolicyConsentRepository.class,
	PolicyDocumentRepository.class,
	LearningProgressService.class,
	io.edupilot.session.SessionStreamAccessGuard.class,
	QuizProposalPolicy.class,
	ClassroomWeekMaterialRepository.class,
	ClassroomNoticeService.class,
	ClassroomResourceService.class,
	ScheduleService.class,
	PersonalScheduleService.class,
	InstructorExamService.class,
	StudentExamService.class,
	ExamAiGradingService.class,
	ExamAttemptDraftService.class,
	ExamAttemptDraftCleanupScheduler.class,
	ExamDraftService.class,
	ExamDraftPreparationService.class,
	ExamSubmissionPersistenceService.class,
	NoteImportService.class,
	UserNoteService.class,
	WrongAnswerNoteService.class,
	ReportCriterionCatalog.class,
	ReportCriterionService.class,
	ReportCriterionGenerationService.class,
	ReportSnapshotBuilder.class,
	ReportAiGenerationService.class,
	ReportApiService.class,
	ReportGenerationPersistenceService.class,
	ReportGenerationService.class
})
public @interface Epic10ServiceMocks {
}
