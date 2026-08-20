package io.edupilot.exam;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.ai.dto.ExamDraftRequest;
import io.edupilot.classroom.Classroom;
import io.edupilot.classroom.ClassroomService;
import io.edupilot.classroom.ClassroomWeekMaterial;
import io.edupilot.classroom.ClassroomWeekMaterialRepository;
import io.edupilot.exam.dto.GenerateExamDraftRequest;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.MaterialPageRepository;
import io.edupilot.material.MaterialPageTextMerger;
import io.edupilot.material.MaterialProcessingStatus;
import io.edupilot.material.MaterialStatus;
import io.edupilot.user.UserRole;

@Service
public class ExamDraftPreparationService {

	private static final String SCHEMA_VERSION = "1.0";
	private static final int MAX_PAGE_CONTEXTS = 30;

	private final ClassroomService classroomService;
	private final ExamRepository examRepository;
	private final ClassroomWeekMaterialRepository weekMaterialRepository;
	private final MaterialPageRepository materialPageRepository;
	private final MaterialPageTextMerger pageTextMerger;

	public ExamDraftPreparationService(
		ClassroomService classroomService,
		ExamRepository examRepository,
		ClassroomWeekMaterialRepository weekMaterialRepository,
		MaterialPageRepository materialPageRepository,
		MaterialPageTextMerger pageTextMerger
	) {
		this.classroomService = classroomService;
		this.examRepository = examRepository;
		this.weekMaterialRepository = weekMaterialRepository;
		this.materialPageRepository = materialPageRepository;
		this.pageTextMerger = pageTextMerger;
	}

	@Transactional(readOnly = true)
	public PreparedExamDraft prepare(
		Long userId,
		UserRole role,
		Long classroomId,
		Long examId,
		GenerateExamDraftRequest request
	) {
		Classroom classroom = classroomService.requireStrictOwner(
			userId, role, classroomId
		);
		Exam exam = examRepository.findWithClassroomById(examId)
			.filter(candidate -> candidate.getClassroomId().equals(classroomId))
			.orElseThrow(() -> new BusinessException(ErrorCode.EXAM_NOT_FOUND));
		if (exam.getStatus() != ExamStatus.DRAFT) {
			throw new BusinessException(ErrorCode.EXAM_NOT_EDITABLE);
		}

		List<ExamDraftRequest.QuestionPlanItem> questionPlan = validatePlan(request);
		validateWeekNumber(classroom, request.weekNumber());

		List<ClassroomWeekMaterial> candidates =
			weekMaterialRepository.findReportMaterialCandidates(
				classroomId,
				null,
				MaterialStatus.ACTIVE,
				MaterialProcessingStatus.READY
			);
		Set<Long> classroomMaterialIds = candidates.stream()
			.map(link -> link.getMaterial().getId())
			.collect(java.util.stream.Collectors.toSet());
		Set<Long> requestedMaterialIds = requestedMaterialIds(
			request.materialIds(), classroomMaterialIds
		);

		List<Long> targetMaterialIds = candidates.stream()
			.filter(link -> request.weekNumber() == null
				|| link.getWeek().getWeekNumber() == request.weekNumber())
			.map(link -> link.getMaterial().getId())
			.filter(id -> requestedMaterialIds == null || requestedMaterialIds.contains(id))
			.distinct()
			.toList();

		List<MaterialPageRepository.ExamDraftPageText> pages = targetMaterialIds.isEmpty()
			? List.of()
			: materialPageRepository.findExamDraftPages(targetMaterialIds).stream()
				.filter(page -> page.getText() != null && !page.getText().isBlank())
				.toList();
		if (pages.isEmpty()) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}

		boolean truncated = pages.size() > MAX_PAGE_CONTEXTS;
		List<ExamDraftRequest.PageContext> pageContexts = java.util.stream.IntStream
			.range(0, Math.min(pages.size(), MAX_PAGE_CONTEXTS))
			.mapToObj(index -> new ExamDraftRequest.PageContext(
				index + 1,
				pageTextMerger.mergeCaption(
					pages.get(index).getText(),
					pages.get(index).getCaption()
				)
			))
			.toList();

		return new PreparedExamDraft(
			new ExamDraftRequest(SCHEMA_VERSION, examId, pageContexts, questionPlan),
			pageContexts.stream()
				.map(ExamDraftRequest.PageContext::pageNumber)
				.collect(java.util.stream.Collectors.toUnmodifiableSet()),
			truncated
		);
	}

	private List<ExamDraftRequest.QuestionPlanItem> validatePlan(
		GenerateExamDraftRequest request
	) {
		if (request == null || request.questionPlan() == null
			|| request.questionPlan().isEmpty()) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		Set<ExamQuestionType> types = new HashSet<>();
		long total = 0;
		for (GenerateExamDraftRequest.QuestionPlanItem item : request.questionPlan()) {
			if (item == null || item.questionType() == null || item.count() == null
				|| item.count() < 1 || !types.add(item.questionType())) {
				throw new BusinessException(ErrorCode.VALIDATION_FAILED);
			}
			total += item.count();
		}
		if (total < 1 || total > 20) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		return request.questionPlan().stream()
			.map(item -> new ExamDraftRequest.QuestionPlanItem(
				item.questionType(), item.count()
			))
			.toList();
	}

	private Set<Long> requestedMaterialIds(
		List<Long> materialIds,
		Set<Long> classroomMaterialIds
	) {
		if (materialIds == null) {
			return null;
		}
		LinkedHashSet<Long> unique = new LinkedHashSet<>(materialIds);
		if (unique.size() != materialIds.size() || unique.contains(null)) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		if (!classroomMaterialIds.containsAll(unique)) {
			throw new BusinessException(ErrorCode.MATERIAL_NOT_FOUND);
		}
		return Set.copyOf(unique);
	}

	private void validateWeekNumber(Classroom classroom, Integer weekNumber) {
		if (weekNumber != null
			&& (weekNumber < 1 || weekNumber > classroom.getWeekCount())) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
	}

	public record PreparedExamDraft(
		ExamDraftRequest aiRequest,
		Set<Integer> sourcePageNumbers,
		boolean truncated
	) {
	}
}
