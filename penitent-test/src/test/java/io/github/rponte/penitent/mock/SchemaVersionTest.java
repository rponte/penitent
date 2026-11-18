package io.github.rponte.penitent.mock;

import static io.github.rponte.penitent.mock.PlsqlMocks.verify;
import static io.github.rponte.penitent.mock.PlsqlMocks.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;

/**
 * A schema prepared by version 1 of {@code penitent-mock-schema.sql}, which recorded the arguments in a
 * {@code VARCHAR2(4000)}: {@code begin()} refuses it, and applying the script again replaces the table.
 */
class SchemaVersionTest {

	@PlsqlPackage("FREIGHT_ARGS")
	interface Args {
		BigDecimal takesText(String pValue);
	}

	/** Turns the schema back into version 1: the table with its arguments in a VARCHAR2, and the package. */
	private static void version1(DataSource pool) throws Exception {
		try (Connection connection = pool.getConnection(); Statement statement = connection.createStatement()) {
			statement.execute("DROP TABLE PENITENT_MOCK_CALLS");
			statement.execute("CREATE TABLE PENITENT_MOCK_CALLS (EDITION_NAME VARCHAR2(128) NOT NULL, ROUTINE"
					+ " VARCHAR2(512) NOT NULL, ARGS VARCHAR2(4000), CALLED_AT TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL)");
			statement.execute("CREATE OR REPLACE PACKAGE PENITENT_MOCK AS FUNCTION version RETURN NUMBER;"
					+ " PROCEDURE record(p_routine VARCHAR2, p_args VARCHAR2); END PENITENT_MOCK;");
			statement.execute("CREATE OR REPLACE PACKAGE BODY PENITENT_MOCK AS FUNCTION version RETURN NUMBER IS BEGIN"
					+ " RETURN 1; END; PROCEDURE record(p_routine VARCHAR2, p_args VARCHAR2) IS BEGIN NULL; END;"
					+ " END PENITENT_MOCK;");
		}
	}

	private static String argsType(DataSource pool) throws Exception {
		try (Connection connection = pool.getConnection(); Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery("SELECT DATA_TYPE FROM USER_TAB_COLUMNS WHERE TABLE_NAME ="
						+ " 'PENITENT_MOCK_CALLS' AND COLUMN_NAME = 'ARGS'")) {
			rows.next();
			return rows.getString(1);
		}
	}

	@Test
	@DisplayName("a schema of version 1 is refused, saying what to apply, and preparing it again moves it to version 2")
	void version_1_is_refused_and_replaced() throws Exception {
		DataSource pool = TestDatabase.pool();
		version1(pool);
		DataSource dataSource = PlsqlMocks.wrap(pool);

		assertThatThrownBy(() -> PlsqlMocks.on(dataSource).begin())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("PENITENT_MOCK is version 1, and this library expects version 2")
				.hasMessageContaining("apply penitent-mock-schema.sql again");

		PlsqlMocks.prepare(TestDatabase.adminPool(), pool, OracleUnderTest.USER);
		assertThat(argsType(pool)).isEqualTo("CLOB");
		try (PlsqlMocks mocks = PlsqlMocks.on(dataSource)) {
			mocks.begin();
			Args mock = mocks.mock(Args.class);
			String text = "x".repeat(5_000);
			when(mock.takesText(text)).thenReturn(BigDecimal.ONE);

			assertThat(PlsqlPackages.create(Args.class, dataSource).takesText(text)).isEqualByComparingTo("1");
			verify(mock).takesText(text);
		}
	}

	@Test
	@DisplayName("applied by another user, with the session's current schema set to the owner, as a migration tool may,"
			+ " the script replaces the table of version 1 too")
	void applied_by_another_user_with_the_current_schema() throws Exception {
		DataSource pool = TestDatabase.pool();
		version1(pool);
		try (com.zaxxer.hikari.HikariDataSource asAdmin = TestDatabase.adminPool()) {
			asAdmin.setConnectionInitSql("ALTER SESSION SET CURRENT_SCHEMA = " + OracleUnderTest.USER);
			PlsqlMocks.prepare(TestDatabase.adminPool(), asAdmin, OracleUnderTest.USER);
		}

		assertThat(argsType(pool)).isEqualTo("CLOB");
		try (PlsqlMocks mocks = PlsqlMocks.on(PlsqlMocks.wrap(pool))) {
			mocks.begin();
		}
	}
}
