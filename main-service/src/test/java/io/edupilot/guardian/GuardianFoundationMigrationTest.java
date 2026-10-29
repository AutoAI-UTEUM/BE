package io.edupilot.guardian;

import static org.assertj.core.api.Assertions.*;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.jdbc.Sql;
import io.edupilot.MainServiceApplication;

@JdbcTest(properties={"spring.datasource.url=jdbc:h2:mem:guardian-migration;MODE=MySQL;DB_CLOSE_DELAY=-1","spring.flyway.enabled=false"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes=MainServiceApplication.class)
@Sql(statements={"drop table if exists guardian_verification_requests","drop table if exists users",
 "create table users(id bigint not null primary key,email varchar(255) not null)","insert into users(id,email) values(1,'legacy-synthetic@example.com')"})
@Sql(scripts="classpath:db/migration/V57__birthdate_and_guardian_pending_foundation.sql")
class GuardianFoundationMigrationTest {
 @Autowired JdbcTemplate jdbc;
 @Test void legacyRowsRemainUnknownAndCannotBeChangedToUnimplementedApproval() {
  assertThat(jdbc.queryForObject("select date_of_birth from users where id=1",java.sql.Date.class)).isNull();
  assertThat(jdbc.queryForObject("select age_verification_state from users where id=1",String.class)).isEqualTo("UNKNOWN");
  assertThatThrownBy(()->jdbc.update("update users set age_verification_state='VERIFIED' where id=1"))
   .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
 }
 @Test @EnabledIfEnvironmentVariable(named="GUARDIAN_FOUNDATION_MYSQL_URL",matches="^jdbc:mysql://127\\.0\\.0\\.1:33316/guardian_foundation_synthetic(?:\\?.*)?$")
 void actualMysqlPreservesUnknownEnforcesNoApprovalAndValidatesEntireMigrationChain() throws Exception {
  String schema="guardian_foundation_migration_synthetic";
  try(var connection=DriverManager.getConnection("jdbc:mysql://127.0.0.1:33316/"+schema,"root","");var sql=connection.createStatement()){
   try(var identity=sql.executeQuery("select @@port,database()")){assertThat(identity.next()).isTrue();assertThat(identity.getInt(1)).isEqualTo(33316);assertThat(identity.getString(2)).isEqualTo(schema);}
   sql.execute("drop table if exists guardian_verification_requests");sql.execute("drop table if exists users");
   sql.execute("create table users(id bigint not null primary key,email varchar(255) not null)");sql.execute("insert into users(id,email) values(1,'legacy-synthetic@example.com')");
   ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/V57__birthdate_and_guardian_pending_foundation.sql"));
   try(var row=sql.executeQuery("select date_of_birth,age_verification_state from users where id=1")){assertThat(row.next()).isTrue();assertThat(row.getDate(1)).isNull();assertThat(row.getString(2)).isEqualTo("UNKNOWN");}
   assertThatThrownBy(()->sql.execute("update users set age_verification_state='VERIFIED' where id=1")).isInstanceOf(SQLException.class);
  }
  String url="jdbc:mysql://127.0.0.1:33316/guardian_foundation_full_migration_synthetic";
  try(var connection=DriverManager.getConnection(url,"root","");var sql=connection.createStatement();var identity=sql.executeQuery("select @@port,database()")){
   assertThat(identity.next()).isTrue();assertThat(identity.getInt(1)).isEqualTo(33316);assertThat(identity.getString(2)).isEqualTo("guardian_foundation_full_migration_synthetic");
  }
  var flyway=org.flywaydb.core.Flyway.configure().dataSource(url,"root","").locations("classpath:db/migration").target("57").load();
  flyway.migrate();assertThat(flyway.validateWithResult().validationSuccessful).isTrue();assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("57");
 }
}
