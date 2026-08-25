package io.edupilot.classroom.dto;

import java.time.LocalDate;

import io.edupilot.classroom.ClassroomColor;

public class UpdateClassroomRequest {

	private boolean namePresent;
	private String name;
	private boolean startDatePresent;
	private LocalDate startDate;
	private boolean endDatePresent;
	private LocalDate endDate;
	private boolean shiftWeekReleaseDatesPresent;
	private Boolean shiftWeekReleaseDates;
	private boolean colorPresent;
	private ClassroomColor color;
	private boolean descriptionPresent;
	private String description;

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.namePresent = true;
		this.name = name;
	}

	public LocalDate getStartDate() {
		return startDate;
	}

	public void setStartDate(LocalDate startDate) {
		this.startDatePresent = true;
		this.startDate = startDate;
	}

	public LocalDate getEndDate() {
		return endDate;
	}

	public void setEndDate(LocalDate endDate) {
		this.endDatePresent = true;
		this.endDate = endDate;
	}

	public Boolean getShiftWeekReleaseDates() {
		return shiftWeekReleaseDates;
	}

	public void setShiftWeekReleaseDates(Boolean shiftWeekReleaseDates) {
		this.shiftWeekReleaseDatesPresent = true;
		this.shiftWeekReleaseDates = shiftWeekReleaseDates;
	}

	public ClassroomColor getColor() {
		return color;
	}

	public void setColor(ClassroomColor color) {
		this.colorPresent = true;
		this.color = color;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.descriptionPresent = true;
		this.description = description;
	}

	public boolean isNamePresent() {
		return namePresent;
	}

	public boolean isStartDatePresent() {
		return startDatePresent;
	}

	public boolean isEndDatePresent() {
		return endDatePresent;
	}

	public boolean isShiftWeekReleaseDatesPresent() {
		return shiftWeekReleaseDatesPresent;
	}

	public boolean isColorPresent() {
		return colorPresent;
	}

	public boolean isDescriptionPresent() {
		return descriptionPresent;
	}

	public boolean hasAnyField() {
		return namePresent
			|| startDatePresent
			|| endDatePresent
			|| shiftWeekReleaseDatesPresent
			|| colorPresent
			|| descriptionPresent;
	}
}
