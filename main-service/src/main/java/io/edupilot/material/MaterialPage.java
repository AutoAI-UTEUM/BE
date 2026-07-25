package io.edupilot.material;

import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
	name = "material_pages",
	uniqueConstraints = @UniqueConstraint(
		name = "uk_material_pages_material_page",
		columnNames = {"material_id", "page_number"}
	)
)
public class MaterialPage {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "material_id", nullable = false)
	private LearningMaterial material;

	@Column(name = "page_number", nullable = false)
	private int pageNumber;

	@Column(name = "text_content", nullable = false, columnDefinition = "MEDIUMTEXT")
	private String textContent;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected MaterialPage() {
	}

	private MaterialPage(LearningMaterial material, int pageNumber, String textContent) {
		this.material = material;
		this.pageNumber = pageNumber;
		this.textContent = textContent;
	}

	public static MaterialPage create(
		LearningMaterial material,
		int pageNumber,
		String textContent
	) {
		return new MaterialPage(material, pageNumber, textContent);
	}

	public int getPageNumber() {
		return pageNumber;
	}

	public String getTextContent() {
		return textContent;
	}
}
