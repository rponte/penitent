package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zaxxer.hikari.HikariDataSource;

import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.exception.PlsqlExecutionException;

/**
 * A routine that changed while the application runs, whose call fails without running, with {@code PLS-00306}, but
 * whose method still fits the new signature, as a parameter renamed when binding by position (#165). The routine is
 * described again at once, and the exception says so, keeping Oracle's error, its code and its type, so that the
 * failing call does not read like a broken package. When the routine read again is the same, the error is the
 * routine's own, from its dynamic PL/SQL, and stays as Oracle says it; and the dictionary is not read again on every
 * call that fails so.
 */
class ChangedAndDescribedAgainTest extends OracleIntegrationTest {

	@PlsqlPackage("CHG_AGAIN")
	interface Sig {
		BigDecimal f(BigDecimal pA, BigDecimal pB);
	}

	private static final BigDecimal TEN = BigDecimal.TEN;
	private static final BigDecimal THREE = BigDecimal.valueOf(3);

	private static void ddl(String... statements) {
		try (Connection c = DATA_SOURCE.getConnection(); Statement s = c.createStatement()) {
			for (String sql : statements) {
				s.execute(sql);
			}
		} catch (SQLException e) {
			throw new IllegalStateException(e);
		}
	}

	private static void f(String parameters, String body) {
		ddl("CREATE OR REPLACE PACKAGE chg_again AS FUNCTION f(" + parameters + ") RETURN NUMBER; END chg_again;",
				"CREATE OR REPLACE PACKAGE BODY chg_again AS FUNCTION f(" + parameters + ") RETURN NUMBER IS " + body
						+ " END; END chg_again;");
	}

	/** A DataSource that counts the describes, the queries of ALL_ARGUMENTS, made through it. */
	private static DataSource counting(DataSource real, AtomicInteger describes) {
		return (DataSource) Proxy.newProxyInstance(ChangedAndDescribedAgainTest.class.getClassLoader(),
				new Class<?>[] { DataSource.class }, (p, m, a) -> {
					Object result = m.invoke(real, a);
					if (!(result instanceof Connection connection)) {
						return result;
					}
					return Proxy.newProxyInstance(ChangedAndDescribedAgainTest.class.getClassLoader(),
							new Class<?>[] { Connection.class }, (cp, cm, ca) -> {
								if (cm.getName().equals("prepareStatement")
										&& String.valueOf(ca[0]).toLowerCase(java.util.Locale.ROOT).contains("all_arguments")) {
									describes.incrementAndGet();
								}
								try {
									return cm.invoke(connection, ca);
								} catch (java.lang.reflect.InvocationTargetException e) {
									throw e.getCause();
								}
							});
				});
	}

	@Test
	@DisplayName("a parameter renamed, by position: the failing call says the routine changed, was described again and"
			+ " did not run, with the signatures before and after; Oracle's error stays its cause and its code")
	void renamed_by_position() {
		try (HikariDataSource pool = newPool()) {
			f("p_a NUMBER, p_b NUMBER", "BEGIN RETURN p_a - p_b;");
			Sig sig = PlsqlPackages.create(Sig.class, pool);
			assertThat(sig.f(TEN, THREE)).isEqualByComparingTo("7");
			f("p_a NUMBER, p_c NUMBER", "BEGIN RETURN p_a - p_c;");

			assertThatThrownBy(() -> sig.f(TEN, THREE))
					.isInstanceOfSatisfying(PlsqlExecutionException.class, e -> assertThat(e.getCode()).isEqualTo(6550))
					.hasMessageStartingWith("PLSQL_TEST.CHG_AGAIN.F changed since this proxy described it, and was"
							+ " described again: the call did not run, and the next one uses the new signature."
							+ " Parameters before: [P_A NUMBER, P_B NUMBER]; now: [P_A NUMBER, P_C NUMBER].")
					.hasMessageContaining("PLS-00306")
					.cause().hasMessageContaining("PLS-00306");
			assertThat(sig.f(TEN, THREE)).isEqualByComparingTo("7");
		}
	}

	@Test
	@DisplayName("PLS-00306 from the routine's own dynamic PL/SQL, the signature unchanged: Oracle's error as it is, and"
			+ " the dictionary read again once, not on every call that fails so")
	void the_routines_own_error() {
		try (HikariDataSource pool = newPool()) {
			f("p_a NUMBER, p_b NUMBER",
					"l NUMBER; BEGIN EXECUTE IMMEDIATE 'BEGIN :r := chg_again.f(1); END;' USING OUT l; RETURN l;");
			AtomicInteger describes = new AtomicInteger();
			Sig sig = PlsqlPackages.create(Sig.class, counting(pool, describes));
			assertThatThrownBy(() -> sig.f(TEN, THREE)).hasMessageContaining("PLS-00306");
			assertThatThrownBy(() -> sig.f(TEN, THREE)).hasMessageContaining("PLS-00306");
			int afterTheFirstCheck = describes.get();

			for (int i = 0; i < 10; i++) {
				assertThatThrownBy(() -> sig.f(TEN, THREE))
						.isInstanceOfSatisfying(PlsqlExecutionException.class,
								e -> assertThat(e.getCode()).isEqualTo(6550))
						.hasMessageContaining("PLS-00306")
						.hasMessageNotContaining("changed since");
			}
			assertThat(describes.get()).as("describes after 10 more failing calls").isEqualTo(afterTheFirstCheck);
		}
	}

	@Test
	@DisplayName("a real change after the routine's own errors is still noticed, once the short while has passed")
	void a_change_after_the_routines_own_errors() throws InterruptedException {
		try (HikariDataSource pool = newPool()) {
			f("p_a NUMBER, p_b NUMBER",
					"l NUMBER; BEGIN EXECUTE IMMEDIATE 'BEGIN :r := chg_again.f(1); END;' USING OUT l; RETURN l;");
			Sig sig = PlsqlPackages.create(Sig.class, pool);
			assertThatThrownBy(() -> sig.f(TEN, THREE)).hasMessageContaining("PLS-00306");
			assertThatThrownBy(() -> sig.f(TEN, THREE)).hasMessageContaining("PLS-00306");
			f("p_a NUMBER, p_c NUMBER", "BEGIN RETURN p_a - p_c;");
			Thread.sleep(1_100);

			assertThatThrownBy(() -> sig.f(TEN, THREE)).hasMessageContaining("changed since this proxy described it");
			assertThat(sig.f(TEN, THREE)).isEqualByComparingTo("7");
		}
	}
}
