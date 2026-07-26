package io.edupilot.memory;

import java.time.Instant;
import java.util.List;

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
		LearningMaterial material,
		MemoryWrite write
	) {
		this.user = user;
		this.material = material;
		apply(write);
	}

	public static LearnerMemory create(
		User user,
		LearningMaterial material,
		MemoryWrite write
	) {
		return new LearnerMemory(user, material, write);
	}

	public void apply(MemoryWrite write) {
		this.strengths = List.copyOf(write.strengths());
		this.weaknesses = List.copyOf(write.weaknesses());
		this.misconceptions = List.copyOf(write.misconceptions());
		this.explanationPreferences =
			List.copyOf(write.explanationPreferences());
		this.preferredQuizTypes = List.copyOf(write.preferredQuizTypes());
		this.targetDifficulty = write.targetDifficulty();
		this.nextCoachingGoals = List.copyOf(write.nextCoachingGoals());
		this.memoryDigest = write.memoryDigest();
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

	public List<String> getExplanationPreferences() {
		return List.copyOf(explanationPreferences);
	}

	public List<String> getPreferredQuizTypes() {
		return List.copyOf(preferredQuizTypes);
	}

	public String getMemoryDigest() {
		return memoryDigest;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
