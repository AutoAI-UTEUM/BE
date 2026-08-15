package io.edupilot.material;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MaterialOverviewRepository
	extends JpaRepository<MaterialOverview, Long> {

	Optional<MaterialOverview> findByMaterial_Id(Long materialId);
}
