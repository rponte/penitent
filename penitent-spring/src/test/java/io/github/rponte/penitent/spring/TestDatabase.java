package io.github.rponte.penitent.spring;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.oracle.OracleContainer;

/**
 * One Oracle for all the test classes of this module, started on first use, with the
 * {@code FIXTURE_SPRING} package and its objects. Testcontainers stops it when the JVM ends.
 */
final class TestDatabase {

	private static final OracleContainer ORACLE = new OracleContainer("gvenzl/oracle-free:23.26.3-slim-faststart")
			.withUsername("plsql_test")
			.withPassword("plsql_test");

	private static DataSource dataSource;

	private TestDatabase() {
	}

	static synchronized DataSource dataSource() {
		if (dataSource == null) {
			ORACLE.start();
			DataSource started = newDataSource();
			createFixtures(new JdbcTemplate(started));
			dataSource = started;
		}
		return dataSource;
	}

	/** Another DataSource for the same database, for tests that need two of them. */
	static DataSource newDataSource() {
		return new DriverManagerDataSource(ORACLE.getJdbcUrl(), ORACLE.getUsername(), ORACLE.getPassword());
	}

	static void delete(int itemId) {
		new JdbcTemplate(dataSource()).update("delete from fixture_spring_item where id = ?", itemId);
	}

	private static void createFixtures(JdbcTemplate jdbc) {
		jdbc.execute("CREATE TABLE FIXTURE_SPRING_ITEM (ID NUMBER PRIMARY KEY)");
		jdbc.execute("CREATE TYPE ADDRESS_T AS OBJECT (STREET VARCHAR2(60), NUM NUMBER(6))");
		jdbc.execute("CREATE TYPE NAME_LIST_T AS TABLE OF VARCHAR2(60)");
		jdbc.execute("""
				CREATE OR REPLACE PACKAGE FIXTURE_SPRING AS
					FUNCTION session_id RETURN NUMBER;
					FUNCTION count_items RETURN NUMBER;
					PROCEDURE insert_item(p_id NUMBER);
					PROCEDURE raise_error;
					PROCEDURE sql_invalid;
					PROCEDURE lock_table;
					FUNCTION echo_address(p_value ADDRESS_T) RETURN ADDRESS_T;
					FUNCTION echo_names(p_value NAME_LIST_T) RETURN NAME_LIST_T;
				END;
				""");
		jdbc.execute("""
				CREATE OR REPLACE PACKAGE BODY FIXTURE_SPRING AS
					FUNCTION session_id RETURN NUMBER IS BEGIN RETURN SYS_CONTEXT('USERENV', 'SID'); END;
					FUNCTION count_items RETURN NUMBER IS l_total NUMBER;
					BEGIN SELECT COUNT(*) INTO l_total FROM FIXTURE_SPRING_ITEM; RETURN l_total; END;
					PROCEDURE insert_item(p_id NUMBER) IS BEGIN INSERT INTO FIXTURE_SPRING_ITEM VALUES (p_id); END;
					PROCEDURE raise_error IS BEGIN RAISE_APPLICATION_ERROR(-20100, 'rule violated'); END;
					PROCEDURE sql_invalid IS BEGIN EXECUTE IMMEDIATE 'BEGIN does_not_exist; END;'; END;
					PROCEDURE lock_table IS BEGIN LOCK TABLE FIXTURE_SPRING_ITEM IN EXCLUSIVE MODE NOWAIT; END;
					FUNCTION echo_address(p_value ADDRESS_T) RETURN ADDRESS_T IS BEGIN RETURN p_value; END;
					FUNCTION echo_names(p_value NAME_LIST_T) RETURN NAME_LIST_T IS BEGIN RETURN p_value; END;
				END;
				""");
	}
}
