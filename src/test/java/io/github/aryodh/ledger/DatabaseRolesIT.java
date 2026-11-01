package io.github.aryodh.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class DatabaseRolesIT extends LedgerDatabaseTestBase {

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

}
