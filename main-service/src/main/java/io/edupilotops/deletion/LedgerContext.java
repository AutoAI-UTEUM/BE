package io.edupilotops.deletion;

import java.time.Clock;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.context.annotation.*;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import io.edupilot.auth.*;
import io.edupilot.classroom.ClassroomWithdrawalHook;
import io.edupilot.deletion.*;
import io.edupilot.guardian.*;
import io.edupilot.guardian.team.*;
import io.edupilot.guardian.team.mail.GuardianTeamMailCleanup;
import io.edupilot.material.MaterialWithdrawalHook;
import io.edupilot.session.SessionWithdrawalHook;
import io.edupilot.user.UserWithdrawalHook;
import io.edupilot.user.birthdate.BirthdateCorrectionWithdrawalHook;

/** Explicit database-only context. No Boot application/configuration scan, web, Flyway, providers or schedulers. */
public final class LedgerContext {
	public static final Set<Class<?>> HOOKS=Set.of(ClassroomWithdrawalHook.class,MaterialWithdrawalHook.class,
		SessionWithdrawalHook.class,GuardianWithdrawalHook.class,GuardianWebWithdrawalHook.class,
		GuardianTeamWithdrawalHook.class,BirthdateCorrectionWithdrawalHook.class);
	private LedgerContext() { }
	public static void verifyHookCoverage() {
		var scan=new ClassPathScanningCandidateComponentProvider(false);
		scan.addIncludeFilter(new AssignableTypeFilter(UserWithdrawalHook.class));
		Set<String> actual=new HashSet<>(); scan.findCandidateComponents("io.edupilot").forEach(b->actual.add(b.getBeanClassName()));
		Set<String> expected=new HashSet<>(); HOOKS.forEach(c->expected.add(c.getName()));
		if(!actual.equals(expected))throw new IllegalStateException("WITHDRAWAL_HOOK_SET_REQUIRES_REVIEW");
	}
	public static AnnotationConfigApplicationContext open(DataSource source) {
		verifyHookCoverage();
		var context=new AnnotationConfigApplicationContext();
		context.registerBean(DataSource.class,()->source); context.register(Configuration.class);
		try{context.refresh();return context;}catch(RuntimeException error){context.close();throw error;}
	}
	@org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
	@EnableTransactionManagement
	@EnableJpaRepositories(basePackages="io.edupilot")
	@Import({DeletionJournal.class,DeletionReplay.class,DeletionRestoreService.class,
		ClassroomWithdrawalHook.class,MaterialWithdrawalHook.class,SessionWithdrawalHook.class,
		GuardianWithdrawalHook.class,BirthdateCorrectionWithdrawalHook.class,
		GuardianWebCancellation.class,GuardianTeamWithdrawalCleanup.class,GuardianTeamMailCleanup.class})
	public static class Configuration {
		@Bean Clock clock(){return Clock.systemUTC();}
		@Bean GeneralFileRetentionProperties generalFileRetention(){return new GeneralFileRetentionProperties(30);}
		@Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
			var factory=new LocalContainerEntityManagerFactoryBean(); factory.setDataSource(source);
			factory.setPackagesToScan("io.edupilot"); factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
			factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto","none","hibernate.show_sql","false",
				"hibernate.jdbc.time_zone","UTC","hibernate.generate_statistics","false")); return factory;
		}
		@Bean PlatformTransactionManager transactionManager(jakarta.persistence.EntityManagerFactory factory){return new JpaTransactionManager(factory);}
		@Bean TokenRevoker tokenRevoker(AuthSessionRepository sessions,RefreshTokenRepository tokens,Clock clock){
			return id->TokenRevocations.revoke(sessions,tokens,id,clock.instant());
		}
		@Bean GuardianWebWithdrawalHook webHook(GuardianWebCancellation cleanup){return GuardianWebWithdrawalHook.forRecovery(cleanup);}
		@Bean GuardianTeamWithdrawalHook teamHook(GuardianTeamWithdrawalCleanup cleanup){return GuardianTeamWithdrawalHook.forRecovery(cleanup);}
	}
}
