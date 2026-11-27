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

import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.PlsqlRoutine;
import io.github.rponte.penitent.PlsqlSchema;
import io.github.rponte.penitent.exception.PlsqlExecutionException;
import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.exception.PlsqlRoutineRaisedException;

/**
 * Routines a migration left invalid, and object types it replaced, while proxies of them exist (#184, A2 and A3).
 *
 * <p>A package, or a standalone routine, that failed to compile has no rows in {@code ALL_ARGUMENTS} nor in
 * {@code ALL_PROCEDURES}, and keeps none once what it needs is back, until it compiles. A new proxy, which reads its
 * routines there, cannot tell it from one that does not exist; and, not calling it, never makes Oracle compile it. It
 * says it is invalid, and how to compile it. On 19c, {@code DBMS_UTILITY.NAME_RESOLVE}, which resolves its name,
 * compiles it as it resolves it, measured in #184, and the call works.
 *
 * <p>A type replaced with {@code FORCE} makes the next call of a proxy that sent one fail with {@code ORA-21700}: the
 * session's object of the old type is gone. It is not the routine's error: the call says what happened, and the next
 * one works.
 */
class InvalidObjectsTest extends OracleIntegrationTest {

	record Value(BigDecimal a) {
	}

	@PlsqlPackage("INV_PKG")
	interface Pkg {
		BigDecimal plus(BigDecimal pValue);

		Value echo(Value pValue);
	}

	@PlsqlSchema
	interface Standalone {
		@PlsqlRoutine("INV_FN")
		BigDecimal invFn(Value pValue);
	}

	@PlsqlPackage("REPL_PKG")
	interface Replaced {
		Value echo(Value pValue);
	}

	private static void ddl(String... statements) {
		try (Connection c = DATA_SOURCE.getConnection(); Statement s = c.createStatement()) {
			for (String sql : statements) {
				try {
					s.execute(sql);
				} catch (SQLException e) {
					// a compilation that fails, on purpose, or a drop of what is not there
				}
			}
		} catch (SQLException e) {
			throw new IllegalStateException(e);
		}
	}

	private static int arguments(String object) {
		try (Connection c = DATA_SOURCE.getConnection(); Statement s = c.createStatement();
				ResultSet rs = s.executeQuery("select count(*) from all_arguments where owner = user and (package_name = '"
						+ object + "' or package_name is null and object_name = '" + object + "')")) {
			rs.next();
			return rs.getInt(1);
		} catch (SQLException e) {
			throw new IllegalStateException(e);
		}
	}

	@Test
	@DisplayName("a package left invalid, its routines gone from the dictionary: refused as invalid, with how to compile it")
	void invalid_package() {
		ddl("DROP PACKAGE inv_pkg", "DROP TYPE inv_t FORCE", "CREATE TYPE inv_t AS OBJECT (a NUMBER)",
				"CREATE PACKAGE inv_pkg AS FUNCTION plus(p_value NUMBER) RETURN NUMBER;"
						+ " FUNCTION echo(p_value inv_t) RETURN inv_t; END;",
				"CREATE PACKAGE BODY inv_pkg AS FUNCTION plus(p_value NUMBER) RETURN NUMBER IS BEGIN RETURN p_value + 1;"
						+ " END; FUNCTION echo(p_value inv_t) RETURN inv_t IS BEGIN RETURN p_value; END; END;",
				// a migration drops the type, a compilation fails, the type comes back
				"DROP TYPE inv_t FORCE", "ALTER PACKAGE inv_pkg COMPILE", "CREATE TYPE inv_t AS OBJECT (a NUMBER)");
		try {
			assumeTrue(arguments("INV_PKG") == 0, "the failed compilation left no rows in ALL_ARGUMENTS");
			Pkg pkg = PlsqlPackages.create(Pkg.class, DATA_SOURCE);
			if (DATABASE_MAJOR_VERSION == 19) {
				assertThat(pkg.plus(BigDecimal.ONE)).isEqualByComparingTo("2");
				return;
			}
			assertThatThrownBy(() -> pkg.plus(BigDecimal.ONE)).isInstanceOf(PlsqlMappingException.class)
					.hasMessageContaining("package PLSQL_TEST.INV_PKG is INVALID")
					.hasMessageContaining("ALTER PACKAGE PLSQL_TEST.INV_PKG COMPILE");
			assertThatThrownBy(() -> PlsqlPackages.validate(PlsqlPackages.create(Pkg.class, DATA_SOURCE)))
					.isInstanceOf(PlsqlMappingException.class).hasMessageContaining("INV_PKG is INVALID");

			ddl("ALTER PACKAGE inv_pkg COMPILE");
			assertThat(pkg.plus(BigDecimal.ONE)).isEqualByComparingTo("2");
		} finally {
			ddl("DROP PACKAGE inv_pkg", "DROP TYPE inv_t FORCE");
		}
	}

	@Test
	@DisplayName("a standalone function left invalid: refused as invalid, with how to compile it")
	void invalid_standalone_routine() {
		ddl("DROP FUNCTION inv_fn", "DROP TYPE inv_fn_t FORCE", "CREATE TYPE inv_fn_t AS OBJECT (a NUMBER)",
				"CREATE FUNCTION inv_fn(p_value inv_fn_t) RETURN NUMBER IS BEGIN RETURN p_value.a; END;",
				"DROP TYPE inv_fn_t FORCE", "ALTER FUNCTION inv_fn COMPILE", "CREATE TYPE inv_fn_t AS OBJECT (a NUMBER)");
		try {
			assumeTrue(arguments("INV_FN") == 0, "the failed compilation left no rows in ALL_ARGUMENTS");
			Standalone standalone = PlsqlPackages.create(Standalone.class, DATA_SOURCE);
			if (DATABASE_MAJOR_VERSION == 19) {
				return;
			}
			assertThatThrownBy(() -> standalone.invFn(new Value(BigDecimal.ONE)))
					.isInstanceOf(PlsqlMappingException.class)
					.hasMessageContaining("standalone routine PLSQL_TEST.INV_FN is INVALID")
					.hasMessageContaining("ALTER FUNCTION PLSQL_TEST.INV_FN COMPILE");
		} finally {
			ddl("DROP FUNCTION inv_fn", "DROP TYPE inv_fn_t FORCE");
		}
	}

	@Test
	@DisplayName("a type replaced with FORCE: ORA-21700 is not the routine's error, says what happened, and the next call works")
	void type_replaced() {
		ddl("DROP PACKAGE repl_pkg", "DROP TYPE repl_t FORCE", "CREATE TYPE repl_t AS OBJECT (a NUMBER)",
				"CREATE PACKAGE repl_pkg AS FUNCTION echo(p_value repl_t) RETURN repl_t; END;",
				"CREATE PACKAGE BODY repl_pkg AS FUNCTION echo(p_value repl_t) RETURN repl_t IS BEGIN RETURN p_value;"
						+ " END; END;");
		try {
			Replaced replaced = PlsqlPackages.create(Replaced.class, DATA_SOURCE);
			assertThat(replaced.echo(new Value(BigDecimal.ONE))).isEqualTo(new Value(BigDecimal.ONE));

			ddl("CREATE OR REPLACE TYPE repl_t FORCE AS OBJECT (a NUMBER)");

			assertThatThrownBy(() -> replaced.echo(new Value(BigDecimal.TEN)))
					.isInstanceOf(PlsqlExecutionException.class).isNotInstanceOf(PlsqlRoutineRaisedException.class)
					.hasMessageContaining("ORA-21700")
					.hasMessageContaining("replaced in the database")
					.satisfies(e -> assertThat(((PlsqlExecutionException) e).getCode()).isEqualTo(21700));
			assertThat(replaced.echo(new Value(BigDecimal.TEN))).isEqualTo(new Value(BigDecimal.TEN));
		} finally {
			ddl("DROP PACKAGE repl_pkg", "DROP TYPE repl_t FORCE");
		}
	}
}
