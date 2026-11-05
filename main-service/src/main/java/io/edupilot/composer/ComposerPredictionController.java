package io.edupilot.composer;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.edupilot.auth.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** AI-v1-named response projection with an application-owned saved-message selector; default OFF. */
@Hidden
@RestController
@ConditionalOnProperty(name = "edupilot.composer.enabled", havingValue = "true")
public class ComposerPredictionController {
	private final ComposerPredictionService predictions;
	private final RequestAttributeSecurityContextRepository requestContexts = new RequestAttributeSecurityContextRepository();
	public ComposerPredictionController(ComposerPredictionService predictions) { this.predictions = predictions; }

	@PostMapping(value = "/api/sessions/{sessionId}/composer-prediction", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public ResponseEntity<SseEmitter> predict(@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable Long sessionId, @Valid @RequestBody Request request,
		HttpServletRequest servletRequest, HttpServletResponse servletResponse) {
		SseEmitter emitter = predictions.open(user.userId(), sessionId, request.sourceMessageId());
		// The JWT filter is once-per-request and does not authenticate ASYNC redispatch.
		// Keep only this already-validated request's context, never an HTTP session or a
		// dispatcher-wide permitAll. Ongoing account/ownership checks remain in the stream.
		requestContexts.saveContext(SecurityContextHolder.getContext(), servletRequest, servletResponse);
		return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM)
			.header(HttpHeaders.CACHE_CONTROL, "no-store").header("X-Accel-Buffering", "no")
			.body(emitter);
	}
	public record Request(@NotNull @Positive Long sourceMessageId) { }
}
