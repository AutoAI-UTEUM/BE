package io.edupilot.aiusage;

import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;

import io.edupilot.ai.dto.AiUsage;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(
	name = "ai_usage_log",
	indexes = {
		@Index(
			name = "idx_ai_usage_user_day",
			columnList = "user_id, created_at"
		),
		@Index(
			name = "idx_ai_usage_feature",
			columnList = "feature, created_at"
		)
	}
)
public class AiUsageLog {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 40)
	private AiFeature feature;

	@Column(length = 50)
	private String model;

	@Column(name = "input_tokens")
	private Long inputTokens;

	@Column(name = "output_tokens")
	private Long outputTokens;

	@Column(name = "reasoning_tokens")
	private Long reasoningTokens;

	@Column(nullable = false)
	private boolean success;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected AiUsageLog() {
	}

	private AiUsageLog(
		Long userId,
		AiFeature feature,
		AiUsage usage,
		boolean success
	) {
		this.userId = userId;
		this.feature = feature;
		this.model = usage == null ? null : usage.model();
		this.inputTokens = usage == null ? null : usage.inputTokens();
		this.outputTokens = usage == null ? null : usage.outputTokens();
		this.reasoningTokens = usage == null ? null : usage.reasoningTokens();
		this.success = success;
	}

	public static AiUsageLog create(
		Long userId,
		AiFeature feature,
		AiUsage usage,
		boolean success
	) {
		return new AiUsageLog(userId, feature, usage, success);
	}

	public Long getId() {
		return id;
	}

	public Long getUserId() {
		return userId;
	}

	public AiFeature getFeature() {
		return feature;
	}

	public String getModel() {
		return model;
	}

	public Long getInputTokens() {
		return inputTokens;
	}

	public Long getOutputTokens() {
		return outputTokens;
	}

	public Long getReasoningTokens() {
		return reasoningTokens;
	}

	public boolean isSuccess() {
		return success;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
