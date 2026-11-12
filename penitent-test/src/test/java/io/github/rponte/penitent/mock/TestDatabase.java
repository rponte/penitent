package io.github.rponte.penitent.mock;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.junit.jupiter.api.Assumptions;

import com.zaxxer.hikari.HikariDataSource;

/**
 * One Oracle for all the test classes of this module, started on first use, with the prerequisites of the mocks
 * applied through {@link PlsqlMocks#prepare}, and the fixtures: a package whose spec uses {@code %TYPE}, a
 * {@code SUBTYPE}, a {@code DEFAULT}, an overload and an {@code OUT}, with state and a private function, and a
 * second package that calls the first.
 */
public final class TestDatabase {

	private static OracleUnderTest oracle;

	private static HikariDataSource pool;

	private TestDatabase() {
	}

	private static final String IMAGE = System.getProperty("oracle.database.image",
			"gvenzl/oracle-free:23.26.3-slim-faststart");

	/** The pool of the test user, as an application has it: not wrapped. */
	public static synchronized DataSource pool() {
		// measured on Oracle 23ai only, so far: architecture decision 0001 lists 21c and 19c as open questions
		Assumptions.assumeTrue(IMAGE.contains("oracle-free"), "the mocks are measured on Oracle 23ai only, so far");
		if (pool == null) {
			oracle = OracleUnderTest.start(IMAGE);
			pool = newPool(OracleUnderTest.USER);
			PlsqlMocks.prepare(newPool(oracle.adminUser()), pool, OracleUnderTest.USER);
			createFixtures();
		}
		return pool;
	}

	public static HikariDataSource newPool(String user) {
		HikariDataSource dataSource = new HikariDataSource();
		dataSource.setJdbcUrl(oracle.jdbcUrl());
		dataSource.setUsername(user);
		dataSource.setPassword(OracleUnderTest.PASSWORD);
		dataSource.setMaximumPoolSize(3);
		return dataSource;
	}

	public static void execute(DataSource dataSource, String... statements) {
		try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
			for (String sql : statements) {
				statement.execute(sql);
			}
		} catch (SQLException e) {
			throw new IllegalStateException(e);
		}
	}

	/** A pool of the database's administrator, for what only a DBA may run. */
	public static HikariDataSource adminPool() {
		return newPool(oracle().adminUser());
	}

	static OracleUnderTest oracle() {
		pool();
		return oracle;
	}

	/** The statements of {@code db/fixtures/freights.sql}, each ended by a line holding only a slash. */
	private static void createFixtures() {
		String script;
		try (java.io.InputStream in = TestDatabase.class.getResourceAsStream("/db/fixtures/freights.sql")) {
			script = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		} catch (java.io.IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
		java.util.List<String> statements = new java.util.ArrayList<>();
		StringBuilder current = new StringBuilder();
		for (String line : script.lines().toList()) {
			if (line.strip().equals("/")) {
				statements.add(current.toString().strip());
				current.setLength(0);
			} else if (!(current.isEmpty() && (line.isBlank() || line.strip().startsWith("--")))) {
				current.append(line).append('\n');
			}
		}
		execute(pool, statements.toArray(String[]::new));
	}
}
