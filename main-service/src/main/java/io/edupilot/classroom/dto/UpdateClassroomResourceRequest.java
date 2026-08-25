package io.edupilot.classroom.dto;

public class UpdateClassroomResourceRequest {

	private boolean titlePresent;
	private String title;
	private boolean weekNumberPresent;
	private Integer weekNumber;

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.titlePresent = true;
		this.title = title;
	}

	public Integer getWeekNumber() {
		return weekNumber;
	}

	public void setWeekNumber(Integer weekNumber) {
		this.weekNumberPresent = true;
		this.weekNumber = weekNumber;
	}

	public boolean isTitlePresent() {
		return titlePresent;
	}

	public boolean isWeekNumberPresent() {
		return weekNumberPresent;
	}

	public boolean hasAnyField() {
		return titlePresent || weekNumberPresent;
	}
}
