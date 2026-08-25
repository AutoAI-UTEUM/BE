package io.edupilot.material;

import java.util.List;
import java.util.Optional;
import java.util.Collection;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MaterialPageRepository extends JpaRepository<MaterialPage, Long> {

	Optional<MaterialPage> findByMaterial_IdAndPageNumber(Long materialId, int pageNumber);

	@Query(value = """
		SELECT CHAR_LENGTH(mp.text_content)
		FROM material_pages mp
		WHERE mp.material_id = :materialId
		  AND mp.page_number = :pageNumber
		""", nativeQuery = true)
	Optional<Integer> findTextLengthByMaterialIdAndPageNumber(
		@Param("materialId") Long materialId,
		@Param("pageNumber") int pageNumber
	);

	List<MaterialPage> findByMaterial_IdAndPageNumberBetweenOrderByPageNumberAsc(
		Long materialId,
		int startPage,
		int endPage
	);

	List<MaterialPage> findByMaterial_IdOrderByPageNumberAsc(Long materialId);

	@Query("""
		select page.material.id as materialId,
		       page.pageNumber as pageNumber,
		       page.textContent as text,
		       page.caption as caption
		from MaterialPage page
		where page.material.id in :materialIds
		order by page.material.id, page.pageNumber, page.id
		""")
	List<ExamDraftPageText> findExamDraftPages(
		@Param("materialIds") Collection<Long> materialIds
	);

	interface ExamDraftPageText {
		Long getMaterialId();
		Integer getPageNumber();
		String getText();
		String getCaption();
	}
}
