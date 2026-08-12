package io.edupilot.notification;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

	Page<Notification> findByUser_Id(Long userId, Pageable pageable);

	Optional<Notification> findByIdAndUser_Id(Long notificationId, Long userId);
}
