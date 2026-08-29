package io.edupilot.admin;

import java.util.Locale;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.admin.dto.AdminUserDetailResponse;
import io.edupilot.admin.dto.AdminUserListResponse;
import io.edupilot.admin.dto.AdminUserResponse;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;
import io.edupilot.user.UserStatus;

@Service
public class AdminUserService {

	private final UserRepository userRepository;

	public AdminUserService(UserRepository userRepository) {
		this.userRepository = userRepository;
	}

	@Transactional(readOnly = true)
	public AdminUserListResponse list(
		String query,
		UserRole role,
		UserStatus status,
		AdminListSort sort,
		int page,
		int size
	) {
		Page<User> users = userRepository.findAdminUsers(
			normalizedQuery(query),
			role,
			status,
			PageRequest.of(page, size, userSort(sort))
		);
		Page<AdminUserResponse> responses = users.map(AdminUserResponse::from);
		return new AdminUserListResponse(
			responses.getContent(),
			responses.getNumber(),
			responses.getSize(),
			responses.getTotalElements(),
			responses.getTotalPages()
		);
	}

	@Transactional(readOnly = true)
	public AdminUserDetailResponse detail(Long userId) {
		return userRepository.findById(userId)
			.map(AdminUserDetailResponse::from)
			.orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
	}

	private String normalizedQuery(String query) {
		if (query == null || query.isBlank()) {
			return null;
		}
		return query.trim().toLowerCase(Locale.ROOT);
	}

	private Sort userSort(AdminListSort sort) {
		return switch (sort == null ? AdminListSort.RECENT : sort) {
			case RECENT -> Sort.by(
				Sort.Order.desc("createdAt"),
				Sort.Order.desc("id")
			);
			case NAME -> Sort.by(
				Sort.Order.asc("name"),
				Sort.Order.asc("id")
			);
		};
	}
}
