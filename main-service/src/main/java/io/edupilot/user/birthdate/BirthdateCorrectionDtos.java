package io.edupilot.user.birthdate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public final class BirthdateCorrectionDtos {
	private BirthdateCorrectionDtos() { }

	public record Submit(
		@NotNull(message = "수정 요청할 생년월일이 필요합니다.")
		@Schema(type = "string", format = "date", description = "KST 오늘 이하의 수정 요청 날짜. 접수만으로 가입 생년월일이 바뀌지 않습니다.")
		LocalDate requestedDateOfBirth
	) {
		@Override public String toString() { return "BirthdateCorrectionSubmit[REDACTED]"; }
	}

	public record Request(Long id, Long userId, LocalDate requestedDateOfBirth,
		BirthdateCorrectionRequest.State state, Instant requestedAt) {
		public static Request from(BirthdateCorrectionRequest row) {
			return new Request(row.getId(), row.getUserId(), row.getRequestedDateOfBirth(), row.getState(), row.getRequestedAt());
		}
		@Override public String toString() { return "BirthdateCorrectionResponse[REDACTED]"; }
	}

	public record ListResponse(List<Request> requests, int page, int size, long totalElements, int totalPages) { }
}
