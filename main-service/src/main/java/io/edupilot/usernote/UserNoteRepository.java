package io.edupilot.usernote;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserNoteRepository extends JpaRepository<UserNote, Long> {

	Page<UserNote> findByUser_IdAndDeletedAtIsNull(Long userId, Pageable pageable);

	Page<UserNote> findByUser_IdAndMaterial_IdAndDeletedAtIsNull(
		Long userId, Long materialId, Pageable pageable
	);

	Optional<UserNote> findByIdAndUser_IdAndDeletedAtIsNull(Long id, Long userId);

	Optional<UserNote> findByUser_IdAndClientId(Long userId, String clientId);

	long countByUser_IdAndDeletedAtIsNull(Long userId);
}
