package io.edupilot.exam.dto;

import java.util.List;

import jakarta.validation.Valid;

public class UpdateExamRequest {

	private boolean titlePresent;
	private String title;
	private boolean descriptionPresent;
	private String description;
	private boolean weekNumberPresent;
	private Integer weekNumber;
	private boolean allowRetakePresent;
	private Boolean allowRetake;
	private boolean questionsPresent;
	private List<@Valid ExamQuestionRequest> questions;

	public String getTitle() { return title; }
	public void setTitle(String title) { this.titlePresent = true; this.title = title; }
	public String getDescription() { return description; }
	public void setDescription(String description) {
		this.descriptionPresent = true;
		this.description = description;
	}
	public Integer getWeekNumber() { return weekNumber; }
	public void setWeekNumber(Integer weekNumber) {
		this.weekNumberPresent = true;
		this.weekNumber = weekNumber;
	}
	public Boolean getAllowRetake() { return allowRetake; }
	public void setAllowRetake(Boolean allowRetake) {
		this.allowRetakePresent = true;
		this.allowRetake = allowRetake;
	}
	public List<ExamQuestionRequest> getQuestions() { return questions; }
	public void setQuestions(List<ExamQuestionRequest> questions) {
		this.questionsPresent = true;
		this.questions = questions;
	}
	public boolean isTitlePresent() { return titlePresent; }
	public boolean isDescriptionPresent() { return descriptionPresent; }
	public boolean isWeekNumberPresent() { return weekNumberPresent; }
	public boolean isAllowRetakePresent() { return allowRetakePresent; }
	public boolean isQuestionsPresent() { return questionsPresent; }
	public boolean hasAnyField() {
		return titlePresent || descriptionPresent || weekNumberPresent
			|| allowRetakePresent || questionsPresent;
	}
}
