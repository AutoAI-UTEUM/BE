package io.edupilot.classroom.dto;

import java.time.Instant;

public class UpdateClassroomNoticeRequest {

	private boolean titlePresent;
	private String title;
	private boolean contentPresent;
	private String content;
	private boolean weekNumberPresent;
	private Integer weekNumber;
	private boolean publishAtPresent;
	private Instant publishAt;

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.titlePresent = true;
		this.title = title;
	}

	public String getContent() {
		return content;
	}

	public void setContent(String content) {
		this.contentPresent = true;
		this.content = content;
	}

	public Integer getWeekNumber() {
		return weekNumber;
	}

	public void setWeekNumber(Integer weekNumber) {
		this.weekNumberPresent = true;
		this.weekNumber = weekNumber;
	}

	public Instant getPublishAt() {
		return publishAt;
	}

	public void setPublishAt(Instant publishAt) {
		this.publishAtPresent = true;
		this.publishAt = publishAt;
	}

	public boolean isTitlePresent() {
		return titlePresent;
	}

	public boolean isContentPresent() {
		return contentPresent;
	}

	public boolean isWeekNumberPresent() {
		return weekNumberPresent;
	}

	public boolean isPublishAtPresent() {
		return publishAtPresent;
	}

	public boolean hasAnyField() {
		return titlePresent
			|| contentPresent
			|| weekNumberPresent
			|| publishAtPresent;
	}
}
