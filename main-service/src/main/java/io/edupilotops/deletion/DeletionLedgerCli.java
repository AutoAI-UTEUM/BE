package io.edupilotops.deletion;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import io.edupilot.deletion.*;
import tools.jackson.databind.ObjectMapper;

/** Supported via scripts/deploy/deletion_ledger.py only. Default invocation performs no DB action. */
public final class DeletionLedgerCli {
	private DeletionLedgerCli() { }
	public static void main(String[] args) {
		// This process handles restricted data: no framework/SQL/provider logs, including error payloads.
		if(org.slf4j.LoggerFactory.getILoggerFactory() instanceof ch.qos.logback.classic.LoggerContext logs) {
			logs.reset(); logs.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).setLevel(ch.qos.logback.classic.Level.OFF);
		}
		try {
			if(args.length!=2 || !Set.of("export","replay","migrate-isolated").contains(args[0]) || !"--execute".equals(args[1])) {
				System.out.println("{\"status\":\"OPERATOR_WRAPPER_REQUIRED_NO_ACTION\"}");return;
			}
			Map<String,String> env=System.getenv();boolean export="export".equals(args[0]),migrate="migrate-isolated".equals(args[0]);
			LedgerCodec.require("true".equals(env.get("LEDGER_WRAPPER_VERIFIED")));
			String sha=env.get("LEDGER_SOURCE_SHA"),live=env.get("LEDGER_LIVE_UUID"),target=env.get("LEDGER_TARGET_UUID");
			LedgerCodec.require(sha!=null && sha.matches("[a-f0-9]{40}"));
			Instant approved=Instant.parse(env.get("LEDGER_REVIEWED_AT"));
			Instant now=Instant.now();LedgerCodec.require(!approved.isAfter(now) && !approved.isBefore(now.minusSeconds(1800)));
			LedgerCodec.Document input=null;
			if(!export && !migrate) {
				byte[] raw=System.in.readNBytes(LedgerCodec.MAX_BYTES+1);
				input=LedgerCodec.decode(raw,env.get("LEDGER_INPUT_SHA256"));
				LedgerCodec.require(input.sourceUuid().equals(live) && input.sourceSha().equals(sha) && Set.of(LedgerDatabase.V64,LedgerDatabase.V65).contains(input.catalogSha256())
					&& !input.exportedAtUtc().isAfter(approved)
					&& !input.exportedAtUtc().isBefore(Instant.parse(env.get("LEDGER_BACKUP_AT"))));
				String epoch=env.get("LEDGER_RESTORE_EPOCH");LedgerCodec.require(epoch!=null && epoch.matches("[A-Za-z0-9_-]{1,100}"));
			}
			var database=new LedgerDatabase(env.get("LEDGER_DB_USER"),env.get("LEDGER_DB_PASSWORD"),target,live,export,migrate);
			if(migrate) {
				// Explicit DDL on the wrapper-verified isolated copy only. No repair/ignore/down migration.
				org.flywaydb.core.Flyway.configure().dataSource(database).locations("classpath:db/migration")
					.baselineOnMigrate(false).outOfOrder(false).cleanDisabled(true).load().migrate();
				try(var connection=database.getConnection()){LedgerCodec.require(LedgerDatabase.history(connection).equals(LedgerDatabase.V65));}
				System.out.println("{\"status\":\"ISOLATED_SCHEMA_V65\"}");return;
			}
			boolean mismatch=false;
			try(var context=LedgerContext.open(database)) {
				if(export) {
					var tx=new TransactionTemplate(context.getBean(PlatformTransactionManager.class));tx.setReadOnly(true);
					tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
					LedgerCodec.Document document=tx.execute(status->{
						List<DeletionSnapshot> entries=exportAll(context.getBean(DeletionJournal.class));
						var connection=org.springframework.jdbc.datasource.DataSourceUtils.getConnection(database);
						try{return new LedgerCodec.Document(sha,live,LedgerDatabase.history(connection),Instant.now().truncatedTo(ChronoUnit.MICROS),entries);}
						catch(java.sql.SQLException error){throw new IllegalStateException("EXPORT_SNAPSHOT_REJECTED");}
						finally{org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(connection,database);}
					});
					byte[] raw=LedgerCodec.encode(document);
					System.out.write(raw);System.out.flush();
				} else {
					var counts=replay(context.getBean(DeletionRestoreService.class),input.snapshots(),env.get("LEDGER_RESTORE_EPOCH"));
					System.out.println(new ObjectMapper().writeValueAsString(Map.of("status",counts.getOrDefault(DeletionReplay.Result.IDENTITY_MISMATCH,0)>0?"IDENTITY_MISMATCH_HOLD":"ISOLATED_LOGICAL_REPLAY_COMPLETE","results",counts,"physicalDeletionExecuted",false)));
					mismatch=counts.getOrDefault(DeletionReplay.Result.IDENTITY_MISMATCH,0)>0;
				}
			}
			if(mismatch)System.exit(3);
		} catch(Throwable error) {
			System.out.println("{\"status\":\"OPERATOR_LEDGER_FAILED_NO_RETRY_OR_REOPEN\"}");System.exit(2);
		}
	}
	static List<DeletionSnapshot> exportAll(DeletionJournal journal) {
		List<DeletionSnapshot> result=new ArrayList<>();long cursor=0;
		while(true){var page=journal.exportPage(cursor,1000);result.addAll(page.entries());LedgerCodec.require(result.size()<=LedgerCodec.MAX_ENTRIES);
			if(!page.hasNext())break;LedgerCodec.require(page.nextId()>cursor);cursor=page.nextId();}
		return List.copyOf(result);
	}
	static Map<DeletionReplay.Result,Integer> replay(DeletionRestoreService service,List<DeletionSnapshot> rows,String epoch) {
		Map<DeletionReplay.Result,Integer> counts=new EnumMap<>(DeletionReplay.Result.class);
		for(int offset=0;offset<rows.size();offset+=1000)for(var result:service.restoreBatch(rows.subList(offset,Math.min(rows.size(),offset+1000)),epoch))counts.merge(result,1,Integer::sum);
		return counts;
	}
}
