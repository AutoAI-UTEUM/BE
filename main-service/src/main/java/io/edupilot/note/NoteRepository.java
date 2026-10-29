package io.edupilot.note;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import jakarta.persistence.LockModeType;

import io.edupilot.material.MaterialStatus;

public interface NoteRepository extends JpaRepository<Note, Long> {

	Page<Note> findByUser_IdAndMaterial_IdAndMaterial_Status(
		Long userId,
		Long materialId,
		MaterialStatus materialStatus,
		Pageable pageable
	);

	Optional<Note> findByIdAndUser_Id(Long id, Long userId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<Note> findFirstByUser_IdAndSourceMessage_IdOrderByIdAsc(Long userId, Long sourceMessageId);
}
