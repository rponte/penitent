package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.PlsqlRoutine;
import io.github.rponte.penitent.PlsqlSchema;
import io.github.rponte.penitent.exception.PlsqlExecutionException;

/**
 * A routine named through a synonym is called through the synonym (#184, A4), as PL/SQL calls it: pointed elsewhere,
 * as a blue/green deploy does, the next call goes where it points now. The proxy describes the routine at the
 * synonym's target, since the dictionary only knows real names, and binds types by their real names, since the driver
 * keeps a type's description per connection by name; a call that fails as if the routine changed resolves the synonym
 * again, as #165 does. Each package returns another value, so that a test shows which one ran.
 */
class SynonymCallTest extends OracleIntegrationTest {

	@PlsqlPackage("SYN_SVC")
	interface Svc {
		BigDecimal f(BigDecimal pA);
	}

	@PlsqlPackage(value = "SYN_SVC", schema = "plsql_test")
	interface SvcInSchema {
		BigDecimal f(BigDecimal pA);
	}

	record Rec(BigDecimal a) {
	}

	@PlsqlPackage("SYN_SVC")
	interface RecordSvc {
		BigDecimal echoRec(Rec pValue);
	}

	@PlsqlPackage("SYN_PUB_SVC")
	interface PublicSvc {
		BigDecimal f(BigDecimal pA);
	}

	@PlsqlSchema
	interface Standalone {
		@PlsqlRoutine("SYN_FN")
		BigDecimal fn(BigDecimal pA);
	}

	/** A package whose f returns {@code p_a * 100 + version}, its parameter named {@code parameter}. */
	private static String[] pkg(String name, String parameter, int version) {
		return new String[] {
				"CREATE OR REPLACE PACKAGE " + name + " AS FUNCTION f(" + parameter + " NUMBER) RETURN NUMBER; END;",
				"CREATE OR REPLACE PACKAGE BODY " + name + " AS FUNCTION f(" + parameter + " NUMBER) RETURN NUMBER IS"
						+ " BEGIN RETURN " + parameter + " * 100 + " + version + "; END; END;" };
	}

	private static void as(Connection c, String... statements) {
		try (Statement s = c.createStatement()) {
			for (String sql : statements) {
				s.execute(sql);
			}
		} catch (SQLException e) {
			throw new IllegalStateException(e);
		}
	}

	private static void user(String... statements) {
		try (Connection c = DATA_SOURCE.getConnection()) {
			as(c, statements);
		} catch (SQLException e) {
			throw new IllegalStateException(e);
		}
	}

	private static void admin(String... statements) {
		try (Connection c = adminConnection()) {
			as(c, statements);
		} catch (SQLException e) {
			throw new IllegalStateException(e);
		}
	}

	private static void quietly(Runnable drop) {
		try {
			drop.run();
		} catch (IllegalStateException e) {
			// not there
		}
	}

	@AfterEach
	void drop() {
		for (String sql : new String[] { "DROP SYNONYM syn_svc", "DROP SYNONYM syn_fn", "DROP PACKAGE syn_green",
				"DROP PACKAGE syn_other", "DROP FUNCTION syn_fn_green" }) {
			quietly(() -> user(sql));
		}
		for (String sql : new String[] { "DROP PUBLIC SYNONYM syn_pub_svc", "DROP PACKAGE plsql_owner.syn_blue",
				"DROP FUNCTION plsql_owner.syn_fn_blue" }) {
			quietly(() -> admin(sql));
		}
	}

	private static void blueAndGreen() {
		admin(pkg("plsql_owner.syn_blue", "p_a", 1));
		admin("GRANT EXECUTE ON plsql_owner.syn_blue TO plsql_test");
		user(pkg("syn_green", "p_a", 2));
	}

	@Test
	@DisplayName("a private synonym pointed elsewhere: the proxy's next call goes there, and on once the old one is gone")
	void private_synonym_pointed_elsewhere() {
		blueAndGreen();
		user("CREATE SYNONYM syn_svc FOR plsql_owner.syn_blue");
		Svc svc = PlsqlPackages.create(Svc.class, DATA_SOURCE);
		assertThat(svc.f(BigDecimal.ONE)).isEqualByComparingTo("101");

		user("CREATE OR REPLACE SYNONYM syn_svc FOR syn_green");
		assertThat(svc.f(BigDecimal.ONE)).isEqualByComparingTo("102");

		admin("DROP PACKAGE plsql_owner.syn_blue");
		assertThat(svc.f(BigDecimal.ONE)).isEqualByComparingTo("102");
	}

	@Test
	@DisplayName("with the schema declared: the same")
	void declared_schema() {
		blueAndGreen();
		user("CREATE SYNONYM syn_svc FOR plsql_owner.syn_blue");
		SvcInSchema svc = PlsqlPackages.create(SvcInSchema.class, DATA_SOURCE);
		assertThat(svc.f(BigDecimal.ONE)).isEqualByComparingTo("101");

		user("CREATE OR REPLACE SYNONYM syn_svc FOR syn_green");
		assertThat(svc.f(BigDecimal.ONE)).isEqualByComparingTo("102");
	}

	@Test
	@DisplayName("a public synonym pointed elsewhere: the same")
	void public_synonym_pointed_elsewhere() {
		blueAndGreen();
		admin("GRANT EXECUTE ON plsql_test.syn_green TO plsql_owner",
				"CREATE OR REPLACE PUBLIC SYNONYM syn_pub_svc FOR plsql_owner.syn_blue");
		PublicSvc svc = PlsqlPackages.create(PublicSvc.class, DATA_SOURCE);
		assertThat(svc.f(BigDecimal.ONE)).isEqualByComparingTo("101");

		admin("CREATE OR REPLACE PUBLIC SYNONYM syn_pub_svc FOR plsql_test.syn_green");
		assertThat(svc.f(BigDecimal.ONE)).isEqualByComparingTo("102");
	}

	@Test
	@DisplayName("a standalone function's synonym pointed elsewhere: the same")
	void standalone_synonym_pointed_elsewhere() {
		admin("CREATE OR REPLACE FUNCTION plsql_owner.syn_fn_blue(p_a NUMBER) RETURN NUMBER IS BEGIN RETURN p_a * 100 + 1;"
				+ " END;", "GRANT EXECUTE ON plsql_owner.syn_fn_blue TO plsql_test");
		user("CREATE OR REPLACE FUNCTION syn_fn_green(p_a NUMBER) RETURN NUMBER IS BEGIN RETURN p_a * 100 + 2; END;",
				"CREATE SYNONYM syn_fn FOR plsql_owner.syn_fn_blue");
		Standalone standalone = PlsqlPackages.create(Standalone.class, DATA_SOURCE);
		assertThat(standalone.fn(BigDecimal.ONE)).isEqualByComparingTo("101");

		user("CREATE OR REPLACE SYNONYM syn_fn FOR syn_fn_green");
		assertThat(standalone.fn(BigDecimal.ONE)).isEqualByComparingTo("102");
	}

	/** A package whose echo_rec takes its own RECORD, and returns {@code a * 100 + version}. */
	private static String[] recordPkg(String name, int version) {
		return new String[] {
				"CREATE OR REPLACE PACKAGE " + name + " AS TYPE rec IS RECORD (a NUMBER);"
						+ " FUNCTION echo_rec(p_value rec) RETURN NUMBER; END;",
				"CREATE OR REPLACE PACKAGE BODY " + name + " AS FUNCTION echo_rec(p_value rec) RETURN NUMBER IS"
						+ " BEGIN RETURN p_value.a * 100 + " + version + "; END; END;" };
	}

	@Test
	@DisplayName("a package RECORD, pointed elsewhere: the call fails once, since the RECORD is the old package's, then works")
	void package_record_pointed_elsewhere() {
		admin(recordPkg("plsql_owner.syn_blue", 1));
		admin("GRANT EXECUTE ON plsql_owner.syn_blue TO plsql_test");
		user(recordPkg("syn_green", 2));
		user("CREATE SYNONYM syn_svc FOR plsql_owner.syn_blue");
		RecordSvc svc = PlsqlPackages.create(RecordSvc.class, DATA_SOURCE);
		assertThat(svc.echoRec(new Rec(BigDecimal.ONE))).isEqualByComparingTo("101");

		user("CREATE OR REPLACE SYNONYM syn_svc FOR syn_green");
		assertThatThrownBy(() -> svc.echoRec(new Rec(BigDecimal.ONE))).isInstanceOf(PlsqlExecutionException.class)
				.hasMessageContaining("SYN_SVC now points to PLSQL_TEST.SYN_GREEN, not PLSQL_OWNER.SYN_BLUE");
		assertThat(svc.echoRec(new Rec(BigDecimal.ONE))).isEqualByComparingTo("102");
	}

	@Test
	@DisplayName("pointed to a package of another signature: the call fails once, saying where the synonym points now")
	void pointed_to_another_signature() {
		blueAndGreen();
		user(pkg("syn_other", "p_x", 3));
		user("CREATE SYNONYM syn_svc FOR plsql_owner.syn_blue");
		Svc svc = PlsqlPackages.create(Svc.class, DATA_SOURCE);
		assertThat(svc.f(BigDecimal.ONE)).isEqualByComparingTo("101");

		user("CREATE OR REPLACE SYNONYM syn_svc FOR syn_other");
		assertThatThrownBy(() -> svc.f(BigDecimal.ONE)).isInstanceOf(PlsqlExecutionException.class)
				.hasMessageContaining("SYN_SVC now points to PLSQL_TEST.SYN_OTHER, not PLSQL_OWNER.SYN_BLUE");
		assertThat(svc.f(BigDecimal.ONE)).isEqualByComparingTo("103");
	}
}
