package io.edupilot.report;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import io.edupilot.ai.AiClient;
import io.edupilot.ai.AiClientException;
import io.edupilot.ai.dto.CriteriaSuggestRequest;
import io.edupilot.ai.dto.CriteriaSuggestResponse;
import io.edupilot.aiusage.AiFeature;
import io.edupilot.aiusage.AiUsageService;
import io.edupilot.classroom.ClassroomService;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.MaterialOverview;
import io.edupilot.material.MaterialOverviewRepository;
import io.edupilot.report.dto.CreateReportCriterionRequest;
import io.edupilot.report.dto.ReportCriterionGenerationResponse;
import io.edupilot.user.UserRole;

@Service
public class ReportCriterionGenerationService {

	private static final Logger log = LoggerFactory.getLogger(
		ReportCriterionGenerationService.class
	);
	private static final String SCHEMA_VERSION = "1.0";
	private static final String RUNNING_MESSAGE =
		"평가 지표 생성 작업이 이미 진행 중입니다.";
	private static final String SLOT_MESSAGE = "기존 지표 정리 후 재시도";
	private static final String DUPLICATE_MESSAGE =
		"중복된 평가 지표를 정리한 후 재시도해 주세요.";
	private static final String FAILURE_MESSAGE =
		"평가 지표 생성에 실패했습니다. 잠시 후 다시 시도해 주세요.";

	private final ClassroomService classroomService;
	private final MaterialOverviewRepository overviewRepository;
	private final ReportCriterionService criterionService;
	private final AiClient aiClient;
	private final AiUsageService aiUsageService;
	private final Executor executor;
	private final ConcurrentMap<Long, GenerationState> states =
		new ConcurrentHashMap<>();

	public ReportCriterionGenerationService(
		ClassroomService classroomService,
		MaterialOverviewRepository overviewRepository,
		ReportCriterionService criterionService,
		AiClient aiClient,
		AiUsageService aiUsageService,
		@Qualifier("reportGenerationExecutor") Executor executor
	) {
		this.classroomService = classroomService;
		this.overviewRepository = overviewRepository;
		this.criterionService = criterionService;
		this.aiClient = aiClient;
		this.aiUsageService = aiUsageService;
		this.executor = executor;
	}

	public ReportCriterionGenerationResponse start(
		Long instructorId,
		UserRole role,
		Long classroomId
	) {
		classroomService.requireStrictOwnerForUpdate(
			instructorId, role, classroomId
		);
		List<MaterialOverview> overviews = overviewRepository
			.findReadyByClassroomId(classroomId)
			.stream()
			.filter(overview -> overview.getOutline() != null)
			.toList();
		if (overviews.isEmpty()) {
			throw new BusinessException(
				ErrorCode.REPORT_CRITERIA_GENERATION_NOT_READY
			);
		}

		ReportCriterionService.GenerationContext context =
			criterionService.generationContext(classroomId);
		if (context.availableSlots() < 3) {
			throw new BusinessException(
				ErrorCode.REPORT_CRITERION_LIMIT_EXCEEDED
			);
		}

		CriteriaSuggestRequest request = new CriteriaSuggestRequest(
			SCHEMA_VERSION,
			context.existingCriterionKeys(),
			overviews.stream().map(overview -> new CriteriaSuggestRequest.Material(
				overview.getMaterialTitle(),
				overview.getOutline().materialSummary(),
				overview.getOutline().sections()
			)).toList()
		);
		begin(classroomId);
		GenerationCommand command = new GenerationCommand(
			instructorId, role, classroomId, context.availableSlots(), request
		);
		try {
			executor.execute(() -> generate(command));
		} catch (RejectedExecutionException exception) {
			states.put(classroomId, GenerationState.failed(FAILURE_MESSAGE));
			log.atWarn()
				.addKeyValue("classroomId", classroomId)
				.log("Report criterion generation executor rejected task");
		}
		return GenerationState.running().toResponse();
	}

	public ReportCriterionGenerationResponse status(
		Long instructorId,
		UserRole role,
		Long classroomId
	) {
		classroomService.requireStrictOwnerForUpdate(
			instructorId, role, classroomId
		);
		return states.getOrDefault(classroomId, GenerationState.idle())
			.toResponse();
	}

	private void begin(Long classroomId) {
		AtomicBoolean started = new AtomicBoolean(false);
		states.compute(classroomId, (ignored, current) -> {
			if (current != null && current.status() == Status.RUNNING) {
				return current;
			}
			started.set(true);
			return GenerationState.running();
		});
		if (!started.get()) {
			throw new BusinessException(
				ErrorCode.REPORT_CRITERION_DUPLICATE,
				RUNNING_MESSAGE
			);
		}
	}

	private void generate(GenerationCommand command) {
		try {
			CriteriaSuggestResponse response = aiClient.suggestCriteria(
				command.request()
			);
			aiUsageService.record(
				command.instructorId(),
				AiFeature.CRITERIA,
				response == null ? null : response.usage(),
				true
			);
			if (response.criteria().size() > command.availableSlots()) {
				states.put(
					command.classroomId(),
					GenerationState.failed(SLOT_MESSAGE)
				);
				return;
			}
			List<CreateReportCriterionRequest> requests = response.criteria()
				.stream()
				.map(this::registrationRequest)
				.toList();
			int registeredCount = criterionService.registerGenerated(
				command.instructorId(),
				command.role(),
				command.classroomId(),
				requests
			);
			states.put(
				command.classroomId(),
				GenerationState.completed(
					registeredCount,
					warningMessage(response.warnings())
				)
			);
		} catch (BusinessException exception) {
			if (exception instanceof AiClientException) {
				aiUsageService.record(
					command.instructorId(),
					AiFeature.CRITERIA,
					null,
					false
				);
			}
			String message = exception.errorCode()
				== ErrorCode.REPORT_CRITERION_DUPLICATE
				? DUPLICATE_MESSAGE : SLOT_MESSAGE;
			states.put(
				command.classroomId(), GenerationState.failed(message)
			);
			logFailure(command.classroomId(), exception);
		} catch (RuntimeException exception) {
			states.put(
				command.classroomId(),
				GenerationState.failed(FAILURE_MESSAGE)
			);
			logFailure(command.classroomId(), exception);
		}
	}

	private CreateReportCriterionRequest registrationRequest(
		CriteriaSuggestResponse.Criterion criterion
	) {
		String description = criterion.description();
		if (description.length() > 500) {
			description = description.substring(0, 500);
		}
		return new CreateReportCriterionRequest(
			criterion.key(),
			criterion.name(),
			description,
			Map.of("summary", criterion.rubric()),
			criterion.allowedSources(),
			criterion.minimumEvidence(),
			criterion.weight()
		);
	}

	private String warningMessage(List<CriteriaSuggestResponse.Warning> warnings) {
		if (warnings.isEmpty()) {
			return null;
		}
		return warnings.stream()
			.map(warning -> warning.type() + ": " + warning.message())
			.reduce((left, right) -> left + "; " + right)
			.orElse(null);
	}

	private void logFailure(Long classroomId, RuntimeException exception) {
		log.atWarn()
			.addKeyValue("classroomId", classroomId)
			.addKeyValue("failureType", exception.getClass().getSimpleName())
			.log("Report criterion generation failed");
	}

	private enum Status {
		IDLE,
		RUNNING,
		COMPLETED,
		FAILED
	}

	private record GenerationCommand(
		Long instructorId,
		UserRole role,
		Long classroomId,
		int availableSlots,
		CriteriaSuggestRequest request
	) {
	}

	private record GenerationState(
		Status status,
		Integer registeredCount,
		String message
	) {
		static GenerationState idle() {
			return new GenerationState(Status.IDLE, null, null);
		}

		static GenerationState running() {
			return new GenerationState(Status.RUNNING, null, null);
		}

		static GenerationState completed(int count, String message) {
			return new GenerationState(Status.COMPLETED, count, message);
		}

		static GenerationState failed(String message) {
			return new GenerationState(Status.FAILED, null, message);
		}

		ReportCriterionGenerationResponse toResponse() {
			return new ReportCriterionGenerationResponse(
				status.name(), registeredCount, message
			);
		}
	}
}
