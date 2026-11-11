package io.github.rponte.penitent.integration;

import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.testcontainers.oracle.OracleContainer;

import com.zaxxer.hikari.HikariDataSource;

import oracle.jdbc.OracleDriver;

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

	/**
	 * The database the tests run against: Oracle 23ai by default, or another image given by the
	 * {@code oracle.database.image} property, such as {@code gvenzl/oracle-xe:21-slim-faststart} for Oracle 21c.
	 */
	private static final String IMAGE = System.getProperty("oracle.database.image",
			"gvenzl/oracle-free:23.26.3-slim-faststart");

	private static final OracleContainer ORACLE = new OracleContainer(
			org.testcontainers.utility.DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("gvenzl/oracle-free")) {
		// the Testcontainers module is for Oracle Free, whose database is FREEPDB1; Oracle XE, as 21c, calls it XEPDB1
		@Override
		public String getDatabaseName() {
			return IMAGE.contains("oracle-xe") ? "XEPDB1" : super.getDatabaseName();
		}
	}
			.withUsername("plsql_test")
			.withPassword("plsql_test");

	protected static final DataSource DATA_SOURCE;

	/** The major version of the database, such as 23 for Oracle 23ai and 21 for Oracle 21c. */
	protected static final int DATABASE_MAJOR_VERSION;

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
		try (Connection connection = DATA_SOURCE.getConnection()) {
			DATABASE_MAJOR_VERSION = connection.getMetaData().getDatabaseMajorVersion();
		} catch (SQLException e) {
			throw new IllegalStateException("failed to read the version of the test database", e);
		}
	}

	/**
	 * Skips the test on a database before Oracle 23ai, which has BOOLEAN only in PL/SQL: not as a column,
	 * as the attribute of an object type or in a query, as {@code select true}. The fixtures that need it
	 * are created only on 23ai, in {@code V3__additional_types.sql}.
	 */
	protected static void assumeSqlBoolean() {
		assumeTrue(DATABASE_MAJOR_VERSION >= 23, "SQL BOOLEAN exists only from Oracle 23ai on");
	}

	/**
	 * Whether driver 23 or later runs against a database before Oracle 23ai: a null PL/SQL BOOLEAN that
	 * comes back, as a return value, an {@code OUT} or a block's {@code :result}, then reads as true or
	 * false. See {@link NullBooleanOutputTest}.
	 */
	protected static boolean nullBooleanOutputMisread() {
		return new OracleDriver().getMajorVersion() >= 23 && DATABASE_MAJOR_VERSION < 23;
	}

	/** Skips what follows in the test, from a null BOOLEAN that comes back, where {@link #nullBooleanOutputMisread()}. */
	protected static void assumeNullBooleanOutputReadable() {
		assumeFalse(nullBooleanOutputMisread(),
				"driver 23 reads a null BOOLEAN output from a database before 23ai as true or false");
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
