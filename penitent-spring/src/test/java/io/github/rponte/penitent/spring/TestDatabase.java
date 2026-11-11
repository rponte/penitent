package io.github.rponte.penitent.spring;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One Oracle for all the test classes of this module, started on first use, with the
 * {@code FIXTURE_SPRING} package and its objects. Testcontainers stops it when the JVM ends.
 */
final class TestDatabase {

	/**
	 * Oracle 23ai by default, or another image given by the {@code oracle.database.image} property, as in
	 * penitent-core's {@code OracleIntegrationTest}.
	 */
	private static final String IMAGE = System.getProperty("oracle.database.image",
			"gvenzl/oracle-free:23.26.3-slim-faststart");

	private static final OracleContainer ORACLE = new OracleContainer(
			DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("gvenzl/oracle-free")) {
		// the Testcontainers module is for Oracle Free, whose database is FREEPDB1; Oracle XE, as 21c, calls it XEPDB1
		@Override
		public String getDatabaseName() {
			return IMAGE.contains("oracle-xe") ? "XEPDB1" : super.getDatabaseName();
		}
	}
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
					PROCEDURE insert_and_commit(p_id NUMBER);
					PROCEDURE insert_and_rollback(p_id NUMBER);
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
					PROCEDURE insert_and_commit(p_id NUMBER) IS
					BEGIN INSERT INTO FIXTURE_SPRING_ITEM VALUES (p_id); COMMIT; END;
					PROCEDURE insert_and_rollback(p_id NUMBER) IS
					BEGIN INSERT INTO FIXTURE_SPRING_ITEM VALUES (p_id); ROLLBACK; END;
				END;
				""");
	}
}
