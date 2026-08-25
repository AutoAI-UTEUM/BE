package io.edupilot.classroom.dto;

import java.time.Instant;

public class UpdateClassroomWeekRequest {

	private boolean titlePresent;
	private String title;
	private boolean releaseAtPresent;
	private Instant releaseAt;

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.titlePresent = true;
		this.title = title;
	}

	public Instant getReleaseAt() {
		return releaseAt;
	}

	public void setReleaseAt(Instant releaseAt) {
		this.releaseAtPresent = true;
		this.releaseAt = releaseAt;
	}

	public boolean isTitlePresent() {
		return titlePresent;
	}

	public boolean isReleaseAtPresent() {
		return releaseAtPresent;
	}

	public boolean hasAnyField() {
		return titlePresent || releaseAtPresent;
	}
}
