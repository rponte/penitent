package io.github.rponte.penitent.integration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.testcontainers.oracle.OracleContainer;

import com.zaxxer.hikari.HikariDataSource;

import io.github.rponte.penitent.PlsqlPackages;

/**
 * Starts a single Oracle, shared by all test classes, and applies the fixtures.
 *
 * <p>No Spring on purpose: that is how the library has to work for those who do not use Spring.
 * The user is a regular one, without DBA, so a test that passes here does not depend on any special
 * privilege. The pool is required: without it, one connection per call exhausts the Oracle listener
 * ({@code ORA-12516}).
 *
 * <p>The tests live outside the library's package so they only see the public API, like any
 * consumer.
 */
abstract class OracleIntegrationTest {

	private static final OracleContainer ORACLE = new OracleContainer("gvenzl/oracle-free:23.26.3-slim-faststart")
			.withUsername("plsql_test")
			.withPassword("plsql_test");

	protected static final DataSource DATA_SOURCE;

	static {
		// no stop: Testcontainers tears the container down when the JVM exits
		ORACLE.start();

		createOwnerSchema();

		Flyway.configure()
				.dataSource(ORACLE.getJdbcUrl(), "plsql_owner", ORACLE.getPassword())
				.locations("classpath:db/owner")
				.placeholderReplacement(false)
				.load()
				.migrate();

		executeAsSystem("CREATE PUBLIC SYNONYM FIXTURE_OWNER_PUBLIC FOR plsql_owner.FIXTURE_OF_OWNER");

		Flyway.configure()
				.dataSource(ORACLE.getJdbcUrl(), ORACLE.getUsername(), ORACLE.getPassword())
				.locations("classpath:db/fixtures")
				.placeholderReplacement(false)
				.load()
				.migrate();

		DATA_SOURCE = newPool();
	}

	/** The owner of {@code db/owner}: its packages are only visible through grants, as in production. */
	private static void createOwnerSchema() {
		executeAsSystem(
				"CREATE USER plsql_owner IDENTIFIED BY \"" + ORACLE.getPassword() + "\" QUOTA UNLIMITED ON USERS",
				"GRANT CREATE SESSION, CREATE TABLE, CREATE PROCEDURE, CREATE TYPE TO plsql_owner");
	}

	private static void executeAsSystem(String... commands) {
		try (Connection connection = DriverManager.getConnection(ORACLE.getJdbcUrl(), "system", ORACLE.getPassword());
				Statement statement = connection.createStatement()) {
			for (String command : commands) {
				statement.execute(command);
			}
		} catch (SQLException e) {
			throw new IllegalStateException("failed to prepare the test database", e);
		}
	}

	protected static HikariDataSource newPool() {
		HikariDataSource dataSource = new HikariDataSource();
		dataSource.setJdbcUrl(ORACLE.getJdbcUrl());
		dataSource.setUsername(ORACLE.getUsername());
		dataSource.setPassword(ORACLE.getPassword());
		dataSource.setMaximumPoolSize(5);
		return dataSource;
	}

	protected static <T> T pkg(Class<T> type) {
		return PlsqlPackages.create(type, DATA_SOURCE);
	}
}
