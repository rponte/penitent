package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Struct;
import java.sql.Types;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import oracle.jdbc.OracleConnection;

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
 * {@code TABLE%ROWTYPE}, with the literal suffix; the table name alone is {@code ORA-17060}.
 * <li>A package collection binds as an {@link Array} by {@code PACKAGE.TYPE}; the name alone is
 * {@code ORA-17074}. Its elements are the RECORD or the {@code %ROWTYPE} above.
 * </ul>
 */
class DriverAddressingTest extends OracleIntegrationTest {

	private static final String PACKAGE = "FIXTURE_COMPOSITES";

	@Test
	@DisplayName("a package RECORD binds as a Struct by PACKAGE.TYPE")
	void record_as_struct() throws SQLException {
		Struct echo = call("ECHO_RECORD", Types.STRUCT, PACKAGE + ".MINI_ITEM",
				connection -> connection.createStruct(PACKAGE + ".MINI_ITEM", miniItem(7)));

		assertThat(echo.getSQLTypeName()).contains("MINI_ITEM");
		assertThat(echo.getAttributes()).containsExactly(BigDecimal.valueOf(7), "item-7", new BigDecimal("1.5"));
	}

	@Test
	@DisplayName("a %ROWTYPE binds as a Struct by TABLE%ROWTYPE, with the literal suffix")
	void rowtype_as_struct() throws SQLException {
		Struct echo = call("ECHO_ROWTYPE", Types.STRUCT, "FIXTURE_ITEM%ROWTYPE",
				connection -> connection.createStruct("FIXTURE_ITEM%ROWTYPE", item(7)));

		assertThat(echo.getAttributes()).hasSize(4);
		assertThat(echo.getAttributes()[0]).isEqualTo(BigDecimal.valueOf(7));
	}

	@Test
	@DisplayName("the table name without %ROWTYPE does not resolve")
	void table_name_alone_does_not_resolve() {
		assertThatThrownBy(() -> call("ECHO_ROWTYPE", Types.STRUCT, "FIXTURE_ITEM",
				connection -> connection.createStruct("FIXTURE_ITEM%ROWTYPE", item(7))))
				.hasMessageContaining("ORA-17060");
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

		assertThat(echo.getBaseTypeName()).isEqualTo("\"PLSQL_TEST\".\"FIXTURE_ITEM%ROWTYPE\"");
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
		assertThatThrownBy(() -> call("ECHO_RECORDS", Types.ARRAY, "MINI_ITEM_LIST",
				connection -> records(connection, 10, 32)))
				.hasMessageContaining("ORA-17074");
	}

	@FunctionalInterface
	private interface Argument {
		Object create(Connection connection) throws SQLException;
	}

	/** {@code ? = call FIXTURE_COMPOSITES.<routine>(?)}, the result registered with {@code typeName}. */
	@SuppressWarnings("unchecked")
	private static <T> T call(String routine, int sqlType, String typeName, Argument argument) throws SQLException {
		try (Connection connection = DATA_SOURCE.getConnection();
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
			elements[i] = connection.createStruct("FIXTURE_ITEM%ROWTYPE", item(ids[i]));
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
