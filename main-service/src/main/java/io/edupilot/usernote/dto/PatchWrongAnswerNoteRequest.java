package io.edupilot.usernote.dto;

public class PatchWrongAnswerNoteRequest {

	private boolean memoPresent;
	private String memo;

	public String getMemo() { return memo; }
	public void setMemo(String memo) { this.memoPresent = true; this.memo = memo; }
	public boolean isMemoPresent() { return memoPresent; }
}
