package io.edupilot.memory;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import io.edupilot.material.LearningMaterial;
import io.edupilot.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "learner_memories")
public class LearnerMemory {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "material_id", nullable = false)
	private LearningMaterial material;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "strengths_json", nullable = false, columnDefinition = "json")
	private List<String> strengths;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "weaknesses_json", nullable = false, columnDefinition = "json")
	private List<String> weaknesses;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "misconceptions_json", nullable = false, columnDefinition = "json")
	private List<String> misconceptions;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "explanation_preferences_json", nullable = false, columnDefinition = "json")
	private List<String> explanationPreferences;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "preferred_quiz_types_json", nullable = false, columnDefinition = "json")
	private List<String> preferredQuizTypes;

	@Column(name = "target_difficulty", length = 30)
	private String targetDifficulty;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "next_coaching_goals_json", nullable = false, columnDefinition = "json")
	private List<String> nextCoachingGoals;

	@Column(name = "memory_digest", columnDefinition = "TEXT")
	private String memoryDigest;

	@Version
	@Column(nullable = false)
	private long version;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected LearnerMemory() {
	}

	private LearnerMemory(
		User user,
		LearningMaterial material
	) {
		this.user = user;
		this.material = material;
		this.strengths = List.of();
		this.weaknesses = List.of();
		this.misconceptions = List.of();
		this.explanationPreferences = List.of();
		this.preferredQuizTypes = List.of();
		this.nextCoachingGoals = List.of();
	}

	public static LearnerMemory create(
		User user,
		LearningMaterial material
	) {
		return new LearnerMemory(user, material);
	}

	public void applyCandidates(List<LearnerMemoryCandidate> candidates) {
		Set<String> nextStrengths = new LinkedHashSet<>(strengths);
		Set<String> nextWeaknesses = new LinkedHashSet<>(weaknesses);
		Set<String> nextMisconceptions = new LinkedHashSet<>(misconceptions);
		Set<String> nextPreferences = new LinkedHashSet<>(
			explanationPreferences
		);
		for (LearnerMemoryCandidate candidate : candidates) {
			switch (candidate.getCandidateType()) {
				case "STRENGTH" -> nextStrengths.add(candidate.getContent());
				case "WEAKNESS" -> nextWeaknesses.add(candidate.getContent());
				case "MISCONCEPTION" ->
					nextMisconceptions.add(candidate.getContent());
				case "PREFERENCE" ->
					nextPreferences.add(candidate.getContent());
				default -> throw new IllegalArgumentException(
					"Unsupported memory candidate type"
				);
			}
		}
		this.strengths = List.copyOf(nextStrengths);
		this.weaknesses = List.copyOf(nextWeaknesses);
		this.misconceptions = List.copyOf(nextMisconceptions);
		this.explanationPreferences = List.copyOf(nextPreferences);
		this.memoryDigest = Stream.of(
			strengths,
			weaknesses,
			misconceptions,
			explanationPreferences,
			preferredQuizTypes,
			nextCoachingGoals
		)
			.flatMap(List::stream)
			.distinct()
			.collect(java.util.stream.Collectors.joining("; "));
		if (memoryDigest.isEmpty()) {
			memoryDigest = null;
		}
	}

	public Long getId() {
		return id;
	}

	public List<String> getStrengths() {
		return List.copyOf(strengths);
	}

	public List<String> getWeaknesses() {
		return List.copyOf(weaknesses);
	}

	public List<String> getMisconceptions() {
		return List.copyOf(misconceptions);
	}

	public List<String> getExplanationPreferences() {
		return List.copyOf(explanationPreferences);
	}

	public List<String> getPreferredQuizTypes() {
		return List.copyOf(preferredQuizTypes);
	}

	public String getMemoryDigest() {
		return memoryDigest;
	}

	public String getTargetDifficulty() {
		return targetDifficulty;
	}

	public List<String> getNextCoachingGoals() {
		return List.copyOf(nextCoachingGoals);
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
