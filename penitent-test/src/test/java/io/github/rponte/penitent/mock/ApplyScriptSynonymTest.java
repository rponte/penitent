package io.github.rponte.penitent.mock;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zaxxer.hikari.HikariDataSource;

import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;

/**
 * A script that replaces a private synonym in the test's edition, in a schema of its own, {@value #SCHEMA}, whose
 * editions keep synonyms too: the test user's do not, which {@code ApplyScriptsTest} shows refused.
 */
class ApplyScriptSynonymTest {

	static final String SCHEMA = "PLSQL_SYNONYMS";

	/** Reached through the synonym RATES, which points to REAL_RATES. */
	@PlsqlPackage("RATES")
	interface Rates {
		BigDecimal rate();
	}

	/** Calls RATES.rate in PL/SQL, so it follows the synonym as its edition has it. */
	@PlsqlPackage("QUOTES")
	interface Quotes {
		BigDecimal total();
	}

	private static HikariDataSource pool;
	private static DataSource dataSource;

	private PlsqlMocks mocks;

	@BeforeAll
	static void createSchema() {
		TestDatabase.pool();
		OracleUnderTest oracle = TestDatabase.oracle();
		oracle.executeAsAdmin(
				"CREATE USER " + SCHEMA + " IDENTIFIED BY \"" + OracleUnderTest.PASSWORD + "\" QUOTA UNLIMITED ON "
						+ oracle.defaultTablespace(),
				"GRANT CONNECT, RESOURCE, CREATE VIEW, CREATE SYNONYM TO " + SCHEMA);
		pool = TestDatabase.newPool(SCHEMA);
		PlsqlMocks.prepare(TestDatabase.adminPool(), pool, SCHEMA);
		oracle.executeAsAdmin("ALTER USER " + SCHEMA + " ENABLE EDITIONS FOR SYNONYM");
		TestDatabase.execute(pool,
				"CREATE PACKAGE real_rates AS FUNCTION rate RETURN NUMBER; END real_rates;",
				"CREATE PACKAGE BODY real_rates AS FUNCTION rate RETURN NUMBER IS BEGIN RETURN 1; END; END real_rates;",
				"CREATE PACKAGE fake_rates AS FUNCTION rate RETURN NUMBER; END fake_rates;",
				"CREATE PACKAGE BODY fake_rates AS FUNCTION rate RETURN NUMBER IS BEGIN RETURN 2; END; END fake_rates;",
				"CREATE SYNONYM rates FOR real_rates",
				"CREATE PACKAGE quotes AS FUNCTION total RETURN NUMBER; END quotes;",
				"CREATE PACKAGE BODY quotes AS FUNCTION total RETURN NUMBER IS BEGIN RETURN rates.rate * 10; END;"
						+ " END quotes;");
		dataSource = PlsqlMocks.wrap(pool);
	}

	@BeforeEach
	void begin() {
		mocks = PlsqlMocks.on(dataSource);
		mocks.begin();
	}

	@AfterEach
	void end() {
		mocks.end();
	}

	@Test
	@DisplayName("a synonym replaced: the test reaches its new target, in Java and in PL/SQL; every other session the real one")
	void synonym() {
		mocks.applyScriptText("CREATE OR REPLACE SYNONYM rates FOR fake_rates;");

		assertThat(PlsqlPackages.create(Rates.class, dataSource).rate()).isEqualByComparingTo("2");
		assertThat(PlsqlPackages.create(Quotes.class, dataSource).total()).isEqualByComparingTo("20");
		assertThat(PlsqlPackages.create(Rates.class, pool).rate()).isEqualByComparingTo("1");
		assertThat(PlsqlPackages.create(Quotes.class, pool).total()).isEqualByComparingTo("10");
	}

	@Test
	@DisplayName("the next test, in an edition of its own, reaches the real target again")
	void next_test() {
		assertThat(PlsqlPackages.create(Rates.class, dataSource).rate()).isEqualByComparingTo("1");
		assertThat(PlsqlPackages.create(Quotes.class, dataSource).total()).isEqualByComparingTo("10");
	}
}
