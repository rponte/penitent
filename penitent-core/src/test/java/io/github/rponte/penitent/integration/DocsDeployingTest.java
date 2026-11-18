package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.SQLExceptionOverride;

import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.exception.PlsqlExecutionException;

/**
 * The examples of "Changing PL/SQL while the application runs": a type or a package body changed under a pool of
 * HikariCP, and the pool's own tools to recover, each with a pool of one connection, so that the next call shows
 * whether the connection was replaced.
 */
class DocsDeployingTest extends OracleIntegrationTest {

	// snippet: deploying/stale-type-eviction
	/** Evicts a connection whose driver keeps an object type a deploy has changed. */
	public static class StaleTypeEviction implements SQLExceptionOverride {

		public Override adjudicate(SQLException e) {
			// ORA-17049: the driver's old type; ORA-03101: the old type sent to the database
			return e.getErrorCode() == 17049 || e.getErrorCode() == 3101 ? Override.MUST_EVICT
					: Override.CONTINUE_EVICT;
		}
	}
	// end-snippet

	record Pair(BigDecimal a, BigDecimal b) {
	}

	record Triple(BigDecimal a, BigDecimal b, BigDecimal c) {
	}

	@PlsqlPackage("DEPLOY_PAIRS")
	interface Pairs {
		Pair echo(Pair value);
	}

	@PlsqlPackage("DEPLOY_PAIRS")
	interface Triples {
		Triple echo(Triple value);
	}

	@PlsqlPackage("DEPLOY_COUNTER")
	interface Counter {
		BigDecimal next();
	}

	private static final Pair PAIR = new Pair(BigDecimal.ONE, BigDecimal.TEN);
	private static final Triple TRIPLE = new Triple(BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO);

	private static void ddl(String... statements) {
		try (Connection c = DATA_SOURCE.getConnection(); Statement s = c.createStatement()) {
			for (String sql : statements) {
				s.execute(sql);
			}
		} catch (SQLException e) {
			throw new IllegalStateException(e);
		}
	}

	/** An object type of two attributes, and a package that echoes it. */
	private static void pairType() {
		ddl("BEGIN EXECUTE IMMEDIATE 'DROP PACKAGE deploy_pairs'; EXCEPTION WHEN OTHERS THEN NULL; END;",
				"BEGIN EXECUTE IMMEDIATE 'DROP TYPE deploy_pair_t FORCE'; EXCEPTION WHEN OTHERS THEN NULL; END;",
				"CREATE TYPE deploy_pair_t AS OBJECT (a NUMBER, b NUMBER)",
				"CREATE PACKAGE deploy_pairs AS FUNCTION echo(p_value deploy_pair_t) RETURN deploy_pair_t;"
						+ " END deploy_pairs;",
				"CREATE PACKAGE BODY deploy_pairs AS FUNCTION echo(p_value deploy_pair_t) RETURN deploy_pair_t IS"
						+ " BEGIN RETURN p_value; END; END deploy_pairs;");
	}

	private static void counter(int start) {
		ddl("CREATE OR REPLACE PACKAGE deploy_counter AS FUNCTION next RETURN NUMBER; END deploy_counter;",
				"CREATE OR REPLACE PACKAGE BODY deploy_counter AS g_count NUMBER := " + start + ";"
						+ " FUNCTION next RETURN NUMBER IS BEGIN g_count := g_count + 1; RETURN g_count; END;"
						+ " END deploy_counter;");
	}

	private static HikariDataSource pool(HikariConfig config) {
		try (HikariDataSource template = newPool()) {
			config.setJdbcUrl(template.getJdbcUrl());
			config.setUsername(template.getUsername());
			config.setPassword(template.getPassword());
		}
		config.setMaximumPoolSize(1);
		return new HikariDataSource(config);
	}

	/** The calls on one connection, which the library's close() leaves open: one transaction, as Spring would hold. */
	private static javax.sql.DataSource transactionOn(Connection connection) {
		Connection kept = (Connection) java.lang.reflect.Proxy.newProxyInstance(DocsDeployingTest.class.getClassLoader(),
				new Class<?>[] { Connection.class }, (proxy, method, args) -> {
					if (method.getName().equals("close")) {
						return null;
					}
					try {
						return method.invoke(connection, args);
					} catch (java.lang.reflect.InvocationTargetException e) {
						throw e.getCause();
					}
				});
		return (javax.sql.DataSource) java.lang.reflect.Proxy.newProxyInstance(DocsDeployingTest.class.getClassLoader(),
				new Class<?>[] { javax.sql.DataSource.class }, (proxy, method, args) -> switch (method.getName()) {
					case "getConnection" -> kept;
					case "hashCode" -> System.identityHashCode(proxy);
					case "equals" -> proxy == args[0];
					default -> null;
				});
	}

	@Test
	@DisplayName("a type changed: the driver keeps the old one, until StaleTypeEviction replaces the connection")
	void stale_type_eviction() {
		pairType();
		HikariConfig config = new HikariConfig();
		// snippet: deploying/exception-override
		config.setExceptionOverride(new StaleTypeEviction());
		// end-snippet
		try (HikariDataSource hikari = pool(config)) {
			assertThat(PlsqlPackages.create(Pairs.class, hikari).echo(PAIR)).isEqualTo(PAIR);
			ddl("ALTER TYPE deploy_pair_t ADD ATTRIBUTE (c NUMBER) CASCADE");

			assertThatThrownBy(() -> PlsqlPackages.create(Triples.class, hikari).echo(TRIPLE))
					.isInstanceOfSatisfying(PlsqlExecutionException.class, e -> assertThat(e.getCode()).isEqualTo(17049));
			assertThat(PlsqlPackages.create(Triples.class, hikari).echo(TRIPLE)).isEqualTo(TRIPLE);
		}
	}

	@Test
	@DisplayName("a type changed, without an override: the connection keeps the old type, until it is evicted")
	void soft_evict() {
		pairType();
		try (HikariDataSource hikari = pool(new HikariConfig())) {
			PlsqlPackages.create(Pairs.class, hikari).echo(PAIR);
			ddl("ALTER TYPE deploy_pair_t ADD ATTRIBUTE (c NUMBER) CASCADE");
			assertThatThrownBy(() -> PlsqlPackages.create(Triples.class, hikari).echo(TRIPLE))
					.isInstanceOfSatisfying(PlsqlExecutionException.class, e -> assertThat(e.getCode()).isEqualTo(17049));
			assertThatThrownBy(() -> PlsqlPackages.create(Triples.class, hikari).echo(TRIPLE))
					.as("HikariCP keeps the connection by itself").isInstanceOf(PlsqlExecutionException.class);

			// snippet: deploying/soft-evict
			hikari.getHikariPoolMXBean().softEvictConnections();
			// end-snippet

			assertThat(PlsqlPackages.create(Triples.class, hikari).echo(TRIPLE)).isEqualTo(TRIPLE);
		}
	}

	@Test
	@DisplayName("a body with state recompiled: ORA-04068 once on the connection, and then the new body")
	void package_state_discarded() {
		counter(0);
		try (HikariDataSource hikari = pool(new HikariConfig())) {
			Counter counter = PlsqlPackages.create(Counter.class, hikari);
			assertThat(counter.next()).isEqualByComparingTo("1");
			counter(100);

			assertThatThrownBy(counter::next)
					.isInstanceOfSatisfying(PlsqlExecutionException.class, e -> assertThat(e.getCode()).isEqualTo(4068));
			assertThat(counter.next()).isEqualByComparingTo("101");
		}
	}

	@Test
	@DisplayName("SESSION_EXIT_ON_PACKAGE_STATE_ERROR: the session ends, the whole transaction is undone, and the pool"
			+ " replaces the connection")
	void session_exit() throws SQLException {
		assumeTrue(DATABASE_MAJOR_VERSION >= 23, "measured on 23ai; 19c needs RU 19.23 and the privilege");
		ddl("BEGIN EXECUTE IMMEDIATE 'DROP TABLE deploy_log'; EXCEPTION WHEN OTHERS THEN NULL; END;",
				"CREATE TABLE deploy_log (id NUMBER)");
		counter(0);
		HikariConfig config = new HikariConfig();
		// snippet: deploying/session-exit
		config.setConnectionInitSql("ALTER SESSION SET SESSION_EXIT_ON_PACKAGE_STATE_ERROR = TRUE");
		// end-snippet
		try (HikariDataSource hikari = pool(config)) {
			assertThat(PlsqlPackages.create(Counter.class, hikari).next()).isEqualByComparingTo("1");
			counter(100);

			try (Connection connection = hikari.getConnection()) {
				connection.setAutoCommit(false);
				try (Statement statement = connection.createStatement()) {
					statement.execute("INSERT INTO deploy_log VALUES (1)");
				}
				Counter inTransaction = PlsqlPackages.create(Counter.class, transactionOn(connection));
				assertThatThrownBy(inTransaction::next)
						.isInstanceOfSatisfying(PlsqlExecutionException.class, e -> assertThat(e.getCode()).isEqualTo(28));
			}
			assertThat(PlsqlPackages.create(Counter.class, hikari).next()).isEqualByComparingTo("101");
		}
		try (Connection c = DATA_SOURCE.getConnection(); Statement s = c.createStatement();
				ResultSet rows = s.executeQuery("SELECT COUNT(*) FROM deploy_log")) {
			rows.next();
			assertThat(rows.getInt(1)).as("the row inserted before the call, undone with the session").isZero();
		}
	}
}
