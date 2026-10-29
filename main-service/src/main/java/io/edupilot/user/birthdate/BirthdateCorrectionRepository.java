package io.edupilot.user.birthdate;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BirthdateCorrectionRepository extends JpaRepository<BirthdateCorrectionRequest, Long> {
	Optional<BirthdateCorrectionRequest> findByUser_Id(Long userId);
	Page<BirthdateCorrectionRequest> findByState(BirthdateCorrectionRequest.State state, Pageable pageable);

	@Modifying
	@Query("""
		update BirthdateCorrectionRequest request
		set request.requestedDateOfBirth = null, request.state = :withdrawn
		where request.user.id = :userId
		""")
	int eraseOnWithdrawal(@Param("userId") Long userId, @Param("withdrawn") BirthdateCorrectionRequest.State withdrawn);
}
