package io.github.aryodh.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

@SpringBootTest
class DatabaseRolesIT {

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

	@Autowired
	private JdbcClient jdbc;

	@Test
	void applicationConnectsAsApplicationRole() {
		assertThat(jdbc.sql("SELECT current_user").query(String.class).single()).isEqualTo("ledger_app");
	}

	@Test
	void migratorOwnsLedgerSchema() {
		String owner = jdbc.sql("SELECT pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname = 'ledger'")
			.query(String.class)
			.single();

		assertThat(owner).isEqualTo("ledger_migrator");
	}

	@ParameterizedTest
	@ValueSource(strings = { "CREATE TABLE ledger.forbidden (id int)", "CREATE TABLE public.forbidden (id int)",
			"CREATE SCHEMA forbidden", "CREATE TEMP TABLE forbidden (id int)", "DROP SCHEMA ledger CASCADE",
			"SELECT * FROM ledger.flyway_schema_history", "DELETE FROM ledger.flyway_schema_history" })
	void applicationRoleCannotChangeSchemaOrMigrationHistory(String sql) {
		assertThatThrownBy(() -> jdbc.sql(sql).update()).rootCause()
			.satisfiesAnyOf(cause -> assertThat(cause).hasMessageContaining("permission denied"),
					cause -> assertThat(cause).hasMessageContaining("must be owner"));
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
