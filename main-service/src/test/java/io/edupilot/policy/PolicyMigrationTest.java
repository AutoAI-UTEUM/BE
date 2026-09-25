package io.edupilot.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlGroup;

import io.edupilot.MainServiceApplication;

@JdbcTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:policy-migration;MODE=MySQL;DB_CLOSE_DELAY=-1",
	"spring.flyway.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = MainServiceApplication.class)
@SqlGroup({
	@Sql(statements = "create table users (id bigint primary key)"),
	@Sql(scripts = "classpath:db/migration/V48__policy_documents_consents.sql")
})
class PolicyMigrationTest {
	@Autowired private JdbcTemplate jdbc;

	@Test
	void v48SeedsDraftsAndEnforcesConsentUniquenessAndUserForeignKey() {
		assertThat(jdbc.queryForList(
			"select type from policy_documents where version = '0.9' order by type",
			String.class)).containsExactly("PRIVACY", "TERMS");
		assertThat(jdbc.queryForObject(
			"select count(*) from policy_documents where effective_at <= current_timestamp",
			Integer.class)).isEqualTo(2);
		assertThatThrownBy(() -> jdbc.update("""
			insert into policy_documents
			(type, version, title, content, effective_at, created_by, created_at)
			values ('TERMS', '0.9', '중복', '본문', current_timestamp, 1, current_timestamp)
			""")).isInstanceOf(DataIntegrityViolationException.class);
		jdbc.update("insert into users (id) values (1)");
		jdbc.update("""
			insert into policy_consents
			(user_id, policy_type, policy_version, agreed_at, ip)
			values (1, 'TERMS', '0.9', current_timestamp, '192.0.2.1')
			""");
		assertThatThrownBy(() -> jdbc.update("""
			insert into policy_consents
			(user_id, policy_type, policy_version, agreed_at, ip)
			values (1, 'TERMS', '0.9', current_timestamp, '192.0.2.1')
			""")).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("""
			insert into policy_consents
			(user_id, policy_type, policy_version, agreed_at, ip)
			values (99, 'PRIVACY', '0.9', current_timestamp, '192.0.2.1')
			""")).isInstanceOf(DataIntegrityViolationException.class);
	}
}
