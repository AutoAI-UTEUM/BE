package io.edupilot.material;

import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import io.edupilot.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "learning_materials")
public class LearningMaterial {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "owner_id", nullable = false)
	private User owner;

	@Column(nullable = false, length = 255)
	private String title;

	@Column(name = "storage_key", nullable = false, unique = true, length = 255)
	private String storageKey;

	@Column(name = "page_count")
	private Integer pageCount;

	@Enumerated(EnumType.STRING)
	@Column(name = "processing_status", nullable = false, length = 20)
	private MaterialProcessingStatus processingStatus;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private MaterialStatus status;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected LearningMaterial() {
	}

	private LearningMaterial(User owner, String title, String storageKey) {
		this.owner = owner;
		this.title = title;
		this.storageKey = storageKey;
		this.pageCount = null;
		this.processingStatus = MaterialProcessingStatus.PROCESSING;
		this.status = MaterialStatus.ACTIVE;
	}

	public static LearningMaterial create(User owner, String title, String storageKey) {
		return new LearningMaterial(owner, title, storageKey);
	}

	public void markReady(int pageCount) {
		if (!isActiveAndProcessing()) {
			return;
		}
		this.pageCount = pageCount;
		this.processingStatus = MaterialProcessingStatus.READY;
	}

	public void markFailed() {
		if (!isActiveAndProcessing()) {
			return;
		}
		this.pageCount = null;
		this.processingStatus = MaterialProcessingStatus.FAILED;
	}

	public void delete() {
		this.status = MaterialStatus.DELETED;
	}

	public boolean isActiveAndProcessing() {
		return status == MaterialStatus.ACTIVE
			&& processingStatus == MaterialProcessingStatus.PROCESSING;
	}

	public boolean isActive() {
		return status == MaterialStatus.ACTIVE;
	}

	public boolean isReady() {
		return processingStatus == MaterialProcessingStatus.READY;
	}

	public Long getId() {
		return id;
	}

	public Long getOwnerId() {
		return owner.getId();
	}

	public String getTitle() {
		return title;
	}

	public String getStorageKey() {
		return storageKey;
	}

	public Integer getPageCount() {
		return pageCount;
	}

	public MaterialProcessingStatus getProcessingStatus() {
		return processingStatus;
	}

	public MaterialStatus getStatus() {
		return status;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
