package io.edupilot.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class AssessmentQueryServiceTest {

	@Mock
	private QuizAssessmentRepository assessmentRepository;

	@Test
	void usesSessionFiveAndCrossSessionTwentyWindows() {
		when(assessmentRepository
			.findTop5BySession_IdOrderByCreatedAtDescIdDesc(100L))
			.thenReturn(List.of());
		when(assessmentRepository.findRecentByUserAndMaterial(
			org.mockito.ArgumentMatchers.eq(1L),
			org.mockito.ArgumentMatchers.eq(10L),
			org.mockito.ArgumentMatchers.any()
		)).thenReturn(List.of());
		AssessmentQueryService service = new AssessmentQueryService(
			assessmentRepository
		);

		service.recentForSession(100L);
		service.recentForPromotion(1L, 10L);

		verify(assessmentRepository)
			.findTop5BySession_IdOrderByCreatedAtDescIdDesc(100L);
		ArgumentCaptor<Pageable> pageable =
			ArgumentCaptor.forClass(Pageable.class);
		verify(assessmentRepository).findRecentByUserAndMaterial(
			org.mockito.ArgumentMatchers.eq(1L),
			org.mockito.ArgumentMatchers.eq(10L),
			pageable.capture()
		);
		assertThat(pageable.getValue().getPageSize()).isEqualTo(20);
		assertThat(service.sessionWindow()).isEqualTo(5);
	}
}
