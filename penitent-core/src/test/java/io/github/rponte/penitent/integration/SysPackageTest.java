package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.exception.PlsqlMappingException;

/** The library reads any package the user can see, not only those in their own schema. */
class SysPackageTest extends OracleIntegrationTest {

	@PlsqlPackage(value = "DBMS_APPLICATION_INFO", schema = "SYS")
	interface DbmsApplicationInfo {
		void setModule(String moduleName, String actionName);
	}

	@PlsqlPackage(value = "DBMS_DEBUG_JDWP", schema = "SYS")
	interface DbmsDebugJdwp {
		void connectTcp(String host, int port);
	}

	@Test
	@DisplayName("DBMS_APPLICATION_INFO.SET_MODULE changes the session context")
	void sys_package() throws SQLException {
		try (Connection connection = DATA_SOURCE.getConnection()) {
			PlsqlPackages.create(DbmsApplicationInfo.class, alwaysIn(connection)).setModule("PLSQL", "TEST");

			try (PreparedStatement select = connection.prepareStatement(
					"select sys_context('userenv','module') from dual");
					ResultSet rs = select.executeQuery()) {
				rs.next();
				assertThat(rs.getString(1)).isEqualTo("PLSQL");
			}
		}
	}

	@Test
	@DisplayName("CONNECT_TCP declares 8 parameters, 6 with DEFAULT: calling with 2 passes validation")
	void arity_range_in_a_sys_package() {
		// the connection itself fails, because no debugger is listening; what is asserted is that
		// the database is what rejects it, not the library validation
		assertThatThrownBy(() -> pkg(DbmsDebugJdwp.class).connectTcp("localhost", 1))
				.isNotInstanceOf(PlsqlMappingException.class);
	}

	/** Session state is only visible on the same connection, so {@code close} becomes a no-op. */
	private static DataSource alwaysIn(Connection connection) {
		Connection withoutClosing = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
				new Class<?>[] { Connection.class }, (proxy, method, args) -> {
					if (method.getName().equals("close")) {
						return null;
					}
					try {
						return method.invoke(connection, args);
					} catch (InvocationTargetException e) {
						throw e.getCause();
					}
				});
		return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
				new Class<?>[] { DataSource.class }, (proxy, method, args) -> {
					if (method.getName().equals("getConnection")) {
						return withoutClosing;
					}
					throw new UnsupportedOperationException(method.getName());
				});
	}
}
