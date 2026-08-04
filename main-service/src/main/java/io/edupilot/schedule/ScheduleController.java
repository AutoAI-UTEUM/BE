package io.edupilot.schedule;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.edupilot.auth.AuthenticatedUser;
import io.edupilot.global.response.ApiResponse;
import io.edupilot.schedule.dto.CreatePersonalScheduleRequest;
import io.edupilot.schedule.dto.PersonalScheduleResponse;
import io.edupilot.schedule.dto.ScheduleListResponse;
import io.edupilot.schedule.dto.UpdatePersonalScheduleRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/users/me/schedule")
@Tag(name = "Schedule")
@SecurityRequirement(name = "bearerAuth")
public class ScheduleController {

	private final ScheduleService scheduleService;
	private final PersonalScheduleService personalScheduleService;

	public ScheduleController(
		ScheduleService scheduleService,
		PersonalScheduleService personalScheduleService
	) {
		this.scheduleService = scheduleService;
		this.personalScheduleService = personalScheduleService;
	}

	@GetMapping
	@Operation(summary = "내 강의실 일정 조회")
	public ApiResponse<ScheduleListResponse> list(
		@AuthenticationPrincipal AuthenticatedUser user,
		@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
		@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
		@RequestParam(required = false) Long classroomId
	) {
		return ApiResponse.success(scheduleService.list(
			user.userId(), user.role(), from, to, classroomId
		));
	}

	@PostMapping
	@Operation(summary = "개인 일정 생성")
	public ApiResponse<PersonalScheduleResponse> create(
		@AuthenticationPrincipal AuthenticatedUser user,
		@Valid @RequestBody CreatePersonalScheduleRequest request
	) {
		return ApiResponse.success(personalScheduleService.create(
			user.userId(), request
		));
	}

	@PatchMapping("/{scheduleId}")
	@Operation(summary = "개인 일정 수정")
	public ApiResponse<PersonalScheduleResponse> update(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable Long scheduleId,
		@Valid @RequestBody UpdatePersonalScheduleRequest request
	) {
		return ApiResponse.success(personalScheduleService.update(
			user.userId(), scheduleId, request
		));
	}

	@DeleteMapping("/{scheduleId}")
	@Operation(summary = "개인 일정 삭제")
	public ApiResponse<Void> delete(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable Long scheduleId
	) {
		personalScheduleService.delete(user.userId(), scheduleId);
		return ApiResponse.success(null);
	}
}
