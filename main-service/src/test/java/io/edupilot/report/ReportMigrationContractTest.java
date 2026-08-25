package io.edupilot.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class ReportMigrationContractTest {

	private static final Pattern CREATE_TABLE = Pattern.compile("CREATE TABLE ");

	@Test
	void v19ContainsReportPersistenceContract() throws Exception {
		String migration = migration("V19__report.sql");

		assertThat(CREATE_TABLE.matcher(migration).results()).hasSize(5);
		assertThat(migration)
			.contains("CREATE TABLE report_criteria")
			.contains("CREATE TABLE report_generations")
			.contains("CREATE TABLE student_reports")
			.contains("CREATE TABLE report_criterion_results")
			.contains("CREATE TABLE report_evidence_snapshots")
			.contains("uk_report_criteria_classroom_key_version")
			.contains("uk_report_generations_classroom_student_request")
			.contains("uk_student_reports_generation UNIQUE (generation_id)")
			.contains("uk_student_reports_classroom_student_version")
			.contains("uk_report_criterion_results_report_key")
			.contains("uk_report_evidence_snapshots_generation_evidence")
			.contains("idx_report_generations_status_lease")
			.contains("idx_report_generations_classroom_student_status")
			.contains("idx_report_evidence_snapshots_generation_source")
			.contains("DEFAULT '1970-01-01 00:00:00.000000'")
			.contains("status <> 'ASSESSED' OR score IS NOT NULL")
			.doesNotContain("session_page_progress")
			.doesNotContain("report_questions")
			.doesNotContain("INSERT INTO")
			.doesNotContain("ON DELETE CASCADE");
	}

	@Test
	void preservesAppliedMigrationChecksumsAndOrder() throws Exception {
		assertThat(sha256(migration("V17__exam.sql")))
			.isEqualTo("0a74ab074b25e5cb3a0f64c9b60272e8676f7c80f86d506105cb33b771e8af83");
		assertThat(sha256(migration("V18__exam_grading_lease.sql")))
			.isEqualTo("c8da2bfe747c89903eee09d1bd731d6b1e1635c42bc5e55a1ff7fe7df32bc51e");
		assertThat(List.of(
			"V17__exam.sql",
			"V18__exam_grading_lease.sql",
			"V19__report.sql"
		)).isSortedAccordingTo((left, right) ->
			Integer.compare(version(left), version(right))
		);
	}

	@Test
	void v25BackfillsMixedScopeKeysBeforeReplacingVersionUnique() throws Exception {
		String migration = migration("V25__report_scope_chain.sql");

		int addNullable = migration.indexOf(
			"ADD COLUMN scope_key VARCHAR(16) NULL"
		);
		int backfill = migration.indexOf("UPDATE student_reports report");
		int makeNotNull = migration.indexOf(
			"MODIFY COLUMN scope_key VARCHAR(16) NOT NULL"
		);
		int dropOldUnique = migration.indexOf(
			"DROP INDEX uk_student_reports_classroom_student_version"
		);
		int addScopeUnique = migration.indexOf(
			"uk_student_reports_classroom_student_scope_version"
		);

		assertThat(addNullable).isGreaterThanOrEqualTo(0);
		assertThat(backfill).isGreaterThan(addNullable);
		assertThat(makeNotNull).isGreaterThan(backfill);
		assertThat(dropOldUnique).isGreaterThan(makeNotNull);
		assertThat(addScopeUnique).isGreaterThan(dropOldUnique);
		assertThat(migration)
			.contains("JOIN report_generations generation")
			.contains("generation.scope_type = 'FULL'")
			.contains("THEN 'FULL'")
			.contains("generation.scope_type = 'WEEK'")
			.contains("THEN CONCAT('WEEK:', generation.week_number)")
			.contains("ELSE NULL")
			.contains("UNIQUE (classroom_id, student_id, scope_key, version)")
			.doesNotContain("COALESCE")
			.doesNotContain("DEFAULT 'FULL'");
	}

	private String migration(String filename) throws Exception {
		try (var input = getClass().getResourceAsStream("/db/migration/" + filename)) {
			assertThat(input).isNotNull();
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private String sha256(String content) throws Exception {
		byte[] bytes = content.replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8);
		return java.util.HexFormat.of().formatHex(
			MessageDigest.getInstance("SHA-256").digest(bytes)
		);
	}

	private int version(String filename) {
		return Integer.parseInt(filename.substring(1, filename.indexOf("__")));
	}
}
