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
final class TestDatabase {

	private static OracleUnderTest oracle;

	private static HikariDataSource pool;

	private TestDatabase() {
	}

	private static final String IMAGE = System.getProperty("oracle.database.image",
			"gvenzl/oracle-free:23.26.3-slim-faststart");

	/** The pool of the test user, as an application has it: not wrapped. */
	static synchronized DataSource pool() {
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

	static HikariDataSource newPool(String user) {
		HikariDataSource dataSource = new HikariDataSource();
		dataSource.setJdbcUrl(oracle.jdbcUrl());
		dataSource.setUsername(user);
		dataSource.setPassword(OracleUnderTest.PASSWORD);
		dataSource.setMaximumPoolSize(3);
		return dataSource;
	}

	static void execute(DataSource dataSource, String... statements) {
		try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
			for (String sql : statements) {
				statement.execute(sql);
			}
		} catch (SQLException e) {
			throw new IllegalStateException(e);
		}
	}

	static OracleUnderTest oracle() {
		pool();
		return oracle;
	}

	private static void createFixtures() {
		execute(pool, "CREATE TABLE FREIGHT_ITEM (ID NUMBER PRIMARY KEY, UF VARCHAR2(2))",
				"""
				CREATE OR REPLACE PACKAGE FREIGHTS AS
				  SUBTYPE money IS NUMBER(10,2);
				  FUNCTION calculate(p_origin freight_item.uf%TYPE, p_destination VARCHAR2 DEFAULT 'SP') RETURN money;
				  FUNCTION calculate(p_weight NUMBER) RETURN NUMBER;
				  PROCEDURE quote(p_uf IN VARCHAR2, p_value OUT money);
				  FUNCTION label(p_day DATE) RETURN VARCHAR2;
				  FUNCTION real_one RETURN VARCHAR2;
				  FUNCTION calls RETURN PLS_INTEGER;
				  FUNCTION total(p_origin VARCHAR2, p_destination VARCHAR2) RETURN NUMBER;
				  FUNCTION count_items(p_uf VARCHAR2) RETURN NUMBER;
				END FREIGHTS;""",
				"""
				CREATE OR REPLACE PACKAGE BODY FREIGHTS AS
				  g_calls PLS_INTEGER := 0;

				  -- private, used by the real routines
				  FUNCTION base_rate(p_uf VARCHAR2) RETURN NUMBER IS
				  BEGIN
				    RETURN CASE p_uf WHEN 'CE' THEN 50 ELSE 10 END;
				  END base_rate;

				  FUNCTION calculate(p_origin freight_item.uf%TYPE, p_destination VARCHAR2 DEFAULT 'SP') RETURN money IS
				    l_value money;
				  BEGIN
				    g_calls := g_calls + 1;
				    l_value := base_rate(p_destination) * 1.5;
				    RETURN l_value;
				  END calculate;

				  FUNCTION calculate(p_weight NUMBER) RETURN NUMBER IS
				  BEGIN
				    g_calls := g_calls + 1;
				    RETURN p_weight * 2;
				  END calculate;

				  PROCEDURE quote(p_uf IN VARCHAR2, p_value OUT money) AS
				  BEGIN
				    p_value := base_rate(p_uf);
				  END;

				  FUNCTION label(p_day DATE) RETURN VARCHAR2 IS
				  BEGIN
				    RETURN TO_CHAR(p_day, 'YYYY-MM-DD');
				  END label;

				  FUNCTION real_one RETURN VARCHAR2 IS BEGIN RETURN 'REAL'; END;

				  FUNCTION calls RETURN PLS_INTEGER IS BEGIN RETURN g_calls; END calls;

				  -- calls another routine of the same package
				  FUNCTION total(p_origin VARCHAR2, p_destination VARCHAR2) RETURN NUMBER IS
				  BEGIN
				    RETURN 100 + calculate(p_origin, p_destination);
				  END total;

				  -- reads the schema's data, as most routines do
				  FUNCTION count_items(p_uf VARCHAR2) RETURN NUMBER IS
				    l_count NUMBER;
				  BEGIN
				    SELECT COUNT(*) INTO l_count FROM freight_item WHERE uf = p_uf;
				    RETURN l_count;
				  END count_items;
				END FREIGHTS;""",
				"""
				CREATE OR REPLACE PACKAGE CHECKOUT AS
				  FUNCTION total(p_origin VARCHAR2, p_destination VARCHAR2) RETURN NUMBER;
				END CHECKOUT;""",
				"""
				CREATE OR REPLACE PACKAGE BODY CHECKOUT AS
				  FUNCTION total(p_origin VARCHAR2, p_destination VARCHAR2) RETURN NUMBER IS
				  BEGIN
				    RETURN 100 + freights.calculate(p_origin, p_destination);
				  END total;
				END CHECKOUT;""",
				"""
				BEGIN
				  DBMS_DDL.CREATE_WRAPPED(q'[
				    CREATE OR REPLACE PACKAGE WRAPPED_ONE AS FUNCTION secret RETURN NUMBER; END WRAPPED_ONE;]');
				  DBMS_DDL.CREATE_WRAPPED(q'[
				    CREATE OR REPLACE PACKAGE BODY WRAPPED_ONE AS
				      FUNCTION secret RETURN NUMBER IS BEGIN RETURN 42; END secret;
				    END WRAPPED_ONE;]');
				END;""");
	}
}
