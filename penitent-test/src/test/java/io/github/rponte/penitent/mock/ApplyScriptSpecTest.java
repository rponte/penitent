package io.github.rponte.penitent.mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;

/** A script that replaces a package's spec in the test's edition, with its body and without it. */
class ApplyScriptSpecTest {

	@PlsqlPackage("CHECKOUT")
	interface Checkout {
		BigDecimal total(String pOrigin, String pDestination);
	}

	@PlsqlPackage("CHECKOUT")
	interface CheckoutWithFee {
		BigDecimal total(String pOrigin, String pDestination);

		BigDecimal fee();
	}

	private static final String SPEC_WITH_FEE = """
			CREATE OR REPLACE PACKAGE checkout AS
			  FUNCTION total(p_origin VARCHAR2, p_destination VARCHAR2) RETURN NUMBER;
			  FUNCTION fee RETURN NUMBER;
			END checkout;
			/
			""";

	private static DataSource pool;
	private static DataSource dataSource;

	private PlsqlMocks mocks;

	@BeforeAll
	static void startDatabase() {
		pool = TestDatabase.pool();
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
	@DisplayName("a spec and its body replaced: the test sees both, every other session the real package")
	void spec_and_body() {
		mocks.applyScriptText(SPEC_WITH_FEE + """
				CREATE OR REPLACE PACKAGE BODY checkout AS
				  FUNCTION total(p_origin VARCHAR2, p_destination VARCHAR2) RETURN NUMBER IS BEGIN RETURN 777; END;
				  FUNCTION fee RETURN NUMBER IS BEGIN RETURN 5; END;
				END checkout;
				/
				""");

		CheckoutWithFee test = PlsqlPackages.create(CheckoutWithFee.class, dataSource);
		assertThat(test.total("SP", "CE")).isEqualByComparingTo("777");
		assertThat(test.fee()).isEqualByComparingTo("5");
		CheckoutWithFee real = PlsqlPackages.create(CheckoutWithFee.class, pool);
		assertThatThrownBy(real::fee).hasMessageContaining("FEE");
		assertThat(PlsqlPackages.create(Checkout.class, pool).total("SP", "CE")).isNotEqualByComparingTo("777");
	}

	@Test
	@DisplayName("a spec replaced without its body, the same declarations: the body compiles again in the edition")
	void compatible_spec_alone() {
		mocks.applyScriptText("""
				CREATE OR REPLACE PACKAGE checkout AS
				  c_version CONSTANT NUMBER := 2;
				  FUNCTION total(p_origin VARCHAR2, p_destination VARCHAR2) RETURN NUMBER;
				END checkout;
				/
				""");

		BigDecimal real = PlsqlPackages.create(Checkout.class, pool).total("SP", "CE");
		assertThat(PlsqlPackages.create(Checkout.class, dataSource).total("SP", "CE")).isEqualByComparingTo(real);
	}

	@Test
	@DisplayName("a spec replaced without its body, declaring what the body lacks: refused when applied, not at a call")
	void incompatible_spec_alone() {
		assertThatThrownBy(() -> mocks.applyScriptText(SPEC_WITH_FEE))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("statement 1 (line 1): the spec of PLSQL_TEST.CHECKOUT replaced without its body"
						+ " leaves the body invalid in the test's edition")
				.hasMessageContaining("PLS-00323")
				.hasMessageContaining("Replace the body in the same script, after the spec");
		assertThat(PlsqlPackages.create(Checkout.class, pool).total("SP", "CE")).isNotNull();
	}
}
