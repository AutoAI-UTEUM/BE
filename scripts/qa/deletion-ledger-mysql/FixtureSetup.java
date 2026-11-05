package io.edupilotops.synthetic;

import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import io.edupilot.auth.*;
import io.edupilot.deletion.*;
import io.edupilot.material.*;
import io.edupilot.user.*;
import io.edupilotops.deletion.*;

/** Test fixture outside the application/JAR source set. Only new, owned CI containers may invoke it. */
public final class FixtureSetup {
    static final String EMAIL="ledger-synthetic@example.invalid";
    static final String REPLACEMENT="ledger-replacement@example.invalid";
    static final String KEY="00000000-0000-4000-8000-000000000001.pdf";
    static void need(boolean value) { if(!value)throw new IllegalStateException("SYNTHETIC_ASSERTION_FAILED"); }
    public static void main(String[] args) {
        if(org.slf4j.LoggerFactory.getILoggerFactory() instanceof ch.qos.logback.classic.LoggerContext logs) {
            logs.reset();logs.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).setLevel(ch.qos.logback.classic.Level.OFF);
        }
        try {
            need(args.length==1 && "true".equals(System.getenv("SYNTHETIC_LEDGER_TEST")));
            String user=System.getenv("LEDGER_DB_USER"),password=System.getenv("LEDGER_DB_PASSWORD"),uuid=System.getenv("SYNTHETIC_EXPECTED_UUID");
            need("ledger_synthetic".equals(user) && password!=null && !password.isBlank() && uuid!=null && uuid.matches("[a-f0-9-]{36}"));
            var data=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:3306/edupilot?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&connectTimeout=5000&socketTimeout=30000",user,password);
            try(var c=data.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT @@server_uuid,VERSION(),DATABASE()")) {
                need(r.next() && uuid.equals(r.getString(1)) && "8.0.46".equals(r.getString(2)) && "edupilot".equals(r.getString(3)));
            }
            var jdbc=new JdbcTemplate(data);
            if(args[0].equals("bootstrap64")) {
                need(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Long.class)==0);
                Flyway.configure().dataSource(data).locations("classpath:db/migration").target("64")
                    .baselineOnMigrate(false).outOfOrder(false).cleanDisabled(true).load().migrate();
                try(var c=data.getConnection()){need(LedgerDatabase.history(c).equals(LedgerDatabase.V64));}
                emit(Map.of("status","SYNTHETIC_V64_READY"));return;
            }
            if(args[0].equals("readonly-probe")) {
                var guard=new LedgerDatabase(user,password,uuid,uuid,true);
                try(var c=guard.getConnection();var s=c.createStatement()) {
                    try(var r=s.executeQuery("SELECT @@session.transaction_read_only")){need(r.next()&&r.getInt(1)==1);}
                    boolean blocked=false;try{s.executeUpdate("UPDATE deletion_journal_locks SET id=id WHERE id=1");}catch(SQLException expected){blocked=true;}
                    need(blocked);
                }
                emit(Map.of("status","SYNTHETIC_EXPORT_SESSION_READ_ONLY"));return;
            }
            if(args[0].equals("snapshot")) { emit(snapshot(jdbc,data));return; }
            if(args[0].equals("prepare-mismatch")) {
                owned(jdbc);need(jdbc.update("UPDATE users SET status='ACTIVE',email=?,name='Synthetic replacement' WHERE id=?",REPLACEMENT,id())==1);
                emit(Map.of("status","SYNTHETIC_IDENTITY_CHANGED"));return;
            }
            if(args[0].equals("verify-held")) {
                owned(jdbc);need(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id=? AND status='ACTIVE' AND email=?",Long.class,id(),REPLACEMENT)==1);
                emit(Map.of("status","SYNTHETIC_IDENTITY_PRESERVED"));return;
            }
            try(var context=LedgerContext.open(data)) {
                need(context.getBeansOfType(UserWithdrawalHook.class).size()==7);
                need(context.getBeansOfType(io.edupilot.ai.AiClient.class).isEmpty());
                need(context.getBeansOfType(io.edupilot.mail.EmailService.class).isEmpty());
                need(context.getBeansOfType(DeletionWorker.class).isEmpty());
                need(context.getBeansOfType(org.springframework.scheduling.TaskScheduler.class).isEmpty());
                var tx=new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
                var users=context.getBean(UserRepository.class);var materials=context.getBean(LearningMaterialRepository.class);
                var journal=context.getBean(DeletionJournal.class);
                switch(args[0]) {
                    case "seed" -> {
                        need(users.count()==0);
                        Long id=tx.execute(status->{
                            var locks=context.getBean(DeletionJournalLockRepository.class);
                            if(!locks.existsById(1))locks.saveAndFlush(DeletionJournalLock.initial());
                            var u=users.saveAndFlush(User.create(EMAIL,"SYNTHETIC-NOT-A-LOGIN-HASH","Synthetic fixture"));
                            materials.saveAndFlush(LearningMaterial.create(u,"Synthetic fixture",KEY));
                            var a=context.getBean(AuthSessionRepository.class).saveAndFlush(AuthSession.create(u,Instant.now(),Duration.ofHours(1),Duration.ofDays(1)));
                            context.getBean(RefreshTokenRepository.class).saveAndFlush(new RefreshToken(u,a,"a".repeat(64),Instant.now().plusSeconds(3600)));
                            return u.getId();
                        });
                        emit(Map.of("status","SYNTHETIC_SEEDED","userId",id));
                    }
                    case "mark-deletions" -> {
                        owned(jdbc);
                        tx.executeWithoutResult(status->{
                            var u=users.findById(id()).orElseThrow();var material=materials.findAll().getFirst();
                            journal.recordAccount(u.getId(),u.getCreatedAt(),u.getEmail());journal.recordMaterial(material);
                            u.withdraw();material.delete();TokenRevocations.revoke(context.getBean(AuthSessionRepository.class),context.getBean(RefreshTokenRepository.class),u.getId(),Instant.now());
                        });
                        Instant at=Instant.now().truncatedTo(ChronoUnit.MICROS),retain=at.plusSeconds(86400);
                        journal.importForRestore(List.of(new DeletionSnapshot(DeletionKind.EXTERNAL_AI,"synthetic-retained",null,null,null,null,at,retain)),"ci_source_fixture");
                        emit(Map.of("status","SYNTHETIC_TOMBSTONES_READY","retainUntil",retain.toString()));
                    }
                    case "add-pagination" -> {
                        owned(jdbc);Instant at=Instant.now().truncatedTo(ChronoUnit.MICROS);
                        List<DeletionSnapshot> rows=new ArrayList<>();
                        for(int i=0;i<1001;i++)rows.add(new DeletionSnapshot(DeletionKind.EXTERNAL_AI,"synthetic-page-"+i,null,null,null,null,at,null));
                        journal.importForRestore(rows.subList(0,1000),"ci_pagination");journal.importForRestore(rows.subList(1000,1001),"ci_pagination");
                        emit(Map.of("status","SYNTHETIC_PAGINATION_READY","added",1001));
                    }
                    case "verify-restored" -> {
                        owned(jdbc);var u=users.findById(id()).orElseThrow();
                        need(!u.isActive() && u.getEmail().equals("deleted_"+u.getId()));
                        need(materials.findAll().size()==1 && !materials.findAll().getFirst().isActive());
                        need(context.getBean(AuthSessionRepository.class).findAll().stream().allMatch(AuthSession::isRevoked));
                        need(context.getBean(RefreshTokenRepository.class).findAll().stream().allMatch(RefreshToken::isRevoked));
                        need(jdbc.queryForObject("SELECT COUNT(*) FROM deletion_intents",Long.class)==4);
                        String expected=System.getenv("SYNTHETIC_RETAIN_UNTIL");
                        Instant retained=jdbc.queryForObject("SELECT retain_until FROM deletion_intents WHERE resource_key='synthetic-retained'",(r,n)->r.getTimestamp(1,Calendar.getInstance(TimeZone.getTimeZone("UTC"))).toInstant());
                        need(retained.equals(Instant.parse(expected)));
                        emit(Map.of("status","SYNTHETIC_RESTORE_VERIFIED","hooks",7,"externalClients",0,"physicalDeletionExecuted",false));
                    }
                    default -> throw new IllegalArgumentException("UNKNOWN_FIXTURE_PHASE");
                }
            }
        } catch(Throwable e) {
            List<String> types=new ArrayList<>();for(Throwable x=e;x!=null&&types.size()<6;x=x.getCause())types.add(x.getClass().getSimpleName());
            emit(Map.of("status","SYNTHETIC_FIXTURE_FAILED","exceptionTypes",types));System.exit(2);
        }
    }
    static Long id(){return Long.valueOf(System.getenv("SYNTHETIC_USER_ID"));}
    static void owned(JdbcTemplate jdbc) {
        need(jdbc.queryForObject("SELECT COUNT(*) FROM users",Long.class)==1);
        need(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email IN (?,?) OR email=CONCAT('deleted_',id)",Long.class,EMAIL,REPLACEMENT)==1);
    }
    static Map<String,Object> snapshot(JdbcTemplate jdbc,DataSource data)throws SQLException {
        owned(jdbc);String hash;try(var c=data.getConnection()){hash=LedgerDatabase.history(c);}
        return Map.of("status","SYNTHETIC_SNAPSHOT","catalog",hash,
            "intentCount",jdbc.queryForObject("SELECT COUNT(*) FROM deletion_intents",Long.class),
            "generationSum",jdbc.queryForObject("SELECT COALESCE(SUM(generation),0) FROM deletion_intents",Long.class),
            "v65Columns",jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='guardian_team_requests' AND column_name IN ('prior_consent_method','evidence_reviewed_at','prior_consent_valid_until')",Long.class),
            "v65Check",jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='guardian_team_requests' AND constraint_name='chk_guardian_team_prior_evidence' AND enforced='YES'",Long.class));
    }
    static void emit(Object value){System.out.println(new tools.jackson.databind.ObjectMapper().writeValueAsString(value));}
}
