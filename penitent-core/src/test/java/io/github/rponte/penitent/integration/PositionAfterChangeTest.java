package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zaxxer.hikari.HikariDataSource;

import io.github.rponte.penitent.PlsqlOut;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.exception.PlsqlExecutionException;

/**
 * Binding by position, after the routine changed while the application runs (#159). The Java order is matched to
 * the routine's as it was when the proxy described it, and the call names each parameter as the dictionary named it
 * then: {@code F(P_A => ?, P_B => ?)}. So a parameter inserted in the middle, or two that swap places, still get the
 * values the method was written for, where a call by position, {@code F(?, ?)}, sent them to other parameters with
 * no error. A renamed parameter fails safely once, and the routine is described again. A proxy that describes the
 * routine after the change matches the new order: only binding by name protects there.
 */
class PositionAfterChangeTest extends OracleIntegrationTest {

	@PlsqlPackage("CHG_POS")
	interface Sig {
		BigDecimal f(BigDecimal pA, BigDecimal pB);
	}

	@PlsqlPackage(value = "DBMS_APPLICATION_INFO", schema = "SYS")
	interface ApplicationInfo {
		void setModule(String moduleName, String actionName);

		void readModule(PlsqlOut<String> moduleName, PlsqlOut<String> actionName);
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

	/** F as {@code spec}, returning {@code p_a - p_b} whatever else it takes. */
	private static void f(String parameters) {
		ddl("CREATE OR REPLACE PACKAGE chg_pos AS FUNCTION f(" + parameters + ") RETURN NUMBER; END chg_pos;",
				"CREATE OR REPLACE PACKAGE BODY chg_pos AS FUNCTION f(" + parameters + ") RETURN NUMBER IS BEGIN"
						+ " RETURN p_a - p_b; END; END chg_pos;");
	}

	/** A proxy that described F as {@code f(p_a, p_b)}, on a pool of its own: a deploy then changes F. */
	private static Sig described(HikariDataSource pool) {
		f("p_a NUMBER, p_b NUMBER");
		Sig sig = PlsqlPackages.create(Sig.class, pool);
		assertThat(sig.f(TEN, THREE)).isEqualByComparingTo("7");
		return sig;
	}

	/** A DataSource that keeps the SQL of each statement prepared through it. */
	private static DataSource recording(DataSource real, List<String> sql) {
		return (DataSource) Proxy.newProxyInstance(PositionAfterChangeTest.class.getClassLoader(),
				new Class<?>[] { DataSource.class }, (p, m, a) -> {
					Object result = m.invoke(real, a);
					if (!(result instanceof Connection connection)) {
						return result;
					}
					return Proxy.newProxyInstance(PositionAfterChangeTest.class.getClassLoader(),
							new Class<?>[] { Connection.class }, (cp, cm, ca) -> {
								if (cm.getName().equals("prepareCall")) {
									sql.add((String) ca[0]);
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
	@DisplayName("a call by position names each parameter as the dictionary does: F(P_A => ?, P_B => ?)")
	void the_call_names_the_parameters() {
		f("p_a NUMBER, p_b NUMBER");
		List<String> sql = new CopyOnWriteArrayList<>();
		Sig sig = PlsqlPackages.create(Sig.class, recording(DATA_SOURCE, sql));

		assertThat(sig.f(TEN, THREE)).isEqualByComparingTo("7");
		assertThat(sql).contains("{? = call PLSQL_TEST.CHG_POS.F(P_A => ?, P_B => ?)}");
	}

	@Test
	@DisplayName("a parameter with DEFAULT inserted in the middle: the values still go where the method meant them")
	void inserted_in_the_middle() {
		try (HikariDataSource pool = newPool()) {
			Sig sig = described(pool);
			f("p_a NUMBER, p_new NUMBER DEFAULT 100, p_b NUMBER DEFAULT 0");

			// by position, F(?, ?) sent 3 to p_new and left p_b at 0: 10, with no error
			assertThat(sig.f(TEN, THREE)).isEqualByComparingTo("7");
		}
	}

	@Test
	@DisplayName("a VARCHAR2 with DEFAULT inserted in the middle: no implicit conversion of the value meant for p_b")
	void a_text_inserted_in_the_middle() {
		try (HikariDataSource pool = newPool()) {
			Sig sig = described(pool);
			f("p_a NUMBER, p_new VARCHAR2 DEFAULT 'x', p_b NUMBER DEFAULT 0");

			assertThat(sig.f(TEN, THREE)).isEqualByComparingTo("7");
		}
	}

	@Test
	@DisplayName("two parameters of the same type swap places: each still gets its own value")
	void swapped() {
		try (HikariDataSource pool = newPool()) {
			Sig sig = described(pool);
			f("p_b NUMBER, p_a NUMBER");

			// by position, F(?, ?) gave 3 - 10: -7, with no error
			assertThat(sig.f(TEN, THREE)).isEqualByComparingTo("7");
		}
	}

	@Test
	@DisplayName("a parameter renamed: the call fails, without running, and the next call uses the new signature")
	void renamed() {
		try (HikariDataSource pool = newPool()) {
			Sig sig = described(pool);
			ddl("CREATE OR REPLACE PACKAGE chg_pos AS FUNCTION f(p_a NUMBER, p_c NUMBER) RETURN NUMBER; END chg_pos;",
					"CREATE OR REPLACE PACKAGE BODY chg_pos AS FUNCTION f(p_a NUMBER, p_c NUMBER) RETURN NUMBER IS BEGIN"
							+ " RETURN p_a - p_c; END; END chg_pos;");

			// by position, F(?, ?) ran with the value in p_c, with no error
			assertThatThrownBy(() -> sig.f(TEN, THREE))
					.isInstanceOf(PlsqlExecutionException.class)
					.hasMessageContaining("PLS-00306");
			assertThat(sig.f(TEN, THREE)).isEqualByComparingTo("7");
		}
	}

	@Test
	@DisplayName("a proxy that describes F after the change matches the new order, as before: only binding by name"
			+ " protects there")
	void described_after_the_change() {
		try (HikariDataSource pool = newPool()) {
			f("p_a NUMBER, p_new NUMBER DEFAULT 100, p_b NUMBER DEFAULT 0");
			Sig sig = PlsqlPackages.create(Sig.class, pool);

			assertThat(sig.f(TEN, THREE)).as("3 goes to p_new, p_b keeps its DEFAULT").isEqualByComparingTo("10");
		}
	}

	@Test
	@DisplayName("a package of SYS, DBMS_APPLICATION_INFO, by position: its parameters' names, and an OUT")
	void a_package_of_sys() {
		ApplicationInfo info = PlsqlPackages.create(ApplicationInfo.class, DATA_SOURCE);
		info.setModule("penitent", "named");
		PlsqlOut<String> module = new PlsqlOut<>();
		PlsqlOut<String> action = new PlsqlOut<>();
		info.readModule(module, action);

		assertThat(module.get()).isEqualTo("penitent");
		assertThat(action.get()).isEqualTo("named");
	}
}
