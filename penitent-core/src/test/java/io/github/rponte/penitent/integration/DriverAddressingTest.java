package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Struct;
import java.sql.Types;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zaxxer.hikari.HikariDataSource;

import oracle.jdbc.OracleConnection;
import oracle.jdbc.OracleDriver;

/**
 * Probes of the driver alone, without the library: how {@code ojdbc} addresses the PL/SQL types the
 * library binds. The library builds these names from the dictionary, and its round-trip tests only
 * show that the right name works; these also pin the wrong names that fail, which is why the library
 * builds them as it does. If a new driver changes the addressing, they say so before the library's
 * own tests fail with a less direct message.
 *
 * <ul>
 * <li>A package RECORD binds as a {@link Struct} by {@code PACKAGE.TYPE}, since JDBC 12.1, though the
 * JDBC guide's type table still says RECORD is not supported.
 * <li>A table's {@code %ROWTYPE} has no type name in {@code ALL_ARGUMENTS}, yet binds by
 * {@code TABLE%ROWTYPE}, with the literal suffix; the table name alone is {@code ORA-17060}. Before
 * driver 23, the driver puts the whole name in quotes before asking the database to resolve it, so the
 * suffix becomes part of the identifier and nothing resolves, unless the name already starts with a
 * quote: {@code "SCHEMA".TABLE%ROWTYPE}, with the owner in quotes, binds on every driver, and is the
 * name the library uses.
 * <li>A package collection binds as an {@link Array} by {@code PACKAGE.TYPE}; the name alone is
 * {@code ORA-17074}. Its elements are the RECORD or the {@code %ROWTYPE} above.
 * </ul>
 *
 * <p>The failures are compared by error code, which every driver version keeps: before driver 23, the
 * message does not carry the {@code ORA-} number.
 */
class DriverAddressingTest extends OracleIntegrationTest {

	private static final String PACKAGE = "FIXTURE_COMPOSITES";

	private static final int DRIVER = new OracleDriver().getMajorVersion();

	/** {@code ORA-17060}, "Fail to construct descriptor". */
	private static final int FAIL_TO_CONSTRUCT_DESCRIPTOR = 17060;

	/** {@code ORA-17074}, "invalid name pattern". */
	private static final int INVALID_NAME_PATTERN = 17074;

	@Test
	@DisplayName("a package RECORD binds as a Struct by PACKAGE.TYPE")
	void record_as_struct() throws SQLException {
		Struct echo = call("ECHO_RECORD", Types.STRUCT, PACKAGE + ".MINI_ITEM",
				connection -> connection.createStruct(PACKAGE + ".MINI_ITEM", miniItem(7)));

		assertThat(echo.getSQLTypeName()).contains("MINI_ITEM");
		assertThat(echo.getAttributes()).containsExactly(BigDecimal.valueOf(7), "item-7", new BigDecimal("1.5"));
	}

	@Test
	@DisplayName("a %ROWTYPE binds as a Struct by TABLE%ROWTYPE, with the literal suffix, from driver 23 on")
	void rowtype_as_struct() throws SQLException {
		assumeTrue(DRIVER >= 23, "before driver 23, see rowtype_before_driver_23");
		Struct echo = call("ECHO_ROWTYPE", Types.STRUCT, "FIXTURE_ITEM%ROWTYPE",
				connection -> connection.createStruct("FIXTURE_ITEM%ROWTYPE", item(7)));

		assertThat(echo.getAttributes()).hasSize(4);
		assertThat(echo.getAttributes()[0]).isEqualTo(BigDecimal.valueOf(7));
	}

	@Test
	@DisplayName("before driver 23, TABLE%ROWTYPE does not resolve, with or without the owner")
	void rowtype_before_driver_23() {
		assumeTrue(DRIVER < 23, "only drivers before 23 put the name in quotes");
		assertThat(failureOnNewConnection("ECHO_ROWTYPE", Types.STRUCT, "FIXTURE_ITEM%ROWTYPE"))
				.isEqualTo(FAIL_TO_CONSTRUCT_DESCRIPTOR);
		assertThat(failureOnNewConnection("ECHO_ROWTYPE", Types.STRUCT, "PLSQL_TEST.FIXTURE_ITEM%ROWTYPE"))
				.isEqualTo(FAIL_TO_CONSTRUCT_DESCRIPTOR);
	}

	@Test
	@DisplayName("with the owner in quotes, a %ROWTYPE binds on every driver, in both directions")
	void rowtype_with_quoted_owner() throws SQLException {
		String name = "\"PLSQL_TEST\".FIXTURE_ITEM%ROWTYPE";
		Struct echo = call("ECHO_ROWTYPE", Types.STRUCT, name, connection -> connection.createStruct(name, item(7)));

		assertThat(echo.getAttributes()).hasSize(4);
		assertThat(echo.getAttributes()[0]).isEqualTo(BigDecimal.valueOf(7));
	}

	@Test
	@DisplayName("the table name without %ROWTYPE does not resolve")
	void table_name_alone_does_not_resolve() {
		assertThat(failureOnNewConnection("ECHO_ROWTYPE", Types.STRUCT, "FIXTURE_ITEM"))
				.isEqualTo(FAIL_TO_CONSTRUCT_DESCRIPTOR);
	}

	@Test
	@DisplayName("a TABLE OF RECORD comes back as an Array of Struct, the element addressed by PACKAGE.RECORD")
	void collection_of_records_returned() throws SQLException {
		Array echo = call("ECHO_RECORDS", Types.ARRAY, PACKAGE + ".MINI_ITEM_LIST",
				connection -> records(connection, 10, 32));

		assertThat(echo.getBaseTypeName()).isEqualTo("PLSQL_TEST." + PACKAGE + ".MINI_ITEM");
		Object[] elements = (Object[]) echo.getArray();
		assertThat(elements).hasSize(2);
		assertThat(((Struct) elements[0]).getAttributes()).hasSize(3);
	}

	@Test
	@DisplayName("a TABLE OF %ROWTYPE comes back as an Array of Struct, the element carrying the suffix")
	void collection_of_rowtypes_returned() throws SQLException {
		Array echo = call("ECHO_ROWTYPES", Types.ARRAY, PACKAGE + ".ITEM_LIST",
				connection -> rowtypes(connection, 10, 32));

		// driver 23 quotes the element's name, an older one does not
		assertThat(echo.getBaseTypeName()).isEqualTo(DRIVER >= 23 ? "\"PLSQL_TEST\".\"FIXTURE_ITEM%ROWTYPE\""
				: "PLSQL_TEST.FIXTURE_ITEM%ROWTYPE");
		Object[] elements = (Object[]) echo.getArray();
		assertThat(elements).hasSize(2);
		assertThat(((Struct) elements[0]).getAttributes()).hasSize(4);
	}

	@Test
	@DisplayName("a TABLE OF RECORD goes in as an IN parameter, with its values intact")
	void collection_of_records_as_parameter() throws SQLException {
		Array echo = call("ECHO_RECORDS", Types.ARRAY, PACKAGE + ".MINI_ITEM_LIST",
				connection -> records(connection, 10, 32));

		assertThat(ids(echo)).containsExactly(BigDecimal.valueOf(10), BigDecimal.valueOf(32));
	}

	@Test
	@DisplayName("a TABLE OF %ROWTYPE goes in as an IN parameter, with its values intact")
	void collection_of_rowtypes_as_parameter() throws SQLException {
		Array echo = call("ECHO_ROWTYPES", Types.ARRAY, PACKAGE + ".ITEM_LIST",
				connection -> rowtypes(connection, 10, 32));

		assertThat(ids(echo)).containsExactly(BigDecimal.valueOf(10), BigDecimal.valueOf(32));
	}

	@Test
	@DisplayName("the collection name without the package does not resolve")
	void collection_without_package_does_not_resolve() {
		assertThat(failureOnNewConnection("ECHO_RECORDS", Types.ARRAY, "MINI_ITEM_LIST")).isEqualTo(INVALID_NAME_PATTERN);
	}

	@FunctionalInterface
	private interface Argument {
		Object create(Connection connection) throws SQLException;
	}

	/**
	 * The driver keeps the descriptors it resolved in the connection, and finds a name there by what it
	 * resolved to, whatever the spelling: on a connection where {@code "PLSQL_TEST".FIXTURE_ITEM%ROWTYPE}
	 * was resolved, {@code PLSQL_TEST.FIXTURE_ITEM%ROWTYPE} resolves too, even on driver 21. So these
	 * probes have a pool of their own, which leaves the descriptors of the other tests' pool alone, and
	 * a name expected to fail is tried on a new connection.
	 */
	private static HikariDataSource probes;

	@BeforeAll
	static void openProbes() {
		probes = newPool();
	}

	@AfterAll
	static void closeProbes() {
		probes.close();
	}

	/** The error code of resolving {@code typeName}, on a connection that never resolved anything. */
	private static int failureOnNewConnection(String routine, int sqlType, String typeName) {
		try (HikariDataSource fresh = newPool(); Connection connection = fresh.getConnection();
				CallableStatement statement = connection.prepareCall("{? = call " + PACKAGE + "." + routine + "(?)}")) {
			statement.registerOutParameter(1, sqlType, typeName);
			throw new AssertionError(typeName + " resolved");
		} catch (SQLException e) {
			return e.getErrorCode();
		}
	}

	/** {@code ? = call FIXTURE_COMPOSITES.<routine>(?)}, the result registered with {@code typeName}. */
	@SuppressWarnings("unchecked")
	private static <T> T call(String routine, int sqlType, String typeName, Argument argument) throws SQLException {
		try (Connection connection = probes.getConnection();
				CallableStatement statement = connection.prepareCall("{? = call " + PACKAGE + "." + routine + "(?)}")) {
			statement.registerOutParameter(1, sqlType, typeName);
			statement.setObject(2, argument.create(connection));
			statement.execute();
			return (T) statement.getObject(1);
		}
	}

	private static Object[] miniItem(int id) {
		return new Object[] { id, "item-" + id, 1.5 };
	}

	private static Object[] item(int id) {
		return new Object[] { id, "item-" + id, 1.5, null };
	}

	private static Array records(Connection connection, int... ids) throws SQLException {
		Object[] elements = new Object[ids.length];
		for (int i = 0; i < ids.length; i++) {
			elements[i] = connection.createStruct(PACKAGE + ".MINI_ITEM", miniItem(ids[i]));
		}
		return connection.unwrap(OracleConnection.class).createOracleArray(PACKAGE + ".MINI_ITEM_LIST", elements);
	}

	private static Array rowtypes(Connection connection, int... ids) throws SQLException {
		Object[] elements = new Object[ids.length];
		for (int i = 0; i < ids.length; i++) {
			elements[i] = connection.createStruct("\"PLSQL_TEST\".FIXTURE_ITEM%ROWTYPE", item(ids[i]));
		}
		return connection.unwrap(OracleConnection.class).createOracleArray(PACKAGE + ".ITEM_LIST", elements);
	}

	private static Object[] ids(Array array) throws SQLException {
		Object[] elements = (Object[]) array.getArray();
		Object[] ids = new Object[elements.length];
		for (int i = 0; i < elements.length; i++) {
			ids[i] = ((Struct) elements[i]).getAttributes()[0];
		}
		return ids;
	}
}
