package io.edupilot.report;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import io.edupilot.classroom.Classroom;
import io.edupilot.classroom.ClassroomService;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.report.dto.CreateReportCriterionRequest;
import io.edupilot.report.dto.ReportCriterionListResponse;
import io.edupilot.report.dto.ReportCriterionResponse;
import io.edupilot.report.dto.UpdateReportCriterionRequest;
import io.edupilot.user.UserRole;

@Service
public class ReportCriterionService {

	private static final int MAX_ACTIVE_CRITERIA = 20;

	private final ClassroomService classroomService;
	private final ReportCriterionRepository criterionRepository;
	private final ReportCriterionCatalog criterionCatalog;

	public ReportCriterionService(
		ClassroomService classroomService,
		ReportCriterionRepository criterionRepository,
		ReportCriterionCatalog criterionCatalog
	) {
		this.classroomService = classroomService;
		this.criterionRepository = criterionRepository;
		this.criterionCatalog = criterionCatalog;
	}

	@Transactional(readOnly = true)
	public ReportCriterionListResponse list(
		Long instructorId,
		UserRole role,
		Long classroomId
	) {
		classroomService.requireStrictOwner(instructorId, role, classroomId);
		List<ReportCriterionResponse> items = new ArrayList<>();
		criterionCatalog.defaultCriteria().stream()
			.map(this::builtinResponse)
			.forEach(items::add);
		criterionRepository
			.findByClassroom_IdAndActiveTrueOrderByCriterionKeyAscVersionDesc(classroomId)
			.stream()
			.map(this::customResponse)
			.forEach(items::add);
		return new ReportCriterionListResponse(List.copyOf(items));
	}

	@Transactional
	public ReportCriterionResponse create(
		Long instructorId,
		UserRole role,
		Long classroomId,
		CreateReportCriterionRequest request
	) {
		Classroom classroom = classroomService.requireStrictOwnerForUpdate(
			instructorId, role, classroomId
		);
		String key = normalizedKey(request.criterionKey());
		if (criterionRepository.existsByClassroom_IdAndCriterionKey(classroomId, key)) {
			throw duplicate();
		}
		validateActivation(classroomId, request.name(), null, true);
		ReportCriterion criterion = criterionRepository.save(ReportCriterion.create(
			classroom,
			key,
			normalizedRequired(request.name()),
			normalizedOptional(request.description()),
			Map.copyOf(request.rubric()),
			sourceNames(request.allowedSources()),
			request.minEvidence(),
			request.weight(),
			1,
			true
		));
		return customResponse(criterion);
	}

	@Transactional
	public ReportCriterionResponse update(
		Long instructorId,
		UserRole role,
		Long classroomId,
		Long criterionId,
		UpdateReportCriterionRequest request
	) {
		if (!request.hasAnyChange()) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		Classroom classroom = classroomService.requireStrictOwnerForUpdate(
			instructorId, role, classroomId
		);
		ReportCriterion current = criterionRepository
			.findByIdAndClassroom_Id(criterionId, classroomId)
			.orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND));
		List<ReportCriterion> versions = criterionRepository
			.findByClassroom_IdAndCriterionKeyOrderByVersionDesc(
				classroomId, current.getCriterionKey()
			);
		if (versions.isEmpty() || !versions.get(0).getId().equals(current.getId())) {
			throw new BusinessException(ErrorCode.REPORT_NOT_FOUND);
		}
		if (!request.hasContentChanges()) {
			return toggle(classroomId, current, request.active());
		}

		boolean nextActive = request.active() == null
			? current.isActive() : request.active();
		String nextName = request.name() == null
			? current.getName() : normalizedRequired(request.name());
		if (nextActive) {
			validateActivation(
				classroomId, nextName, current.getId(), !current.isActive()
			);
		}
		current.deactivate();
		ReportCriterion next = criterionRepository.save(ReportCriterion.create(
			classroom,
			current.getCriterionKey(),
			nextName,
			request.description() == null
				? current.getDescription() : normalizedOptional(request.description()),
			request.rubric() == null
				? current.getRubric() : Map.copyOf(request.rubric()),
			request.allowedSources() == null
				? current.getAllowedSources() : sourceNames(request.allowedSources()),
			request.minEvidence() == null
				? current.getMinEvidence() : request.minEvidence(),
			request.weight() == null ? current.getWeight() : request.weight(),
			current.getVersion() + 1,
			nextActive
		));
		return customResponse(next);
	}

	private ReportCriterionResponse toggle(
		Long classroomId,
		ReportCriterion criterion,
		boolean active
	) {
		if (active && !criterion.isActive()) {
			validateActivation(classroomId, criterion.getName(), criterion.getId(), true);
			criterion.activate();
		} else if (!active) {
			criterion.deactivate();
		}
		return customResponse(criterion);
	}

	private void validateActivation(
		Long classroomId,
		String name,
		Long excludedCriterionId,
		boolean addsActiveCriterion
	) {
		if (addsActiveCriterion && criterionCatalog.defaultCriteria().size()
			+ criterionRepository.countByClassroom_IdAndActiveTrue(classroomId)
			>= MAX_ACTIVE_CRITERIA) {
			throw new BusinessException(ErrorCode.REPORT_CRITERION_LIMIT_EXCEEDED);
		}
		String normalizedName = normalizedName(name);
		boolean defaultDuplicate = criterionCatalog.defaultCriteria().stream()
			.anyMatch(item -> normalizedName(item.name()).equals(normalizedName));
		boolean customDuplicate = criterionRepository
			.findByClassroom_IdAndActiveTrueOrderByCriterionKeyAscVersionDesc(classroomId)
			.stream()
			.filter(item -> !item.getId().equals(excludedCriterionId))
			.anyMatch(item -> normalizedName(item.getName()).equals(normalizedName));
		if (defaultDuplicate || customDuplicate) {
			throw duplicate();
		}
	}

	private ReportCriterionResponse builtinResponse(
		ReportCriterionDefinition criterion
	) {
		return new ReportCriterionResponse(
			null,
			criterion.key(),
			criterion.name(),
			criterion.rubricSummary(),
			Map.of("summary", criterion.rubricSummary()),
			criterion.allowedSources().stream().map(Enum::name).sorted().toList(),
			criterion.minEvidence(),
			criterion.weight(),
			criterion.version(),
			true,
			true
		);
	}

	private ReportCriterionResponse customResponse(ReportCriterion criterion) {
		return new ReportCriterionResponse(
			criterion.getId(),
			criterion.getCriterionKey(),
			criterion.getName(),
			criterion.getDescription(),
			criterion.getRubric(),
			criterion.getAllowedSources(),
			criterion.getMinEvidence(),
			criterion.getWeight(),
			Integer.toString(criterion.getVersion()),
			criterion.isActive(),
			false
		);
	}

	private String normalizedKey(String value) {
		String normalized = normalizedRequired(value)
			.toLowerCase(Locale.ROOT)
			.replaceAll("[\\s-]+", "_");
		if (!normalized.matches("[a-z0-9_]+") || normalized.length() > 50) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		return normalized;
	}

	private String normalizedName(String value) {
		return normalizedRequired(value)
			.replaceAll("\\s+", " ")
			.toLowerCase(Locale.ROOT);
	}

	private String normalizedRequired(String value) {
		if (!StringUtils.hasText(value)) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		return value.trim().replaceAll("\\s+", " ");
	}

	private String normalizedOptional(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}

	private List<String> sourceNames(List<ReportSourceType> sources) {
		return sources.stream().map(Enum::name).distinct().sorted().toList();
	}

	private BusinessException duplicate() {
		return new BusinessException(ErrorCode.REPORT_CRITERION_DUPLICATE);
	}
}
