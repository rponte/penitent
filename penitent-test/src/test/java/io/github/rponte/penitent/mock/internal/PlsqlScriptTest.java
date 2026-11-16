package io.github.rponte.penitent.mock.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * How a script is split into statements, and what of it is malformed, without a database: the rules of SQL*Plus and
 * of Flyway's Oracle parser. PL/SQL ends on a line holding only a slash; SQL ends with a semicolon or such a line;
 * strings and comments never end nor classify a statement.
 */
class PlsqlScriptTest {

	private static List<PlsqlScript.Statement> read(String script) {
		return PlsqlScript.parse(script, "test.sql");
	}

	private static List<String> sql(String script) {
		return read(script).stream().map(PlsqlScript.Statement::sql).toList();
	}

	@Nested
	class Splitting {

		@Test
		@DisplayName("SQL ends with a semicolon, which is removed; PL/SQL ends on a line with a slash, keeping its own")
		void sql_and_plsql() {
			assertThat(sql("""
					CREATE OR REPLACE VIEW freight_items_v AS SELECT 1 AS id FROM dual;

					CREATE OR REPLACE PACKAGE BODY checkout AS
					  FUNCTION total RETURN NUMBER IS
					  BEGIN
					    RETURN 999;
					  END total;
					END checkout;
					/
					""")).containsExactly(
					"CREATE OR REPLACE VIEW freight_items_v AS SELECT 1 AS id FROM dual",
					"""
					CREATE OR REPLACE PACKAGE BODY checkout AS
					  FUNCTION total RETURN NUMBER IS
					  BEGIN
					    RETURN 999;
					  END total;
					END checkout;""");
		}

		@Test
		@DisplayName("SQL may end on a line with a slash too, and a slash left after a semicolon is dropped")
		void slash_after_sql() {
			assertThat(sql("""
					CREATE OR REPLACE VIEW a_v AS SELECT 1 AS id FROM dual
					/
					CREATE OR REPLACE VIEW b_v AS SELECT 2 AS id FROM dual;
					/
					""")).containsExactly("CREATE OR REPLACE VIEW a_v AS SELECT 1 AS id FROM dual",
					"CREATE OR REPLACE VIEW b_v AS SELECT 2 AS id FROM dual");
		}

		@Test
		@DisplayName("a slash that is not alone on its line is SQL, as a division")
		void division() {
			assertThat(sql("CREATE OR REPLACE VIEW half_v AS SELECT 10 / 2 AS half FROM dual;"))
					.containsExactly("CREATE OR REPLACE VIEW half_v AS SELECT 10 / 2 AS half FROM dual");
		}

		@Test
		@DisplayName("the last statement may end with the script, with no terminator")
		void end_of_script() {
			assertThat(sql("CREATE OR REPLACE VIEW a_v AS SELECT 1 AS id FROM dual"))
					.containsExactly("CREATE OR REPLACE VIEW a_v AS SELECT 1 AS id FROM dual");
		}

		@Test
		@DisplayName("a semicolon, a slash or a CREATE inside a string, an alternative quote or a quoted name splits nothing")
		void strings() {
			assertThat(sql("""
					CREATE OR REPLACE VIEW texts_v AS
					  SELECT 'a;b' AS a, 'it''s; CREATE OR REPLACE VIEW x' AS b, q'[c;
					/
					d]' AS c, "semi;colon" AS d FROM dual;
					""")).singleElement().asString()
					.startsWith("CREATE OR REPLACE VIEW texts_v AS")
					.endsWith("\"semi;colon\" AS d FROM dual");
		}

		@Test
		@DisplayName("a semicolon, a slash line or a CREATE inside a comment splits nothing")
		void comments_inside() {
			assertThat(sql("""
					CREATE OR REPLACE VIEW a_v AS
					  SELECT 1 AS id -- not the end; CREATE OR REPLACE VIEW x
					  /* nor this;
					/
					  */ FROM dual;
					""")).singleElement().asString().endsWith("*/ FROM dual");
		}

		@Test
		@DisplayName("comments before a statement are dropped; inside it, kept, optimizer hints included")
		void comments_kept() {
			assertThat(sql("""
					-- replaces the view
					/* with two rows */
					CREATE OR REPLACE VIEW a_v AS
					  SELECT /*+ NO_MERGE */ 1 AS id FROM dual -- the first
					  UNION ALL SELECT 2 FROM dual;
					""")).containsExactly("""
					CREATE OR REPLACE VIEW a_v AS
					  SELECT /*+ NO_MERGE */ 1 AS id FROM dual -- the first
					  UNION ALL SELECT 2 FROM dual""");
		}

		@Test
		@DisplayName("a package body with an initialization section, which has a BEGIN with no END of its own")
		void initialization_section() {
			assertThat(sql("""
					CREATE OR REPLACE PACKAGE BODY counters AS
					  g_count NUMBER;
					  FUNCTION next RETURN NUMBER IS BEGIN g_count := g_count + 1; RETURN g_count; END next;
					BEGIN
					  g_count := 0;
					END counters;
					/
					CREATE OR REPLACE VIEW a_v AS SELECT 1 AS id FROM dual;
					""")).hasSize(2);
		}

		@Test
		@DisplayName("a view with a PL/SQL function in its WITH clause is PL/SQL: it ends on a slash line")
		void view_with_function() {
			assertThat(sql("""
					CREATE OR REPLACE VIEW labels_v AS
					  WITH FUNCTION label(p_uf VARCHAR2) RETURN VARCHAR2 IS BEGIN RETURN 'UF ' || p_uf; END;
					  SELECT label(uf) AS label FROM freight_item
					/
					""")).singleElement().asString().endsWith("SELECT label(uf) AS label FROM freight_item");
		}

		@Test
		@DisplayName("what confused Flyway's block counting confuses nothing here: conditional compilation, IF, CASE, LOOP")
		void no_block_counting() {
			assertThat(sql("""
					CREATE OR REPLACE PACKAGE BODY feature AS
					  FUNCTION on RETURN BOOLEAN IS
					  BEGIN
					    $IF DBMS_DB_VERSION.VERSION >= 23 $THEN
					      RETURN TRUE;
					    $ELSE
					      RETURN FALSE;
					    $END
					  END on;
					  PROCEDURE run IS
					    l_kind VARCHAR2(10);
					  BEGIN
					    IF on THEN l_kind := CASE WHEN 1 = 1 THEN 'new' END; END IF;
					    FOR i IN 1 .. 2 LOOP NULL; END LOOP;
					  END run;
					END feature;
					/
					CREATE OR REPLACE VIEW a_v AS SELECT 1 AS id FROM dual;
					""")).hasSize(2);
		}

		@Test
		@DisplayName("each statement knows its number and the line it starts on")
		void positions() {
			List<PlsqlScript.Statement> statements = read("""
					-- header
					CREATE OR REPLACE VIEW a_v AS SELECT 1 AS id FROM dual;

					CREATE OR REPLACE VIEW b_v AS
					  SELECT 2 AS id FROM dual;
					""");
			assertThat(statements).extracting(PlsqlScript.Statement::number, PlsqlScript.Statement::line)
					.containsExactly(org.assertj.core.groups.Tuple.tuple(1, 2), org.assertj.core.groups.Tuple.tuple(2, 4));
		}
	}

	@Nested
	class Classifying {

		@Test
		@DisplayName("the type, the owner and the name of what each statement creates")
		void objects() {
			assertThat(read("""
					CREATE OR REPLACE VIEW freight_items_v AS SELECT 1 AS id FROM dual;
					CREATE OR REPLACE EDITIONABLE VIEW plsql_test.labels_v AS SELECT 1 AS id FROM dual;
					CREATE OR REPLACE FORCE VIEW "Mixed_v" AS SELECT 1 AS id FROM dual;
					CREATE OR REPLACE SYNONYM items FOR freight_item;
					CREATE OR REPLACE PACKAGE checkout AS FUNCTION total RETURN NUMBER; END checkout;
					/
					CREATE OR REPLACE PACKAGE BODY checkout AS FUNCTION total RETURN NUMBER IS BEGIN RETURN 1; END; END;
					/
					CREATE OR REPLACE FUNCTION one RETURN NUMBER IS BEGIN RETURN 1; END;
					/
					CREATE OR REPLACE PROCEDURE nothing IS BEGIN NULL; END;
					/
					""")).extracting(PlsqlScript.Statement::type, PlsqlScript.Statement::owner, PlsqlScript.Statement::name)
					.containsExactly(
							org.assertj.core.groups.Tuple.tuple("VIEW", null, "FREIGHT_ITEMS_V"),
							org.assertj.core.groups.Tuple.tuple("VIEW", "PLSQL_TEST", "LABELS_V"),
							org.assertj.core.groups.Tuple.tuple("VIEW", null, "Mixed_v"),
							org.assertj.core.groups.Tuple.tuple("SYNONYM", null, "ITEMS"),
							org.assertj.core.groups.Tuple.tuple("PACKAGE", null, "CHECKOUT"),
							org.assertj.core.groups.Tuple.tuple("PACKAGE BODY", null, "CHECKOUT"),
							org.assertj.core.groups.Tuple.tuple("FUNCTION", null, "ONE"),
							org.assertj.core.groups.Tuple.tuple("PROCEDURE", null, "NOTHING"));
		}
	}

	@Nested
	class Refusing {

		@Test
		@DisplayName("SQL*Plus commands, which are not SQL, naming the command")
		void sql_plus() {
			for (String command : List.of("SET DEFINE OFF", "PROMPT replacing the view", "REM a remark", "SHOW ERRORS",
					"WHENEVER SQLERROR EXIT FAILURE")) {
				assertThatThrownBy(() -> read(command + "\nCREATE OR REPLACE VIEW a_v AS SELECT 1 AS id FROM dual;"))
						.as(command)
						.isInstanceOf(IllegalStateException.class)
						.hasMessageContaining(command.split(" ")[0])
						.hasMessageContaining("SQL*Plus");
			}
		}

		@Test
		@DisplayName("a missing ; after SQL, which would run two statements as one")
		void missing_semicolon() {
			assertThatThrownBy(() -> read("""
					CREATE OR REPLACE VIEW a_v AS SELECT 1 AS id FROM dual
					CREATE OR REPLACE VIEW b_v AS SELECT 2 AS id FROM dual;
					"""))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("statement 1")
					.hasMessageContaining("line 2");
		}

		@Test
		@DisplayName("a missing slash after PL/SQL, which would run two statements as one")
		void missing_slash() {
			assertThatThrownBy(() -> read("""
					CREATE OR REPLACE PACKAGE BODY checkout AS
					  FUNCTION total RETURN NUMBER IS BEGIN RETURN 999; END total;
					END checkout;
					CREATE OR REPLACE VIEW a_v AS SELECT 1 AS id FROM dual;
					"""))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("statement 1")
					.hasMessageContaining("line 4")
					.hasMessageContaining("/");
		}

		@Test
		@DisplayName("a script with no statement, only comments or blank lines")
		void empty() {
			assertThatThrownBy(() -> read("-- nothing here\n/* nor here */\n\n"))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("test.sql")
					.hasMessageContaining("no statement");
		}

		@Test
		@DisplayName("a string or a comment left open")
		void unterminated() {
			assertThatThrownBy(() -> read("CREATE OR REPLACE VIEW a_v AS SELECT 'open FROM dual;"))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("line 1");
			assertThatThrownBy(() -> read("CREATE OR REPLACE VIEW a_v AS SELECT 1 /* open FROM dual;"))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("line 1");
		}
	}
}
