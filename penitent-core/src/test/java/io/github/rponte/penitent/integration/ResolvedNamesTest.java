package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.exception.PlsqlMappingException;

/**
 * A proxy resolves its package's name once, not per method (#169), and keeps only a name it found: a package that did
 * not exist when a method was first described may be deployed later, and the next call finds it.
 */
class ResolvedNamesTest extends OracleIntegrationTest {

	@PlsqlPackage("RESOLVED_LATER")
	interface Later {
		BigDecimal twice(BigDecimal pValue);

		BigDecimal thrice(BigDecimal pValue);
	}

	private static void ddl(String... statements) {
		try (Connection c = DATA_SOURCE.getConnection(); Statement s = c.createStatement()) {
			for (String sql : statements) {
				s.execute(sql);
			}
		} catch (SQLException e) {
			throw new IllegalStateException(e);
		}
	}

	@Test
	@DisplayName("a package deployed after a call found it missing is found by the next call, of any method")
	void package_deployed_later() {
		drop();
		Later later = PlsqlPackages.create(Later.class, DATA_SOURCE);
		assertThatThrownBy(() -> later.twice(BigDecimal.ONE)).isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("RESOLVED_LATER does not exist");

		ddl(spec("resolved_later"), body("resolved_later"));
		try {
			assertThat(later.twice(BigDecimal.ONE)).isEqualByComparingTo("2");
			assertThat(later.thrice(BigDecimal.ONE)).isEqualByComparingTo("3");
		} finally {
			drop();
		}
	}

	@Test
	@DisplayName("a synonym deployed after a call found the name missing is followed by the next call")
	void synonym_deployed_later() {
		drop();
		ddl(spec("resolved_target"), body("resolved_target"));
		Later later = PlsqlPackages.create(Later.class, DATA_SOURCE);
		assertThatThrownBy(() -> later.twice(BigDecimal.ONE)).isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("RESOLVED_LATER does not exist");

		ddl("CREATE SYNONYM resolved_later FOR resolved_target");
		try {
			assertThat(later.twice(BigDecimal.ONE)).isEqualByComparingTo("2");
			assertThat(later.thrice(BigDecimal.ONE)).isEqualByComparingTo("3");
		} finally {
			drop();
		}
	}

	private static String spec(String name) {
		return "CREATE OR REPLACE PACKAGE " + name + """
				 AS
				  FUNCTION twice(p_value NUMBER) RETURN NUMBER;
				  FUNCTION thrice(p_value NUMBER) RETURN NUMBER;
				END;""";
	}

	private static String body(String name) {
		return "CREATE OR REPLACE PACKAGE BODY " + name + """
				 AS
				  FUNCTION twice(p_value NUMBER) RETURN NUMBER IS BEGIN RETURN p_value * 2; END;
				  FUNCTION thrice(p_value NUMBER) RETURN NUMBER IS BEGIN RETURN p_value * 3; END;
				END;""";
	}

	private static void drop() {
		for (String drop : new String[] { "DROP SYNONYM resolved_later", "DROP PACKAGE resolved_later",
				"DROP PACKAGE resolved_target" }) {
			ddl("BEGIN EXECUTE IMMEDIATE '" + drop + "'; EXCEPTION WHEN OTHERS THEN NULL; END;");
		}
	}
}
