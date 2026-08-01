package io.edupilot.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.LearningMaterial;
import io.edupilot.material.LearningMaterialRepository;

@ExtendWith(MockitoExtension.class)
class LearningProgressServiceTest {

	@Mock
	private LearningSessionRepository sessionRepository;
	@Mock
	private LearningMaterialRepository materialRepository;
	@Mock
	private SessionPageRecordRepository pageRecordRepository;

	@Test
	void roundsThreeExplainedPagesOfNinetySixToThreePercent() {
		LearningSession session = org.mockito.Mockito.mock(
			LearningSession.class
		);
		when(session.getStatus()).thenReturn(SessionStatus.ACTIVE);
		when(session.getMaterialPageCount()).thenReturn(96);
		when(sessionRepository.findByIdAndUser_Id(100L, 1L))
			.thenReturn(Optional.of(session));
		when(pageRecordRepository.countBySessionId(100L)).thenReturn(3L);

		assertThat(service().calculateSessionProgressRate(1L, 100L))
			.isEqualTo(3);
	}

	@Test
	void returnsZeroWhenSessionHasNoExplanationHistory() {
		LearningSession session = org.mockito.Mockito.mock(
			LearningSession.class
		);
		when(session.getStatus()).thenReturn(SessionStatus.COMPLETED);
		when(session.getMaterialPageCount()).thenReturn(96);
		when(sessionRepository.findByIdAndUser_Id(100L, 1L))
			.thenReturn(Optional.of(session));

		assertThat(service().calculateSessionProgressRate(1L, 100L))
			.isZero();
	}

	@Test
	void calculatesMaterialRateFromDistinctPageCount() {
		LearningMaterial material = org.mockito.Mockito.mock(
			LearningMaterial.class
		);
		when(material.getPageCount()).thenReturn(10);
		when(materialRepository.findById(20L))
			.thenReturn(Optional.of(material));
		when(pageRecordRepository.countDistinctByUserIdAndMaterialId(
			1L,
			20L
		)).thenReturn(4L);

		assertThat(service().calculateMaterialProgressRate(1L, 20L))
			.isEqualTo(40);
	}

	@Test
	void rejectsDeletedSession() {
		LearningSession session = org.mockito.Mockito.mock(
			LearningSession.class
		);
		when(session.getStatus()).thenReturn(SessionStatus.DELETED);
		when(sessionRepository.findByIdAndUser_Id(100L, 1L))
			.thenReturn(Optional.of(session));

		assertThatThrownBy(() ->
			service().calculateSessionProgressRate(1L, 100L))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.SESSION_NOT_FOUND)
			);
		verify(pageRecordRepository, never()).countBySessionId(100L);
	}

	@Test
	void rejectsMissingMaterial() {
		when(materialRepository.findById(20L)).thenReturn(Optional.empty());

		assertThatThrownBy(() ->
			service().calculateMaterialProgressRate(1L, 20L))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.MATERIAL_NOT_FOUND)
			);
		verify(pageRecordRepository, never())
			.countDistinctByUserIdAndMaterialId(1L, 20L);
	}

	private LearningProgressService service() {
		return new LearningProgressService(
			sessionRepository,
			materialRepository,
			pageRecordRepository
		);
	}
}
