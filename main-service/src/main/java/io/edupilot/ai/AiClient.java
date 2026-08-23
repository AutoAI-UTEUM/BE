package io.edupilot.ai;

import java.time.Duration;
import java.util.function.Consumer;

import io.edupilot.ai.dto.AiHealthResponse;
import io.edupilot.ai.dto.CaptionsRequest;
import io.edupilot.ai.dto.CaptionsResponse;
import io.edupilot.ai.dto.CriteriaSuggestRequest;
import io.edupilot.ai.dto.CriteriaSuggestResponse;
import io.edupilot.ai.dto.DiagnosisRequest;
import io.edupilot.ai.dto.DiagnosisResponse;
import io.edupilot.ai.dto.DocChatRequest;
import io.edupilot.ai.dto.DocChatResponse;
import io.edupilot.ai.dto.ExtractResponse;
import io.edupilot.ai.dto.ExamDraftRequest;
import io.edupilot.ai.dto.ExamDraftResponse;
import io.edupilot.ai.dto.GradeRequest;
import io.edupilot.ai.dto.GradeResponse;
import io.edupilot.ai.dto.OutlineRequest;
import io.edupilot.ai.dto.OutlineResponse;
import io.edupilot.ai.dto.QuizAssessmentRequest;
import io.edupilot.ai.dto.QuizAssessmentResponse;
import io.edupilot.ai.dto.ReportGenerateRequest;
import io.edupilot.ai.dto.ReportGenerateResponse;
import io.edupilot.ai.dto.TurnRequest;
import io.edupilot.ai.dto.TurnResponse;
import org.springframework.core.io.Resource;

public interface AiClient {

	AiHealthResponse health();

	TurnResponse executeTurn(TurnRequest request);

	TurnResponse executeTurnStream(
		TurnRequest request,
		Consumer<TurnStreamEvent> listener,
		AiStreamCancellation cancellation,
		Duration totalTimeout
	);

	ExtractResponse extract(Resource pdfResource);

	OutlineResponse outline(OutlineRequest request);

	CaptionsResponse captions(CaptionsRequest request);

	DocChatResponse docChat(DocChatRequest request);

	CriteriaSuggestResponse suggestCriteria(CriteriaSuggestRequest request);

	GradeResponse grade(GradeRequest request);

	QuizAssessmentResponse quizAssessment(QuizAssessmentRequest request);

	DiagnosisResponse diagnosis(DiagnosisRequest request);

	ReportGenerateResponse generateReport(ReportGenerateRequest request);

	ExamDraftResponse generateExamDraft(ExamDraftRequest request);
}
