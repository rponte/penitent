package io.github.rponte.penitent.mock.internal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a script may apply in the test's edition, as far as the statements alone tell, without a database: the
 * editions of the schema are checked against Oracle, in {@code ApplyScriptsTest}.
 */
class EditionScriptsTest {

	@Test
	@DisplayName("CREATE OR REPLACE of a view, a package, its body, a function, a procedure and a synonym is accepted")
	void accepted() {
		assertThatCode(() -> EditionScripts.requireAccepted(PlsqlScript.parse("""
				CREATE OR REPLACE VIEW a_v AS SELECT 1 AS id FROM dual;
				CREATE OR REPLACE EDITIONABLE VIEW b_v AS SELECT 1 AS id FROM dual;
				CREATE OR REPLACE SYNONYM items FOR freight_item;
				CREATE OR REPLACE PACKAGE p AS FUNCTION f RETURN NUMBER; END p;
				/
				CREATE OR REPLACE PACKAGE BODY p AS FUNCTION f RETURN NUMBER IS BEGIN RETURN 1; END f; END p;
				/
				CREATE OR REPLACE FUNCTION one RETURN NUMBER IS BEGIN RETURN 1; END;
				/
				CREATE OR REPLACE PROCEDURE nothing IS BEGIN NULL; END;
				/
				""", "test.sql"))).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("what the edition cannot hold is refused, naming the file, the statement and its line")
	void refused() {
		for (String statement : List.of("CREATE TABLE scratch (id NUMBER);",
				"INSERT INTO freight_item (id, uf) VALUES (1, 'SP');", "DROP VIEW freight_items_v;",
				"ALTER VIEW freight_items_v COMPILE;", "CREATE VIEW plain_v AS SELECT 1 AS id FROM dual;",
				"CREATE OR REPLACE TYPE point_t AS OBJECT (x NUMBER);\n/",
				"CREATE OR REPLACE TRIGGER t BEFORE INSERT ON freight_item BEGIN NULL; END;\n/",
				"CREATE OR REPLACE PUBLIC SYNONYM items FOR freight_item;", "BEGIN NULL; END;\n/")) {
			assertThatThrownBy(() -> EditionScripts.requireAccepted(PlsqlScript.parse("-- first line\n" + statement,
					"test.sql")))
					.as(statement)
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("test.sql, statement 1 (line 2)")
					.hasMessageContaining(statement.lines().findFirst().orElseThrow().replaceAll(";$", ""))
					.hasMessageContaining("CREATE OR REPLACE");
		}
	}
}
