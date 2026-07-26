package io.edupilot.diagnosis;

import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import io.edupilot.session.LearningSession;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "repair_results")
public class RepairResult {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "diagnosis_id", nullable = false)
	private Diagnosis diagnosis;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "session_id", nullable = false)
	private LearningSession session;

	@Column(name = "repair_content", nullable = false, columnDefinition = "MEDIUMTEXT")
	private String repairContent;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "repair_result_json", nullable = false, columnDefinition = "json")
	private RepairResultData repairResult;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected RepairResult() {
	}

	private RepairResult(
		Diagnosis diagnosis,
		LearningSession session,
		String repairContent,
		RepairResultData repairResult
	) {
		this.diagnosis = diagnosis;
		this.session = session;
		this.repairContent = repairContent;
		this.repairResult = repairResult;
	}

	public static RepairResult create(
		Diagnosis diagnosis,
		LearningSession session,
		String repairContent
	) {
		return new RepairResult(
			diagnosis,
			session,
			repairContent,
			new RepairResultData("1.0", repairContent)
		);
	}
}
