package io.edupilot.usernote.dto;

public class PatchUserNoteRequest {

	private boolean titlePresent;
	private String title;
	private boolean contentPresent;
	private String content;
	private boolean pageNumberPresent;
	private Integer pageNumber;

	public String getTitle() { return title; }
	public void setTitle(String title) { this.titlePresent = true; this.title = title; }
	public String getContent() { return content; }
	public void setContent(String content) { this.contentPresent = true; this.content = content; }
	public Integer getPageNumber() { return pageNumber; }
	public void setPageNumber(Integer pageNumber) {
		this.pageNumberPresent = true;
		this.pageNumber = pageNumber;
	}
	public boolean isTitlePresent() { return titlePresent; }
	public boolean isContentPresent() { return contentPresent; }
	public boolean isPageNumberPresent() { return pageNumberPresent; }
	public boolean hasAnyField() {
		return titlePresent || contentPresent || pageNumberPresent;
	}
}
