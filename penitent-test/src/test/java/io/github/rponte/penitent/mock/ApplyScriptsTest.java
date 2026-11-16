package io.github.rponte.penitent.mock;

import static io.github.rponte.penitent.mock.PlsqlMocks.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;

/**
 * A test replaces a view, or a whole package body, by applying SQL in its edition: on a connection of the mocks'
 * own, outside the test's transaction, and only for objects that the edition can hold, which go with it.
 */
class ApplyScriptsTest {

	@PlsqlPackage("FREIGHTS")
	interface Freights {
		BigDecimal countViewItems(String pUf);
	}

	@PlsqlPackage("CHECKOUT")
	interface Checkout {
		BigDecimal total(String pOrigin, String pDestination);
	}

	private static DataSource pool;
	private static DataSource dataSource;

	private PlsqlMocks mocks;
	private Freights freights;
	private Freights realFreights;
	private Checkout checkout;

	@BeforeAll
	static void startDatabase() {
		pool = TestDatabase.pool();
		dataSource = PlsqlMocks.wrap(pool);
	}

	@BeforeEach
	void begin() {
		mocks = PlsqlMocks.on(dataSource);
		mocks.begin();
		freights = PlsqlPackages.create(Freights.class, dataSource);
		realFreights = PlsqlPackages.create(Freights.class, pool);
		checkout = PlsqlPackages.create(Checkout.class, dataSource);
	}

	@AfterEach
	void end() {
		mocks.end();
	}

	@Test
	@DisplayName("a view replaced in the test's edition: the test sees its rows, every other session the real ones")
	void view() {
		mocks.applyScriptText("CREATE OR REPLACE VIEW freight_items_v AS SELECT 1 AS id, 'SP' AS uf FROM dual"
				+ " UNION ALL SELECT 2, 'SP' FROM dual;");

		assertThat(freights.countViewItems("SP")).isEqualByComparingTo("2");
		assertThat(realFreights.countViewItems("SP")).isEqualByComparingTo("0");
	}

	@Test
	@DisplayName("a script from the classpath, its statements ended by a line with a slash: a view and a package body")
	void script() {
		mocks.applyScript("/mocks/freight-items-and-checkout.sql");

		assertThat(freights.countViewItems("SP")).isEqualByComparingTo("2");
		assertThat(checkout.total("SP", "CE")).isEqualByComparingTo("999");
	}

	@Test
	@DisplayName("a script from the file system, by the file: prefix or a Path, and from an InputStream, which stays open")
	void other_sources() throws Exception {
		Path file = Path.of("src", "test", "resources", "mocks", "freight-items-and-checkout.sql");

		mocks.applyScript("file:" + file);
		assertThat(freights.countViewItems("SP")).isEqualByComparingTo("2");

		mocks.applyScript(file);
		assertThat(checkout.total("SP", "CE")).isEqualByComparingTo("999");

		boolean[] closed = { false };
		ByteArrayInputStream in = new ByteArrayInputStream(
				"CREATE OR REPLACE VIEW freight_items_v AS SELECT 1 AS id, 'SP' AS uf FROM dual;"
						.getBytes(StandardCharsets.UTF_8)) {
			@Override
			public void close() {
				closed[0] = true;
			}
		};
		mocks.applyScript(in);
		assertThat(freights.countViewItems("SP")).isEqualByComparingTo("1");
		assertThat(closed[0]).as("closed by the library").isFalse();
	}

	@Test
	@DisplayName("applyScript takes a location, never SQL: SQL is refused, pointing to applyScriptText")
	void location_not_sql() {
		assertThatThrownBy(() -> mocks.applyScript("CREATE OR REPLACE VIEW freight_items_v AS SELECT 1 AS id FROM dual"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("applyScriptText");
		assertThatThrownBy(() -> mocks.applyScript("/mocks/missing.sql"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("/mocks/missing.sql");
	}

	@Test
	@DisplayName("a type the schema does not edition is refused before running, with what a DBA has to run")
	void type_not_editioned() {
		assertThatThrownBy(() -> mocks.applyScriptText("CREATE OR REPLACE SYNONYM items FOR freight_item;"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("SYNONYM")
				.hasMessageContaining("ENABLE EDITIONS FOR SYNONYM");
	}

	@Test
	@DisplayName("one statement refused refuses the whole script: nothing of it runs")
	void all_or_nothing() {
		assertThatThrownBy(() -> mocks.applyScriptText("""
				CREATE OR REPLACE VIEW freight_items_v AS SELECT 1 AS id, 'SP' AS uf FROM dual;
				CREATE TABLE penitent_scratch (id NUMBER);
				"""))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("statement 2");

		assertThat(freights.countViewItems("SP")).isEqualByComparingTo("0");
	}

	@Test
	@DisplayName("what a script replaced goes with the edition: the next test sees the real objects")
	void gone_with_the_edition() {
		mocks.applyScript("/mocks/freight-items-and-checkout.sql");
		mocks.end();
		mocks.begin();

		assertThat(freights.countViewItems("SP")).isEqualByComparingTo("0");
		assertThat(checkout.total("SP", "CE")).isNotEqualByComparingTo("999");
	}

	@Test
	@DisplayName("applying commits nothing of the test's transaction: it runs on a connection of the mocks' own")
	void outside_the_transaction() throws Exception {
		try (Connection connection = dataSource.getConnection()) {
			connection.setAutoCommit(false);
			try (Statement statement = connection.createStatement()) {
				statement.executeUpdate("INSERT INTO freight_item (id, uf) VALUES (9300, 'SP')");
			}

			mocks.applyScriptText("CREATE OR REPLACE VIEW freight_items_v AS SELECT 1 AS id, 'SP' AS uf FROM dual");

			connection.rollback();
		}
		assertThat(rowsWithId(9300)).as("rolled back, not committed by the DDL").isZero();
	}

	@Test
	@DisplayName("what the edition cannot hold is refused before running, naming the statement: a table, DML, DROP, ALTER")
	void refused_statements() throws Exception {
		for (String sql : new String[] { "CREATE TABLE penitent_scratch (id NUMBER)",
				"INSERT INTO freight_item (id, uf) VALUES (9301, 'SP')", "DROP VIEW freight_items_v",
				"ALTER VIEW freight_items_v COMPILE" }) {
			assertThatThrownBy(() -> mocks.applyScriptText(sql))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining(sql.substring(0, 20))
					.hasMessageContaining("statement 1");
		}
		assertThat(rowsWithId(9301)).isZero();
		assertThat(tables("PENITENT_SCRATCH")).isZero();
	}

	@Test
	@DisplayName("an object of another schema is refused before running: its editioned types cannot be checked")
	void refused_other_schema() {
		assertThatThrownBy(() -> mocks.applyScriptText("CREATE OR REPLACE VIEW plsql_registry.region_v AS SELECT 1 AS id FROM dual"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("PLSQL_REGISTRY");
	}

	@Test
	@DisplayName("a body that does not compile is reported with its errors, as a stub's would be")
	void compilation_errors() {
		assertThatThrownBy(() -> mocks.applyScriptText("CREATE OR REPLACE PACKAGE BODY checkout AS"
				+ " FUNCTION total(p_origin VARCHAR2, p_destination VARCHAR2) RETURN NUMBER IS BEGIN RETURN x; END total;"
				+ " END checkout;"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("does not compile")
				.hasMessageContaining("PLS-00201");
	}

	@Test
	@DisplayName("a package has stubs or is replaced by a script in a test, not both, in either order")
	void stubs_and_scripts_do_not_mix() {
		Checkout mock = mocks.mock(Checkout.class);
		when(mock.total("SP", "CE")).thenReturn(BigDecimal.TEN);

		assertThatThrownBy(() -> mocks.applyScript("/mocks/freight-items-and-checkout.sql"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("CHECKOUT")
				.hasMessageContaining("stub");

		mocks.end();
		mocks.begin();
		mocks.applyScript("/mocks/freight-items-and-checkout.sql");
		Checkout again = mocks.mock(Checkout.class);
		assertThatThrownBy(() -> when(again.total("SP", "CE")).thenReturn(BigDecimal.TEN))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("CHECKOUT")
				.hasMessageContaining("script");
	}

	private static int rowsWithId(int id) throws Exception {
		return count("SELECT COUNT(*) FROM freight_item WHERE id = " + id);
	}

	private static int tables(String name) throws Exception {
		return count("SELECT COUNT(*) FROM user_tables WHERE table_name = '" + name + "'");
	}

	private static int count(String sql) throws Exception {
		try (Connection connection = pool.getConnection(); Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery(sql)) {
			rows.next();
			return rows.getInt(1);
		}
	}
}
