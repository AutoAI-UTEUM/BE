package io.edupilot.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.jdbc.Sql;
import io.edupilot.MainServiceApplication;

@JdbcTest(properties={"spring.datasource.url=jdbc:h2:mem:verification-migration;MODE=MySQL;DB_CLOSE_DELAY=-1","spring.flyway.enabled=false"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes=MainServiceApplication.class)
@Sql(statements={"drop table if exists email_verification_tokens","drop table if exists users",
	"create table users(id bigint not null primary key, email varchar(320) not null)",
	"insert into users(id,email) values(1,'legacy-synthetic@example.com')"})
@Sql(scripts="classpath:db/migration/V55__email_ownership_verification.sql")
class EmailVerificationMigrationTest {
	@Autowired private JdbcTemplate jdbc;
	@Test void legacyRowsStayUnknownAndVerifiedRequiresEvidenceAndTokenDeletionCascades() {
		assertThat(jdbc.queryForObject("select email_verification_state from users where id=1",String.class)).isEqualTo("UNKNOWN");
		assertThat(jdbc.queryForObject("select email_verified_at from users where id=1",java.sql.Timestamp.class)).isNull();
		assertThatThrownBy(()->jdbc.update("update users set email_verification_state='VERIFIED' where id=1"))
			.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
		jdbc.update("update users set email_verification_state='VERIFIED',email_verified_at=current_timestamp where id=1");
		jdbc.update("insert into email_verification_tokens(user_id,token_hash,email_hash,expires_at,created_at) values(1,?,?,current_timestamp,current_timestamp)","a".repeat(64),"b".repeat(64));
		jdbc.update("delete from users where id=1");
		assertThat(jdbc.queryForObject("select count(*) from email_verification_tokens",Long.class)).isZero();
	}
	@Test
	@EnabledIfEnvironmentVariable(named="VERIFICATION_MYSQL_URL",matches="^jdbc:mysql://127\\.0\\.0\\.1:33316/verification_synthetic(?:\\?.*)?$")
	void actualMysqlMigrationPreservesUnknownAndEnforcesEvidenceWithoutBackfillingApproval() throws Exception {
		try(var connection=DriverManager.getConnection("jdbc:mysql://127.0.0.1:33316/verification_migration_synthetic","root","");var sql=connection.createStatement()){
			try(var identity=sql.executeQuery("select @@port,database()")){assertThat(identity.next()).isTrue();assertThat(identity.getInt(1)).isEqualTo(33316);assertThat(identity.getString(2)).isEqualTo("verification_migration_synthetic");}
			sql.execute("drop table if exists email_verification_tokens");sql.execute("drop table if exists users");
			sql.execute("create table users(id bigint not null primary key,email varchar(320) not null)");
			sql.execute("insert into users(id,email) values(1,'legacy-synthetic@example.com')");
			ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/V55__email_ownership_verification.sql"));
			try(var state=sql.executeQuery("select email_verification_state,email_verified_at from users where id=1")){assertThat(state.next()).isTrue();assertThat(state.getString(1)).isEqualTo("UNKNOWN");assertThat(state.getTimestamp(2)).isNull();}
			assertThatThrownBy(()->sql.execute("update users set email_verification_state='VERIFIED' where id=1")).isInstanceOf(SQLException.class);
			assertThatThrownBy(()->sql.execute("update users set email_verification_state='UNKNOWN',email_verified_at=current_timestamp where id=1")).isInstanceOf(SQLException.class);
			sql.execute("update users set email_verification_state='VERIFIED',email_verified_at=current_timestamp where id=1");
			sql.execute("insert into email_verification_tokens(user_id,token_hash,email_hash,expires_at,created_at) values(1,'"+"a".repeat(64)+"','"+"b".repeat(64)+"',current_timestamp,current_timestamp)");
			sql.execute("delete from users where id=1");
			try(var count=sql.executeQuery("select count(*) from email_verification_tokens")){assertThat(count.next()).isTrue();assertThat(count.getInt(1)).isZero();}
		}
	}
}
