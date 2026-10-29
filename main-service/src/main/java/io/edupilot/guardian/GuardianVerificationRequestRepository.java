package io.edupilot.guardian;

import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface GuardianVerificationRequestRepository extends JpaRepository<GuardianVerificationRequest,String> {
 Optional<GuardianVerificationRequest> findByUser_Id(Long userId);
 @Modifying(flushAutomatically=true)
 @Query("update GuardianVerificationRequest request set request.status=:cancelled where request.user.id=:userId and request.status=:pending")
 int cancelPending(@Param("userId") Long userId,@Param("pending") GuardianVerificationRequest.Status pending,
  @Param("cancelled") GuardianVerificationRequest.Status cancelled);
}
