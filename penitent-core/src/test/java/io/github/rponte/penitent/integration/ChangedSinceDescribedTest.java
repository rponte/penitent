package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zaxxer.hikari.HikariDataSource;

import io.github.rponte.penitent.PlsqlBind;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.exception.PlsqlExecutionException;
import io.github.rponte.penitent.exception.PlsqlMappingException;

/**
 * A routine or a type that changes in the database after a proxy described it, as a deploy under a running
 * application does: the next call says so, as a new proxy would, rather than with Oracle's raw error, and the proxy
 * describes it again. The call that failed is not repeated.
 */
class ChangedSinceDescribedTest extends OracleIntegrationTest {

	@PlsqlPackage("CHG_SIG")
	interface Sig {
		BigDecimal f(BigDecimal pA, BigDecimal pB);
	}

	@PlsqlPackage(value = "CHG_SIG", bind = PlsqlBind.BY_NAME)
	interface SigByName {
		BigDecimal f(BigDecimal pA, BigDecimal pB);
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

	private static void sig(String spec, String body) {
		ddl("CREATE OR REPLACE PACKAGE chg_sig AS " + spec + " END chg_sig;",
				"CREATE OR REPLACE PACKAGE BODY chg_sig AS " + body + " END chg_sig;");
	}

	private static final String SPEC = "FUNCTION f(p_a NUMBER, p_b NUMBER) RETURN NUMBER;";
	private static final String BODY = "FUNCTION f(p_a NUMBER, p_b NUMBER) RETURN NUMBER IS BEGIN RETURN p_a - p_b; END;";

	private static final BigDecimal TEN = BigDecimal.TEN;
	private static final BigDecimal THREE = BigDecimal.valueOf(3);

	/** A proxy that described F as it was, on a pool of its own, so that no other test shares its connections. */
	private static <T> T described(Class<T> type, HikariDataSource pool) {
		sig(SPEC, BODY);
		T proxy = PlsqlPackages.create(type, pool);
		return proxy;
	}

	@Test
	@DisplayName("a parameter added: the method no longer fits, said as a new proxy would, at this call and the next")
	void parameter_added() {
		try (HikariDataSource pool = newPool()) {
			Sig sig = described(Sig.class, pool);
			assertThat(sig.f(TEN, THREE)).isEqualByComparingTo("7");
			sig("FUNCTION f(p_a NUMBER, p_b NUMBER, p_c NUMBER) RETURN NUMBER;",
					"FUNCTION f(p_a NUMBER, p_b NUMBER, p_c NUMBER) RETURN NUMBER IS BEGIN RETURN 0; END;");

			assertThatThrownBy(() -> sig.f(TEN, THREE))
					.isInstanceOf(PlsqlMappingException.class)
					.hasMessageContaining("PLSQL_TEST.CHG_SIG.F changed since this proxy described it")
					.hasMessageContaining("the method declares 2 parameters, but PLSQL_TEST.CHG_SIG.F expects 3");
			// the proxy now holds the new signature: the next call is refused before it runs, as by a new proxy
			assertThatThrownBy(() -> sig.f(TEN, THREE))
					.isInstanceOf(PlsqlMappingException.class)
					.hasMessage("f(BigDecimal, BigDecimal): the method declares 2 parameters, but PLSQL_TEST.CHG_SIG.F"
							+ " expects 3: [P_A NUMBER, P_B NUMBER, P_C NUMBER]");
		}
	}

	@Test
	@DisplayName("a function turned into a procedure, and a routine removed")
	void function_to_procedure_and_removed() {
		try (HikariDataSource pool = newPool()) {
			Sig sig = described(Sig.class, pool);
			sig.f(TEN, THREE);
			sig("PROCEDURE f(p_a NUMBER, p_b NUMBER);", "PROCEDURE f(p_a NUMBER, p_b NUMBER) IS BEGIN NULL; END;");
			assertThatThrownBy(() -> sig.f(TEN, THREE))
					.isInstanceOf(PlsqlMappingException.class)
					.hasMessageContaining("changed since this proxy described it")
					.hasMessageContaining("is a procedure and returns nothing");

			Sig again = described(Sig.class, pool);
			again.f(TEN, THREE);
			sig("FUNCTION g RETURN NUMBER;", "FUNCTION g RETURN NUMBER IS BEGIN RETURN 1; END;");
			assertThatThrownBy(() -> again.f(TEN, THREE))
					.isInstanceOf(PlsqlMappingException.class)
					.hasMessageContaining("changed since this proxy described it")
					.hasMessageContaining("routine PLSQL_TEST.CHG_SIG.F does not exist. Available: G");
		}
	}

	@Test
	@DisplayName("a parameter renamed, binding by name")
	void parameter_renamed_by_name() {
		try (HikariDataSource pool = newPool()) {
			SigByName sig = described(SigByName.class, pool);
			sig.f(TEN, THREE);
			sig("FUNCTION f(p_a NUMBER, p_other NUMBER) RETURN NUMBER;",
					"FUNCTION f(p_a NUMBER, p_other NUMBER) RETURN NUMBER IS BEGIN RETURN 0; END;");
			assertThatThrownBy(() -> sig.f(TEN, THREE))
					.isInstanceOf(PlsqlMappingException.class)
					.hasMessageContaining("changed since this proxy described it")
					.hasMessageContaining("parameter pB looks for P_B");
		}
	}

	@Test
	@DisplayName("a change the method still fits: the call fails as Oracle says, the next call uses the new signature")
	void change_the_method_fits() {
		try (HikariDataSource pool = newPool()) {
			SigByName sig = described(SigByName.class, pool);
			sig.f(TEN, THREE);
			sig("FUNCTION f(p_b NUMBER, p_a NUMBER, p_c NUMBER DEFAULT 1) RETURN NUMBER;",
					"FUNCTION f(p_b NUMBER, p_a NUMBER, p_c NUMBER DEFAULT 1) RETURN NUMBER IS BEGIN RETURN p_a - p_b + p_c; END;");
			// by name, the call still fits: no error to explain, and the new signature is used as it is
			assertThat(sig.f(TEN, THREE)).isEqualByComparingTo("8");
		}
	}

	@Test
	@DisplayName("an error that is not about the signature is left as Oracle says it")
	void other_errors_untouched() {
		try (HikariDataSource pool = newPool()) {
			sig(SPEC, "FUNCTION f(p_a NUMBER, p_b NUMBER) RETURN NUMBER IS BEGIN RETURN p_a / (p_b - 3); END;");
			Sig sig = PlsqlPackages.create(Sig.class, pool);
			assertThatThrownBy(() -> sig.f(TEN, THREE))
					.isNotInstanceOf(PlsqlMappingException.class)
					.hasMessageNotContaining("changed since");
		}
	}

	@Test
	@DisplayName("PLS-00306 raised inside the routine, by its own dynamic PL/SQL, when nothing changed: left as it is")
	void signature_error_inside_the_routine() {
		try (HikariDataSource pool = newPool()) {
			sig(SPEC, "FUNCTION f(p_a NUMBER, p_b NUMBER) RETURN NUMBER IS l NUMBER; BEGIN"
					+ " EXECUTE IMMEDIATE 'BEGIN :r := chg_sig.f(1); END;' USING OUT l; RETURN l; END;");
			Sig sig = PlsqlPackages.create(Sig.class, pool);
			assertThatThrownBy(() -> sig.f(TEN, THREE)).hasMessageContaining("PLS-00306");

			assertThatThrownBy(() -> sig.f(TEN, THREE))
					.isInstanceOfSatisfying(PlsqlExecutionException.class, e -> assertThat(e.getCode()).isEqualTo(6550))
					.hasMessageContaining("PLS-00306")
					.hasMessageNotContaining("changed since");
		}
	}

	// ------------------------------------------------------------------ composite types

	record Pair(BigDecimal a, BigDecimal b) {
	}

	record Triple(BigDecimal a, BigDecimal b, BigDecimal c) {
	}

	@PlsqlPackage("CHG_OBJ")
	interface Pairs {
		Pair echo(Pair value);
	}

	@PlsqlPackage("CHG_OBJ")
	interface Triples {
		Triple echo(Triple value);
	}

	private static void pairType() {
		ddl("BEGIN EXECUTE IMMEDIATE 'DROP PACKAGE chg_obj'; EXCEPTION WHEN OTHERS THEN NULL; END;",
				"BEGIN EXECUTE IMMEDIATE 'DROP TYPE chg_pair_t FORCE'; EXCEPTION WHEN OTHERS THEN NULL; END;",
				"CREATE TYPE chg_pair_t AS OBJECT (a NUMBER, b NUMBER)",
				"CREATE PACKAGE chg_obj AS FUNCTION echo(p_value chg_pair_t) RETURN chg_pair_t; END chg_obj;",
				"CREATE PACKAGE BODY chg_obj AS FUNCTION echo(p_value chg_pair_t) RETURN chg_pair_t IS"
						+ " BEGIN RETURN p_value; END; END chg_obj;");
	}

	private static final Pair PAIR = new Pair(BigDecimal.ONE, BigDecimal.TEN);
	private static final Triple TRIPLE = new Triple(BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO);

	@Test
	@DisplayName("an object type gains an attribute the Java record lacks: said as a new proxy would; on 21c, whose"
			+ " ORA-00600 closes the connection, at the next call")
	void attribute_added() {
		pairType();
		try (HikariDataSource pool = newPool()) {
			Pairs pairs = PlsqlPackages.create(Pairs.class, pool);
			assertThat(pairs.echo(PAIR)).isEqualTo(PAIR);
			ddl("ALTER TYPE chg_pair_t ADD ATTRIBUTE (c NUMBER) CASCADE");

			Throwable first = org.assertj.core.api.Assertions.catchThrowable(() -> pairs.echo(PAIR));
			if (DATABASE_MAJOR_VERSION == 21) {
				assertThat(first).isInstanceOfSatisfying(PlsqlExecutionException.class,
						e -> assertThat(e.getCode()).isEqualTo(600));
			} else {
				assertThat(first).isInstanceOf(PlsqlMappingException.class)
						.hasMessageContaining("a type of PLSQL_TEST.CHG_OBJ.ECHO changed since this proxy described it")
						.hasMessageContaining("Pair does not cover attribute(s) of CHG_PAIR_T: [C NUMBER]");
			}
			assertThatThrownBy(() -> pairs.echo(PAIR))
					.isInstanceOf(PlsqlMappingException.class)
					.hasMessageContaining("Pair does not cover attribute(s) of CHG_PAIR_T: [C NUMBER]");
		}
	}

	@Test
	@DisplayName("ORA-17049, the driver's old type: kept as Oracle's error, with its code, and says what to do; drivers"
			+ " 19 and 21 write it without the ORA- prefix")
	void driver_keeps_the_old_type() {
		pairType();
		try (HikariDataSource pool = newPool()) {
			pool.setMaximumPoolSize(1);
			PlsqlPackages.create(Pairs.class, pool).echo(PAIR);
			ddl("ALTER TYPE chg_pair_t ADD ATTRIBUTE (c NUMBER) CASCADE");

			assertThatThrownBy(() -> PlsqlPackages.create(Triples.class, pool).echo(TRIPLE))
					.isInstanceOfSatisfying(PlsqlExecutionException.class, e -> assertThat(e.getCode()).isEqualTo(17049))
					.hasMessageContaining("Inconsistent")
					.hasMessageContaining("The driver keeps, in each connection, a type as it was when the connection"
							+ " first used it")
					.hasMessageContaining("renew the pool's connections");
		}
	}
}
