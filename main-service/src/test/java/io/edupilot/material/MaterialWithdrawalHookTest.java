package io.edupilot.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import io.edupilot.user.User;
import io.edupilot.deletion.DeletionJournal;

class MaterialWithdrawalHookTest {
	@Test
	void withdrawalRecordsEachOwnedMaterialBeforeFlushing() {
		var repository=mock(LearningMaterialRepository.class);
		var journal=mock(DeletionJournal.class);
		var owner=User.create("synthetic@example.com","hash","Synthetic");
		var first=LearningMaterial.create(owner,"First","materials/one.pdf");
		var second=LearningMaterial.create(owner,"Second","materials/two.pdf");
		when(repository.findOwnedActiveForUpdate(7L)).thenReturn(List.of(first,second));
		new MaterialWithdrawalHook(repository,journal).onWithdraw(7L);
		assertThat(first.isActive()).isFalse(); assertThat(second.isActive()).isFalse();
		var order=inOrder(journal,repository);
		order.verify(journal).recordMaterial(first); order.verify(journal).recordMaterial(second);
		order.verify(repository).flush();
	}
}