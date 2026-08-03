package io.edupilot.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class ReportGenerationServiceTest {

	@Mock private ReportGenerationRepository generationRepository;
	@Mock private ReportSnapshotBuilder snapshotBuilder;
	@Mock private ReportGenerationPersistenceService persistenceService;

	private ReportGenerationService service;

	@BeforeEach
	void setUp() {
		service = new ReportGenerationService(
			generationRepository,
			snapshotBuilder,
			persistenceService
		);
	}

	@Test
	void sameRequestIdReturnsExistingGenerationWithoutCreating() {
		ReportGeneration existing = org.mockito.Mockito.mock(ReportGeneration.class);
		when(existing.getId()).thenReturn(11L);
		when(existing.getStatus()).thenReturn(ReportGenerationStatus.COMPLETED);
		when(generationRepository.findByClassroom_IdAndStudent_IdAndRequestId(
			2L, 3L, "request-1"
		)).thenReturn(Optional.of(existing));

		ReportGenerationService.RequestResult result = service.request(
			1L, 2L, 3L, ReportScope.full(), "request-1"
		);

		assertThat(result.generationId()).isEqualTo(11L);
		assertThat(result.status()).isEqualTo(ReportGenerationStatus.COMPLETED);
		assertThat(result.created()).isFalse();
		verify(persistenceService, never()).create(
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.any()
		);
	}

	@Test
	void activeSameScopeReturnsExistingGeneration() {
		ReportScope scope = ReportScope.week(2);
		String scopeHash = ReportGenerationService.scopeHash(scope);
		ReportGeneration existing = org.mockito.Mockito.mock(ReportGeneration.class);
		when(existing.getId()).thenReturn(12L);
		when(existing.getStatus()).thenReturn(ReportGenerationStatus.PROCESSING);
		when(generationRepository.findByClassroom_IdAndStudent_IdAndRequestId(
			2L, 3L, "request-2"
		)).thenReturn(Optional.empty());
		when(generationRepository
			.findFirstByClassroom_IdAndStudent_IdAndScopeHashAndStatusInOrderByCreatedAtAsc(
				2L,
				3L,
				scopeHash,
				List.of(ReportGenerationStatus.PENDING, ReportGenerationStatus.PROCESSING)
			)).thenReturn(Optional.of(existing));

		ReportGenerationService.RequestResult result = service.request(
			1L, 2L, 3L, scope, "request-2"
		);

		assertThat(result.generationId()).isEqualTo(12L);
		assertThat(result.created()).isFalse();
		verify(persistenceService, never()).create(
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.any()
		);
	}

	@Test
	void uniqueRaceReloadsWinningGeneration() {
		ReportScope scope = ReportScope.full();
		String scopeHash = ReportGenerationService.scopeHash(scope);
		ReportGeneration winner = org.mockito.Mockito.mock(ReportGeneration.class);
		when(winner.getId()).thenReturn(13L);
		when(winner.getStatus()).thenReturn(ReportGenerationStatus.PENDING);
		when(generationRepository.findByClassroom_IdAndStudent_IdAndRequestId(
			2L, 3L, "request-3"
		)).thenReturn(Optional.empty(), Optional.of(winner));
		when(generationRepository
			.findFirstByClassroom_IdAndStudent_IdAndScopeHashAndStatusInOrderByCreatedAtAsc(
				2L,
				3L,
				scopeHash,
				List.of(ReportGenerationStatus.PENDING, ReportGenerationStatus.PROCESSING)
			)).thenReturn(Optional.empty());
		when(persistenceService.create(
			1L, 2L, 3L, scope, "request-3", scopeHash
		)).thenThrow(new DataIntegrityViolationException("duplicate"));

		ReportGenerationService.RequestResult result = service.request(
			1L, 2L, 3L, scope, "request-3"
		);

		assertThat(result.generationId()).isEqualTo(13L);
		assertThat(result.created()).isFalse();
	}
}
