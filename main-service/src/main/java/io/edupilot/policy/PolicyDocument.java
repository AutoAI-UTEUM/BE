package io.edupilot.policy;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "policy_documents")
public class PolicyDocument {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private PolicyType type;

	@Column(nullable = false, length = 20)
	private String version;

	@Column(nullable = false, length = 200)
	private String title;

	@Column(nullable = false, columnDefinition = "MEDIUMTEXT")
	private String content;

	@Column(length = 1000)
	private String summary;

	@Column(name = "effective_at", nullable = false)
	private Instant effectiveAt;

	@Column(name = "created_by", nullable = false)
	private Long createdBy;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected PolicyDocument() {
	}

	private PolicyDocument(
		PolicyType type, String version, String title, String content,
		String summary, Instant effectiveAt, Long createdBy, Instant createdAt
	) {
		this.type = type;
		this.version = version;
		this.title = title;
		this.content = content;
		this.summary = summary;
		this.effectiveAt = effectiveAt;
		this.createdBy = createdBy;
		this.createdAt = createdAt;
	}

	public static PolicyDocument create(
		PolicyType type, String version, String title, String content,
		String summary, Instant effectiveAt, Long createdBy, Instant createdAt
	) {
		return new PolicyDocument(
			type, version, title, content, summary, effectiveAt, createdBy, createdAt
		);
	}

	public Long getId() { return id; }
	public PolicyType getType() { return type; }
	public String getVersion() { return version; }
	public String getTitle() { return title; }
	public String getContent() { return content; }
	public String getSummary() { return summary; }
	public Instant getEffectiveAt() { return effectiveAt; }
	public Long getCreatedBy() { return createdBy; }
	public Instant getCreatedAt() { return createdAt; }
}
