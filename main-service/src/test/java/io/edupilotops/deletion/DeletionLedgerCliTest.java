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
	@Test void isolatedReviewedV65CatalogIsAcceptedAndNewerMigrationIsRejected(
		@org.junit.jupiter.api.io.TempDir java.nio.file.Path root
	) throws Exception {
		var directory = root.resolve("db/migration");
		java.nio.file.Files.createDirectories(directory);
		Set<Integer> versions = new HashSet<>();
		var resolver = new org.springframework.core.io.support.PathMatchingResourcePatternResolver();
		for (var resource : resolver.getResources("classpath*:db/migration/V*__*.sql")) {
			String name = resource.getFilename();
			assertThat(name).isNotNull();
			int version = Integer.parseInt(name.substring(1, name.indexOf("__")));
			if (version <= 65) {
				assertThat(versions.add(version)).isTrue();
				try (var input = resource.getInputStream()) {
					java.nio.file.Files.copy(input, directory.resolve(name));
				}
			}
		}
		assertThat(versions).containsExactlyInAnyOrderElementsOf(
			java.util.stream.IntStream.rangeClosed(1, 65).boxed().toList());
		ClassLoader original = Thread.currentThread().getContextClassLoader();
		// Parentless resource loader isolates this fixture from the evolving application catalog.
		try (var resources = new java.net.URLClassLoader(new java.net.URL[]{root.toUri().toURL()}, null)) {
			Thread.currentThread().setContextClassLoader(resources);
			assertThat(LedgerDatabase.sourceCatalog()).isEqualTo(LedgerDatabase.V65);
			java.nio.file.Files.writeString(directory.resolve("V66__unreviewed.sql"), "SELECT 1;\n");
			assertThatThrownBy(LedgerDatabase::sourceCatalog).isInstanceOf(IllegalArgumentException.class);
		} finally {
			Thread.currentThread().setContextClassLoader(original);
		}
	}

	@Test void onlyReviewedSourceCatalogAndDifferentWriteDatabaseAreAllowed() throws Exception {
		// The real application classpath now contains V66; the V65 operator must fail closed.
		assertThatThrownBy(LedgerDatabase::sourceCatalog).isInstanceOf(IllegalArgumentException.class);
		String live="12345678-1234-1234-1234-123456789abc",target="12345678-1234-1234-1234-123456789abd";
		assertThatThrownBy(()->new LedgerDatabase("root","synthetic",target,live,false)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(()->new LedgerDatabase("app","synthetic",live,live,false)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(()->new LedgerDatabase("app","synthetic",target,live,true)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(()->new LedgerDatabase("app","synthetic",live,live,true,true)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(()->new LedgerDatabase("app","synthetic",target,live,false).getConnection("other","synthetic"))
			.isInstanceOf(java.sql.SQLException.class).hasMessage("ALTERNATE_CREDENTIALS_REJECTED");
	}
}
