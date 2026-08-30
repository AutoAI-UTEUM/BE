package io.edupilot.global.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

class MigrationVersionUniquenessTest {

	private static final Pattern VERSIONED_MIGRATION = Pattern.compile(
		"^V([0-9][0-9._]*)__.+\\.sql$"
	);

	@Test
	void flywayMigrationVersionsAreUnique() throws IOException {
		Resource[] resources = new PathMatchingResourcePatternResolver()
			.getResources("classpath*:db/migration/V*__*.sql");
		Map<MigrationVersion, List<String>> filenamesByVersion = new LinkedHashMap<>();

		for (Resource resource : resources) {
			String filename = resource.getFilename();
			Matcher matcher = VERSIONED_MIGRATION.matcher(filename == null ? "" : filename);
			if (!matcher.matches()) {
				continue;
			}
			MigrationVersion version = MigrationVersion.fromVersion(matcher.group(1));
			filenamesByVersion.computeIfAbsent(version, ignored -> new ArrayList<>())
				.add(filename);
		}

		List<List<String>> duplicates = filenamesByVersion.values().stream()
			.filter(filenames -> filenames.size() > 1)
			.toList();

		assertThat(duplicates)
			.as("Flyway migration versions must be unique")
			.isEmpty();
	}
}
