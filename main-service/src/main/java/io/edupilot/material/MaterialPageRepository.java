package io.edupilot.material;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MaterialPageRepository extends JpaRepository<MaterialPage, Long> {

	Optional<MaterialPage> findByMaterial_IdAndPageNumber(Long materialId, int pageNumber);

	List<MaterialPage> findByMaterial_IdAndPageNumberBetweenOrderByPageNumberAsc(
		Long materialId,
		int startPage,
		int endPage
	);
}
