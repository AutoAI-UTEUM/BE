package io.edupilot.classroom.dto;

public class UpdateClassroomNoticeRequest {

	private boolean titlePresent;
	private String title;
	private boolean contentPresent;
	private String content;

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

	public boolean isTitlePresent() {
		return titlePresent;
	}

	public boolean isContentPresent() {
		return contentPresent;
	}

	public boolean hasAnyField() {
		return titlePresent || contentPresent;
	}
}
