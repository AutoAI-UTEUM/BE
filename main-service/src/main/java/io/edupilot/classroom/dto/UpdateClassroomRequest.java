package io.edupilot.classroom.dto;

import java.time.LocalDate;

import io.edupilot.classroom.ClassroomColor;

public class UpdateClassroomRequest {

	private boolean namePresent;
	private String name;
	private boolean endDatePresent;
	private LocalDate endDate;
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

	public LocalDate getEndDate() {
		return endDate;
	}

	public void setEndDate(LocalDate endDate) {
		this.endDatePresent = true;
		this.endDate = endDate;
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

	public boolean isEndDatePresent() {
		return endDatePresent;
	}

	public boolean isColorPresent() {
		return colorPresent;
	}

	public boolean isDescriptionPresent() {
		return descriptionPresent;
	}

	public boolean hasAnyField() {
		return namePresent || endDatePresent || colorPresent || descriptionPresent;
	}
}
