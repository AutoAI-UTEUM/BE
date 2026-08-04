package io.edupilot.session;

import java.time.Clock;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.classroom.Classroom;
import io.edupilot.classroom.ClassroomMemberRepository;
import io.edupilot.classroom.ClassroomRepository;
import io.edupilot.classroom.ClassroomWeekMaterialRepository;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.LearningMaterial;
import io.edupilot.material.LearningMaterialRepository;

@Service
public class LearningProgressService {

	private final LearningSessionRepository sessionRepository;
	private final LearningMaterialRepository materialRepository;
	private final SessionPageRecordRepository pageRecordRepository;
	private final ClassroomRepository classroomRepository;
	private final ClassroomMemberRepository memberRepository;
	private final ClassroomWeekMaterialRepository weekMaterialRepository;
	private final Clock clock;

	public LearningProgressService(
		LearningSessionRepository sessionRepository,
		LearningMaterialRepository materialRepository,
		SessionPageRecordRepository pageRecordRepository,
		ClassroomRepository classroomRepository,
		ClassroomMemberRepository memberRepository,
		ClassroomWeekMaterialRepository weekMaterialRepository,
		Clock clock
	) {
		this.sessionRepository = sessionRepository;
		this.materialRepository = materialRepository;
		this.pageRecordRepository = pageRecordRepository;
		this.classroomRepository = classroomRepository;
		this.memberRepository = memberRepository;
		this.weekMaterialRepository = weekMaterialRepository;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public int calculateSessionProgressRate(Long userId, Long sessionId) {
		LearningSession session = sessionRepository
			.findByIdAndUser_Id(sessionId, userId)
			.filter(candidate -> candidate.getStatus() != SessionStatus.DELETED)
			.orElseThrow(() ->
				new BusinessException(ErrorCode.SESSION_NOT_FOUND));
		return progressRate(
			pageRecordRepository.countBySessionId(sessionId),
			session.getMaterialPageCount()
		);
	}

	@Transactional(readOnly = true)
	public int calculateMaterialProgressRate(Long userId, Long materialId) {
		LearningMaterial material = materialRepository.findById(materialId)
			.orElseThrow(() ->
				new BusinessException(ErrorCode.MATERIAL_NOT_FOUND));
		return progressRate(
			pageRecordRepository.countDistinctByUserIdAndMaterialId(
				userId,
				materialId
			),
			material.getPageCount()
		);
	}

	@Transactional(readOnly = true)
	public int calculateClassroomProgressRate(Long userId, Long classroomId) {
		Classroom classroom = classroomRepository.findWithInstructorById(classroomId)
			.orElseThrow(() -> new BusinessException(ErrorCode.CLASSROOM_NOT_FOUND));
		if (!classroom.getInstructorId().equals(userId)
			&& !memberRepository.existsByClassroom_IdAndUser_Id(classroomId, userId)) {
			throw new BusinessException(ErrorCode.CLASSROOM_NOT_FOUND);
		}
		List<LearningMaterial> materials = weekMaterialRepository
			.findDistinctReleasedReadyMaterials(
				classroomId,
				clock.instant(),
				io.edupilot.material.MaterialStatus.ACTIVE,
				io.edupilot.material.MaterialProcessingStatus.READY
			);
		long explainedPages = 0;
		long totalPages = 0;
		for (LearningMaterial material : materials) {
			Integer pageCount = material.getPageCount();
			if (pageCount == null || pageCount < 1) {
				continue;
			}
			totalPages += pageCount;
			explainedPages += pageRecordRepository
				.countDistinctByUserIdAndMaterialId(userId, material.getId());
		}
		return progressRate(explainedPages, totalPages);
	}

	@Transactional(readOnly = true)
	public ReportProgress calculateReportProgress(
		Long userId,
		List<LearningMaterial> materials
	) {
		long explainedPages = 0;
		long totalPages = 0;
		for (LearningMaterial material : materials) {
			Integer pageCount = material.getPageCount();
			if (pageCount == null || pageCount < 1) {
				continue;
			}
			totalPages += pageCount;
			explainedPages += pageRecordRepository
				.countDistinctByUserIdAndMaterialId(userId, material.getId());
		}
		return new ReportProgress(
			explainedPages,
			totalPages,
			progressRate(explainedPages, totalPages),
			explainedPages > 0
		);
	}

	@Transactional(readOnly = true)
	public ClassroomProgressSnapshot calculateClassroomProgressSnapshot(
		Long classroomId,
		Collection<LearningMaterial> materials,
		long learnerCount
	) {
		Map<Long, LearningMaterial> validMaterials = new LinkedHashMap<>();
		for (LearningMaterial material : materials) {
			if (material.getPageCount() != null && material.getPageCount() > 0) {
				validMaterials.putIfAbsent(material.getId(), material);
			}
		}
		Map<Long, Long> explainedPagesByMaterial = new LinkedHashMap<>();
		for (var count : pageRecordRepository.findClassroomProgressCounts(
			classroomId,
			validMaterials.keySet()
		)) {
			explainedPagesByMaterial.merge(
				count.materialId(),
				count.explainedPageCount(),
				Long::sum
			);
		}
		return new ClassroomProgressSnapshot(
			learnerCount,
			Map.copyOf(explainedPagesByMaterial)
		);
	}

	private int progressRate(long explainedPageCount, Integer pageCount) {
		return progressRate(
			explainedPageCount,
			pageCount == null ? 0L : pageCount.longValue()
		);
	}

	private int progressRate(long explainedPageCount, long pageCount) {
		if (explainedPageCount == 0 || pageCount < 1) {
			return 0;
		}
		return (int) Math.round(explainedPageCount * 100.0 / pageCount);
	}

	public record ReportProgress(
		long explainedPages,
		long totalPages,
		int progressRate,
		boolean progressDataAvailable
	) {
	}

	public record ClassroomProgressSnapshot(
		long learnerCount,
		Map<Long, Long> explainedPagesByMaterial
	) {
		public int averageProgressRate(Collection<LearningMaterial> materials) {
			long explainedPages = 0;
			long totalPagesPerLearner = 0;
			Map<Long, LearningMaterial> distinctMaterials = new LinkedHashMap<>();
			for (LearningMaterial material : materials) {
				if (material.getPageCount() != null && material.getPageCount() > 0) {
					distinctMaterials.putIfAbsent(material.getId(), material);
				}
			}
			for (LearningMaterial material : distinctMaterials.values()) {
				totalPagesPerLearner += material.getPageCount();
				explainedPages += explainedPagesByMaterial.getOrDefault(
					material.getId(),
					0L
				);
			}
			return roundedRate(
				explainedPages,
				learnerCount * totalPagesPerLearner
			);
		}

		public int materialAverageProgressRate(LearningMaterial material) {
			Integer pageCount = material.getPageCount();
			if (pageCount == null || pageCount < 1) {
				return 0;
			}
			return roundedRate(
				explainedPagesByMaterial.getOrDefault(material.getId(), 0L),
				learnerCount * pageCount.longValue()
			);
		}

		private static int roundedRate(long numerator, long denominator) {
			if (numerator == 0 || denominator < 1) {
				return 0;
			}
			return (int) Math.round(numerator * 100.0 / denominator);
		}
	}
}
