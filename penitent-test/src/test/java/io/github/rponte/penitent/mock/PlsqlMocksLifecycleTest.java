package io.github.rponte.penitent.mock;

import static io.github.rponte.penitent.mock.PlsqlMocks.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;

/** The edition of a test: created by begin, dropped by end, and nothing left behind; and the prerequisites. */
class PlsqlMocksLifecycleTest {

	@PlsqlPackage("FREIGHTS")
	interface Freights {
		BigDecimal calculate(String pOrigin, String pDestination);
	}

	private static DataSource pool;
	private static DataSource dataSource;

	@BeforeAll
	static void startDatabase() {
		pool = TestDatabase.pool();
		dataSource = PlsqlMocks.wrap(pool);
	}

	private static int count(DataSource dataSource, String sql) {
		try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery(sql)) {
			result.next();
			return result.getInt(1);
		} catch (SQLException e) {
			throw new IllegalStateException(e);
		}
	}

	@Test
	@DisplayName("tests in a row each get an edition, and after end the routine is real and no edition is left")
	void tests_in_a_row() {
		Freights freights = PlsqlPackages.create(Freights.class, dataSource);
		for (String stubbed : new String[] { "24.90", "31.00" }) {
			try (PlsqlMocks mocks = PlsqlMocks.on(dataSource)) {
				mocks.begin();
				when(mocks.mock(Freights.class).calculate("SP", "CE")).thenReturn(new BigDecimal(stubbed));

				assertThat(freights.calculate("SP", "CE")).isEqualByComparingTo(stubbed);
			}
			assertThat(freights.calculate("SP", "CE")).as("real after end").isEqualByComparingTo("75");
		}
		assertThat(count(pool, "SELECT COUNT(*) FROM ALL_EDITIONS WHERE EDITION_NAME LIKE 'PENITENT\\_MOCK\\_%' ESCAPE '\\'"))
				.isZero();
		assertThat(count(pool, "SELECT COUNT(*) FROM PENITENT_MOCK_CALLS")).as("calls of ended tests").isZero();
	}

	@Test
	@DisplayName("a connection taken from the pool after end runs in the database's default edition")
	void connection_after_end() {
		try (PlsqlMocks mocks = PlsqlMocks.on(dataSource)) {
			mocks.begin();
			assertThat(count(dataSource, "SELECT COUNT(*) FROM DUAL WHERE SYS_CONTEXT('USERENV', 'CURRENT_EDITION_NAME')"
					+ " LIKE 'PENITENT\\_MOCK\\_%' ESCAPE '\\'")).isEqualTo(1);
		}
		assertThat(count(dataSource, "SELECT COUNT(*) FROM DUAL WHERE SYS_CONTEXT('USERENV', 'CURRENT_EDITION_NAME')"
				+ " = 'ORA$BASE'")).isEqualTo(1);
	}

	@Test
	@DisplayName("a connection taken before begin, and kept, stays outside the edition and sees the real routine")
	void connection_taken_before_begin() throws SQLException {
		Freights freights = PlsqlPackages.create(Freights.class, dataSource);
		try (Connection before = dataSource.getConnection(); PlsqlMocks mocks = PlsqlMocks.on(dataSource)) {
			mocks.begin();
			when(mocks.mock(Freights.class).calculate("SP", "CE")).thenReturn(new BigDecimal("24.90"));

			assertThat(PlsqlPackages.using(freights, before).calculate("SP", "CE")).isEqualByComparingTo("75");
			assertThat(freights.calculate("SP", "CE")).isEqualByComparingTo("24.90");
		}
	}

	@Test
	@DisplayName("a connection taken in the test and still open at end keeps the edition in use: end fails, saying so")
	void connection_open_at_end() throws SQLException {
		PlsqlMocks mocks = PlsqlMocks.on(dataSource);
		mocks.begin();
		Connection open = dataSource.getConnection();
		try {
			assertThatThrownBy(mocks::end)
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("still be open");
		} finally {
			open.close();
		}
		// the edition left behind is dropped by the next begin
		try (PlsqlMocks next = PlsqlMocks.on(dataSource)) {
			next.begin();
		}
		assertThat(count(pool, "SELECT COUNT(*) FROM ALL_EDITIONS WHERE EDITION_NAME LIKE 'PENITENT\\_MOCK\\_%' ESCAPE '\\'"))
				.isZero();
	}

	@Test
	@DisplayName("an edition left by a run that died is dropped by the next begin")
	void leftover_edition() {
		TestDatabase.execute(pool, "CREATE EDITION PENITENT_MOCK_LEFTOVER AS CHILD OF ORA$BASE");

		try (PlsqlMocks mocks = PlsqlMocks.on(dataSource)) {
			mocks.begin();
		}

		assertThat(count(pool, "SELECT COUNT(*) FROM ALL_EDITIONS WHERE EDITION_NAME = 'PENITENT_MOCK_LEFTOVER'"))
				.isZero();
	}

	@Test
	@DisplayName("begin twice without end is refused")
	void begin_twice() {
		try (PlsqlMocks mocks = PlsqlMocks.on(dataSource)) {
			mocks.begin();
			assertThatThrownBy(mocks::begin).isInstanceOf(IllegalStateException.class).hasMessageContaining("already");
		}
	}

	@Test
	@DisplayName("a DataSource that is not wrapped is refused, since its connections would never enter the edition")
	void not_wrapped() {
		assertThatThrownBy(() -> PlsqlMocks.on(pool))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("PlsqlMocks.wrap");
	}

	@Test
	@DisplayName("prepare again, on a schema already prepared, is harmless")
	void prepare_again() {
		PlsqlMocks.prepare(TestDatabase.newPool(TestDatabase.oracle().adminUser()), pool, OracleUnderTest.USER);

		try (PlsqlMocks mocks = PlsqlMocks.on(dataSource)) {
			mocks.begin();
		}
	}

	@Test
	@DisplayName("a schema without the prerequisites is refused at begin, naming what is missing and the scripts")
	void missing_prerequisites() {
		TestDatabase.execute(TestDatabase.newPool(TestDatabase.oracle().adminUser()),
				"CREATE USER PLSQL_UNPREPARED IDENTIFIED BY \"" + OracleUnderTest.PASSWORD + "\"",
				"GRANT CREATE SESSION TO PLSQL_UNPREPARED");
		DataSource unprepared = PlsqlMocks.wrap(TestDatabase.newPool("PLSQL_UNPREPARED"));

		assertThatThrownBy(() -> PlsqlMocks.on(unprepared).begin())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("editions are not enabled for PLSQL_UNPREPARED")
				.hasMessageContaining("CREATE ANY EDITION")
				.hasMessageContaining("PENITENT_MOCK")
				.hasMessageContaining("penitent-mock-admin.sql")
				.hasMessageContaining("penitent-mock-schema.sql");
	}
}
