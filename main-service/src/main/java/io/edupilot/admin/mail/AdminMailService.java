package io.edupilot.admin.mail;

import java.time.Instant;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.mail.EmailDelivery;
import io.edupilot.mail.EmailDeliveryRepository;
import io.edupilot.mail.EmailDeliveryStatus;

@Service
public class AdminMailService {

	private final EmailDeliveryRepository repository;

	public AdminMailService(EmailDeliveryRepository repository) {
		this.repository = repository;
	}

	@Transactional(readOnly = true)
	public AdminMailDeliveryListResponse list(
		Instant from,
		Instant to,
		EmailDeliveryStatus status,
		int page,
		int size
	) {
		Page<EmailDelivery> deliveries = repository.findForAdmin(
			from, to, status,
			PageRequest.of(page, size, Sort.by(
				Sort.Order.desc("createdAt"),
				Sort.Order.desc("id")
			))
		);
		return new AdminMailDeliveryListResponse(
			deliveries.map(AdminMailDeliveryResponse::from).getContent(),
			page,
			size,
			deliveries.getTotalElements(),
			deliveries.getTotalPages()
		);
	}
}
