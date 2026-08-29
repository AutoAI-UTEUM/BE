package io.edupilot.aiusage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.edupilot.ai.dto.AiUsage;

@ExtendWith(MockitoExtension.class)
class AiUsageServiceTest {

	@Mock
	private AiUsageLogRepository repository;

	@Test
	void recordsUsageTokensAndModel() {
		AiUsageService service = new AiUsageService(repository);

		service.record(
			1L,
			AiFeature.TURN,
			new AiUsage("grok-4", 10L, 20L, 3L),
			true
		);

		ArgumentCaptor<AiUsageLog> captor = ArgumentCaptor.forClass(
			AiUsageLog.class
		);
		verify(repository).saveAndFlush(captor.capture());
		AiUsageLog log = captor.getValue();
		assertThat(log.getUserId()).isEqualTo(1L);
		assertThat(log.getFeature()).isEqualTo(AiFeature.TURN);
		assertThat(log.getModel()).isEqualTo("grok-4");
		assertThat(log.getInputTokens()).isEqualTo(10L);
		assertThat(log.getOutputTokens()).isEqualTo(20L);
		assertThat(log.getReasoningTokens()).isEqualTo(3L);
		assertThat(log.isSuccess()).isTrue();
	}

	@Test
	void recordsNullUsageForEndpointsWithoutUsagePropagation() {
		AiUsageService service = new AiUsageService(repository);

		service.record(2L, AiFeature.EXTRACT, null, false);

		ArgumentCaptor<AiUsageLog> captor = ArgumentCaptor.forClass(
			AiUsageLog.class
		);
		verify(repository).saveAndFlush(captor.capture());
		AiUsageLog log = captor.getValue();
		assertThat(log.getModel()).isNull();
		assertThat(log.getInputTokens()).isNull();
		assertThat(log.getOutputTokens()).isNull();
		assertThat(log.getReasoningTokens()).isNull();
		assertThat(log.isSuccess()).isFalse();
	}

	@Test
	void swallowsRepositoryFailure() {
		AiUsageService service = new AiUsageService(repository);
		when(repository.saveAndFlush(any()))
			.thenThrow(new IllegalStateException("database unavailable"));

		assertThatCode(() -> service.record(
			1L,
			AiFeature.DOC_CHAT,
			null,
			true
		)).doesNotThrowAnyException();
	}
}
