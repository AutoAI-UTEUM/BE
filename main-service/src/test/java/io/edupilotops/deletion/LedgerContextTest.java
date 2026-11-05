package io.edupilotops.deletion;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import io.edupilot.auth.*;
import io.edupilot.deletion.*;
import io.edupilot.material.*;
import io.edupilot.user.*;

/** H2 synthetic integration only: this does not claim MySQL/Flyway/container isolation verification. */
class LedgerContextTest {
	private DriverManagerDataSource syntheticSchema() {
		var data=new DriverManagerDataSource("jdbc:h2:mem:operator-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
		var schema=new LocalContainerEntityManagerFactoryBean();schema.setDataSource(data);
		schema.setPackagesToScan("io.edupilot");schema.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
		schema.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto","create","hibernate.jdbc.time_zone","UTC"));
		schema.afterPropertiesSet();schema.destroy();
		return data;
	}
	@Test void exportDoesNotRequireNewPriorConsentColumnsOnTheReadOnlySource() {
		var data=syntheticSchema();var jdbc=new org.springframework.jdbc.core.JdbcTemplate(data);
		for(String column:List.of("prior_consent_method","evidence_reviewed_at","prior_consent_valid_until")) {
			jdbc.execute("ALTER TABLE guardian_team_requests DROP COLUMN "+column);
		}
		try(var context=LedgerContext.open(data)) {
			assertThat(DeletionLedgerCli.exportAll(context.getBean(DeletionJournal.class))).isEmpty();
		}
	}
	@Test void narrowContextReplaysLogicalWithdrawalAndTokenRevocationWithoutProvidersOrWorkers() {
		var data=syntheticSchema();
		try(var context=LedgerContext.open(data)) {
			assertThat(context.getBeansOfType(UserWithdrawalHook.class)).hasSize(7);
			assertThat(context.getBeansOfType(io.edupilot.ai.AiClient.class)).isEmpty();
			assertThat(context.getBeansOfType(io.edupilot.mail.EmailService.class)).isEmpty();
			assertThat(context.getBeansOfType(DeletionWorker.class)).isEmpty();
			assertThat(context.getBeansOfType(RefreshTokenService.class)).isEmpty();
			assertThat(context.getBeansOfType(org.springframework.scheduling.TaskScheduler.class)).isEmpty();
			var tx=new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
			var users=context.getBean(UserRepository.class);var materials=context.getBean(LearningMaterialRepository.class);
			var intents=context.getBean(DeletionIntentRepository.class);var journal=context.getBean(DeletionJournal.class);
			Long id=tx.execute(status->{
				context.getBean(DeletionJournalLockRepository.class).saveAndFlush(DeletionJournalLock.initial());
				var user=users.saveAndFlush(User.create("operator-synthetic@example.test","synthetic-hash","Synthetic"));
				materials.saveAndFlush(LearningMaterial.create(user,"Synthetic","synthetic.pdf"));
				var session=context.getBean(AuthSessionRepository.class).saveAndFlush(AuthSession.create(user,Instant.now(),Duration.ofHours(1),Duration.ofDays(1)));
				context.getBean(RefreshTokenRepository.class).saveAndFlush(new RefreshToken(user,session,"a".repeat(64),Instant.now().plusSeconds(3600)));
				journal.recordAccount(user.getId(),user.getCreatedAt(),user.getEmail());return user.getId();
			});
			List<DeletionSnapshot> rows=DeletionLedgerCli.exportAll(journal);
			assertThat(rows).hasSize(1);
			var encoded=LedgerCodec.encode(new LedgerCodec.Document("a".repeat(40),"12345678-1234-1234-1234-123456789abc",LedgerDatabase.V65,Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS),rows));
			var imported=LedgerCodec.decode(encoded,LedgerCodec.sha256(encoded));
			var restore=context.getBean(DeletionRestoreService.class);
			assertThat(DeletionLedgerCli.replay(restore,imported.snapshots(),"synthetic_restore")).containsEntry(DeletionReplay.Result.APPLIED,1);
			assertThat(users.findById(id).orElseThrow().isActive()).isFalse();
			assertThat(materials.findAll()).allSatisfy(m->assertThat(m.isActive()).isFalse());
			assertThat(context.getBean(AuthSessionRepository.class).findAll()).allSatisfy(s->assertThat(s.isRevoked()).isTrue());
			assertThat(context.getBean(RefreshTokenRepository.class).findAll()).allSatisfy(t->assertThat(t.isRevoked()).isTrue());
			assertThat(intents.findAll()).hasSize(3).allSatisfy(i->assertThat(i.getRetainUntil()).isNull());
			assertThat(DeletionLedgerCli.replay(restore,imported.snapshots(),"synthetic_restore")).containsEntry(DeletionReplay.Result.ALREADY_APPLIED,1);
			assertThat(intents.findAll()).hasSize(3);
		}
	}
}
