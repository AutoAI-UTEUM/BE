package io.edupilotops.deletion;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import io.edupilot.deletion.*;

class DeletionLedgerCliTest {
	private DeletionSnapshot item(int n) {
		return new DeletionSnapshot(DeletionKind.EXTERNAL_AI,"synthetic-"+n,null,null,null,null,Instant.parse("2026-11-05T10:00:00Z"),null);
	}
	@Test void exportFollowsRealCursorAndDoesNotTruncateAtOneThousand() {
		var journal=mock(DeletionJournal.class);
		var first=java.util.stream.IntStream.range(0,1000).mapToObj(this::item).toList();
		when(journal.exportPage(0,1000)).thenReturn(new DeletionJournal.ExportPage(first,1200,true));
		when(journal.exportPage(1200,1000)).thenReturn(new DeletionJournal.ExportPage(List.of(item(1000)),1201,false));
		assertThat(DeletionLedgerCli.exportAll(journal)).hasSize(1001);
		verify(journal).exportPage(1200,1000);
	}
	@Test void stalledCursorAndTooLargeExportFailClosed() {
		var journal=mock(DeletionJournal.class);
		when(journal.exportPage(0,1000)).thenReturn(new DeletionJournal.ExportPage(List.of(),0,true));
		assertThatThrownBy(()->DeletionLedgerCli.exportAll(journal)).isInstanceOf(IllegalArgumentException.class);
		when(journal.exportPage(0,1000)).thenReturn(new DeletionJournal.ExportPage(Collections.nCopies(LedgerCodec.MAX_ENTRIES+1,item(1)),1001,false));
		assertThatThrownBy(()->DeletionLedgerCli.exportAll(journal)).isInstanceOf(IllegalArgumentException.class);
	}
	@Test void replayBatchesAndRetainsIdentityMismatchInsteadOfReportingSuccess() {
		var restore=mock(DeletionRestoreService.class);
		var items=java.util.stream.IntStream.range(0,1001).mapToObj(this::item).toList();
		when(restore.restoreBatch(items.subList(0,1000),"synthetic_epoch")).thenReturn(Collections.nCopies(1000,DeletionReplay.Result.APPLIED));
		when(restore.restoreBatch(items.subList(1000,1001),"synthetic_epoch")).thenReturn(List.of(DeletionReplay.Result.IDENTITY_MISMATCH));
		assertThat(DeletionLedgerCli.replay(restore,items,"synthetic_epoch"))
			.containsEntry(DeletionReplay.Result.APPLIED,1000).containsEntry(DeletionReplay.Result.IDENTITY_MISMATCH,1);
		verify(restore,times(2)).restoreBatch(anyList(),eq("synthetic_epoch"));
	}
	@Test void onlyReviewedSourceCatalogAndDifferentWriteDatabaseAreAllowed() throws Exception {
		assertThat(LedgerDatabase.sourceCatalog()).isEqualTo(LedgerDatabase.V65);
		String live="12345678-1234-1234-1234-123456789abc",target="12345678-1234-1234-1234-123456789abd";
		assertThatThrownBy(()->new LedgerDatabase("root","synthetic",target,live,false)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(()->new LedgerDatabase("app","synthetic",live,live,false)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(()->new LedgerDatabase("app","synthetic",target,live,true)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(()->new LedgerDatabase("app","synthetic",live,live,true,true)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(()->new LedgerDatabase("app","synthetic",target,live,false).getConnection("other","synthetic"))
			.isInstanceOf(java.sql.SQLException.class).hasMessage("ALTERNATE_CREDENTIALS_REJECTED");
	}
}
