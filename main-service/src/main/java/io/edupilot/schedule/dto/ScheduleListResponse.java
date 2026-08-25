package io.edupilot.schedule.dto;

import java.util.List;

public record ScheduleListResponse(List<ScheduleItemResponse> items) {
}
