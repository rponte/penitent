package io.github.rponte.penitent.integration;

import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;

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

	private static final OracleUnderTest ORACLE = OracleUnderTest.start(System.getProperty("oracle.database.image",
			"gvenzl/oracle-free:23.26.3-slim-faststart"));

	protected static final DataSource DATA_SOURCE;

	/** The major version of the database, such as 23 for Oracle 23ai and 21 for Oracle 21c. */
	protected static final int DATABASE_MAJOR_VERSION;

	static {
		createOwnerSchema();

		Flyway.configure()
				.dataSource(ORACLE.jdbcUrl(), "plsql_owner", OracleUnderTest.PASSWORD)
				.locations("classpath:db/owner")
				.placeholderReplacement(false)
				.load()
				.migrate();

		ORACLE.executeAsAdmin("CREATE PUBLIC SYNONYM FIXTURE_OWNER_PUBLIC FOR plsql_owner.FIXTURE_OF_OWNER");

		Flyway.configure()
				.dataSource(ORACLE.jdbcUrl(), OracleUnderTest.USER, OracleUnderTest.PASSWORD)
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
		ORACLE.executeAsAdmin(
				"CREATE USER plsql_owner IDENTIFIED BY \"" + OracleUnderTest.PASSWORD + "\" QUOTA UNLIMITED ON "
						+ ORACLE.defaultTablespace(),
				"GRANT CREATE SESSION, CREATE TABLE, CREATE PROCEDURE, CREATE TYPE TO plsql_owner");
	}

	protected static HikariDataSource newPool() {
		HikariDataSource dataSource = new HikariDataSource();
		dataSource.setJdbcUrl(ORACLE.jdbcUrl());
		dataSource.setUsername(OracleUnderTest.USER);
		dataSource.setPassword(OracleUnderTest.PASSWORD);
		dataSource.setMaximumPoolSize(5);
		return dataSource;
	}

	protected static <T> T pkg(Class<T> type) {
		return PlsqlPackages.create(type, DATA_SOURCE);
	}
}
