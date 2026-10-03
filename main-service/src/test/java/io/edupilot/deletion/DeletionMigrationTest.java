package io.edupilot.deletion;

import static org.assertj.core.api.Assertions.*;
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

@JdbcTest(properties={"spring.datasource.url=jdbc:h2:mem:deletion-migration;MODE=MySQL;DB_CLOSE_DELAY=-1","spring.flyway.enabled=false"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes=MainServiceApplication.class)
@Sql(statements={"drop table if exists deletion_intents","drop table if exists deletion_journal_lock"})
@Sql(scripts="classpath:db/migration/V56__durable_deletion_journal.sql")
class DeletionMigrationTest {
 @Autowired JdbcTemplate jdbc;
 @Test void schemaSeedsMutexAndRejectsLeasedRowsWithoutEvidence() {
  assertThat(jdbc.queryForObject("select count(*) from deletion_journal_lock where id=1",Integer.class)).isEqualTo(1);
  String sql="insert into deletion_intents(key_hash,kind,resource_key,requested_at,status,attempts,generation,next_attempt_at) values(?, 'EXTERNAL_AI','synthetic-file',current_timestamp,?,0,0,current_timestamp)";
  jdbc.update(sql,"a".repeat(64),"POLICY_PENDING");
  assertThatThrownBy(()->jdbc.update(sql,"b".repeat(64),"LEASED")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  assertThatThrownBy(()->jdbc.update(sql,"a".repeat(64),"POLICY_PENDING")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
 }
 @Test
 @EnabledIfEnvironmentVariable(named="DELETION_JOURNAL_MYSQL_URL",matches="^jdbc:mysql://127\\.0\\.0\\.1:33316/deletion_journal_synthetic(?:\\?.*)?$")
 void actualMysqlFullMigrationChainValidatesThroughV56() throws Exception {
  String url="jdbc:mysql://127.0.0.1:33316/deletion_journal_full_migration_synthetic";
  try(var connection=DriverManager.getConnection(url,"root","");var sql=connection.createStatement();var identity=sql.executeQuery("select @@port,database()")) {
   assertThat(identity.next()).isTrue();assertThat(identity.getInt(1)).isEqualTo(33316);assertThat(identity.getString(2)).isEqualTo("deletion_journal_full_migration_synthetic");
  }
  var flyway=org.flywaydb.core.Flyway.configure().dataSource(url,"root","").locations("classpath:db/migration").load();
  flyway.migrate();assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
  assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("56");
 }
 @Test
 @EnabledIfEnvironmentVariable(named="DELETION_JOURNAL_MYSQL_URL",matches="^jdbc:mysql://127\\.0\\.0\\.1:33316/deletion_journal_synthetic(?:\\?.*)?$")
 void actualMysqlMigrationSeedsMutexAndEnforcesLeaseBindingAndUniqueIdentity() throws Exception {
  try(var connection=DriverManager.getConnection("jdbc:mysql://127.0.0.1:33316/deletion_journal_migration_synthetic","root","");var sql=connection.createStatement()) {
   try(var identity=sql.executeQuery("select @@port,database()")){assertThat(identity.next()).isTrue();assertThat(identity.getInt(1)).isEqualTo(33316);assertThat(identity.getString(2)).isEqualTo("deletion_journal_migration_synthetic");}
   sql.execute("drop table if exists deletion_intents");sql.execute("drop table if exists deletion_journal_lock");
   ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/V56__durable_deletion_journal.sql"));
   try(var seed=sql.executeQuery("select count(*) from deletion_journal_lock where id=1")){assertThat(seed.next()).isTrue();assertThat(seed.getInt(1)).isEqualTo(1);}
   String insert="insert into deletion_intents(key_hash,kind,resource_key,requested_at,status,attempts,generation,next_attempt_at) values('"+"a".repeat(64)+"','EXTERNAL_AI','synthetic-file',current_timestamp,'POLICY_PENDING',0,0,current_timestamp)";
   sql.execute(insert);assertThatThrownBy(()->sql.execute(insert)).isInstanceOf(SQLException.class);
   assertThatThrownBy(()->sql.execute("update deletion_intents set status='LEASED'")).isInstanceOf(SQLException.class);
   assertThatThrownBy(()->sql.execute("update deletion_intents set kind='ACCOUNT'")).isInstanceOf(SQLException.class);
  }
 }
}
