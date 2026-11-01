package io.github.aryodh.ledger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.flywaydb.core.Flyway;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

@SpringBootTest
abstract class LedgerDatabaseTestBase {

	private static final String MIGRATOR_PASSWORD = "test-migrator";
	private static final String APP_PASSWORD = "test-app";

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(postgresImageFromCompose())
		.withDatabaseName("ledger")
		.withUsername("postgres")
		.withEnv("LEDGER_MIGRATOR_DB_PASSWORD", MIGRATOR_PASSWORD)
		.withEnv("LEDGER_APP_DB_PASSWORD", APP_PASSWORD)
		.withCopyFileToContainer(MountableFile.forHostPath("db/init/01-roles.sh", 0755),
				"/docker-entrypoint-initdb.d/01-roles.sh");

	static {
		POSTGRES.start();
		Flyway.configure()
			.dataSource(POSTGRES.getJdbcUrl(), "ledger_migrator", MIGRATOR_PASSWORD)
			.schemas("ledger")
			.load()
			.migrate();
	}

	@DynamicPropertySource
	static void databaseProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "&currentSchema=ledger");
		registry.add("spring.datasource.password", () -> APP_PASSWORD);
	}

	private static String postgresImageFromCompose() {
		try {
			// Testcontainers can't parse a name with both a tag and a digest, so this keeps the digest only.
			var matcher = Pattern.compile("image: postgres:\\S+?(@sha256:\\S+)")
				.matcher(Files.readString(Path.of("compose.yaml")));
			if (!matcher.find()) {
				throw new IllegalStateException("No digest-pinned postgres image in compose.yaml");
			}
			return "postgres" + matcher.group(1);
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
