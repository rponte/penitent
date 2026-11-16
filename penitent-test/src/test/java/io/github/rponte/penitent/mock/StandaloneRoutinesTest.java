package io.github.rponte.penitent.mock;

import static io.github.rponte.penitent.mock.PlsqlMocks.any;
import static io.github.rponte.penitent.mock.PlsqlMocks.anyLong;
import static io.github.rponte.penitent.mock.PlsqlMocks.doNothing;
import static io.github.rponte.penitent.mock.PlsqlMocks.doThrow;
import static io.github.rponte.penitent.mock.PlsqlMocks.eq;
import static io.github.rponte.penitent.mock.PlsqlMocks.out;
import static io.github.rponte.penitent.mock.PlsqlMocks.times;
import static io.github.rponte.penitent.mock.PlsqlMocks.verify;
import static io.github.rponte.penitent.mock.PlsqlMocks.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlOut;
import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.PlsqlSchema;
import io.github.rponte.penitent.exception.PlsqlRoutineRaisedException;

/**
 * Stubs of standalone functions and procedures, outside any package: the routine is a unit of its own, so the whole
 * routine is rewritten in the test's edition. Their real code fails, so a test passes only through the stub.
 */
class StandaloneRoutinesTest {

	@PlsqlSchema
	interface Taxes {
		BigDecimal taxRate(String pUf);

		void notifyTaxOffice(long pInvoice);

		void quoteTax(String pUf, PlsqlOut<BigDecimal> pRate);

		BigDecimal invoiceTotal(BigDecimal pValue, String pUf);
	}

	@PlsqlSchema
	interface Wrapped {
		BigDecimal wrappedRate();
	}

	@PlsqlSchema("PLSQL_REGISTRY")
	interface Registry {
		BigDecimal regionRate();
	}

	private static DataSource pool;
	private static DataSource dataSource;

	private PlsqlMocks mocks;
	private Taxes taxes;

	@BeforeAll
	static void startDatabase() {
		pool = TestDatabase.pool();
		dataSource = PlsqlMocks.wrap(pool);
	}

	@BeforeEach
	void begin() {
		mocks = PlsqlMocks.on(dataSource);
		mocks.begin();
		taxes = PlsqlPackages.create(Taxes.class, dataSource);
	}

	@AfterEach
	void end() {
		mocks.end();
	}

	@Test
	@DisplayName("a standalone function answers as stubbed, and every other session gets the real one")
	void function() {
		Taxes mock = mocks.mock(Taxes.class);

		when(mock.taxRate("SP")).thenReturn(new BigDecimal("0.18"));

		assertThat(taxes.taxRate("SP")).isEqualByComparingTo("0.18");
		assertThatThrownBy(() -> PlsqlPackages.create(Taxes.class, pool).taxRate("SP"))
				.isInstanceOfSatisfying(PlsqlRoutineRaisedException.class, e -> assertThat(e.getCode()).isEqualTo(20400));
	}

	@Test
	@DisplayName("PL/SQL calling a standalone function gets the stub")
	void called_from_plsql() {
		when(mocks.mock(Taxes.class).taxRate(any())).thenReturn(new BigDecimal("0.10"));

		assertThat(taxes.invoiceTotal(new BigDecimal("100"), "SP")).isEqualByComparingTo("110");
	}

	@Test
	@DisplayName("a standalone procedure does nothing with doNothing(), and verify counts its calls")
	void procedure() {
		Taxes mock = mocks.mock(Taxes.class);

		doNothing().when(mock).notifyTaxOffice(anyLong());

		taxes.notifyTaxOffice(1001L);
		taxes.notifyTaxOffice(1002L);
		verify(mock, times(2)).notifyTaxOffice(anyLong());
		verify(mock).notifyTaxOffice(1001L);
	}

	@Test
	@DisplayName("a standalone procedure raises with doThrow(), and sets an OUT value with out()")
	void procedure_raises_and_sets_out() {
		Taxes mock = mocks.mock(Taxes.class);

		doThrow(-20500, "Tax office unavailable").when(mock).notifyTaxOffice(anyLong());
		doNothing().when(mock).quoteTax(eq("CE"), out(new BigDecimal("0.12")));

		assertThatThrownBy(() -> taxes.notifyTaxOffice(1L))
				.isInstanceOfSatisfying(PlsqlRoutineRaisedException.class, e -> assertThat(e.getCode()).isEqualTo(20500));
		PlsqlOut<BigDecimal> rate = new PlsqlOut<>();
		taxes.quoteTax("CE", rate);
		assertThat(rate.get()).isEqualByComparingTo("0.12");
	}

	@Test
	@DisplayName("the stub goes with the edition: the next test calls the real routine")
	void gone_with_the_edition() {
		when(mocks.mock(Taxes.class).taxRate("SP")).thenReturn(new BigDecimal("0.18"));
		mocks.end();
		mocks.begin();

		assertThatThrownBy(() -> taxes.taxRate("SP"))
				.isInstanceOfSatisfying(PlsqlRoutineRaisedException.class, e -> assertThat(e.getCode()).isEqualTo(20400));
	}

	@Test
	@DisplayName("a script replaces a standalone function in the test's edition, the schema editioning functions")
	void script() {
		mocks.applyScriptText("""
				CREATE OR REPLACE FUNCTION tax_rate(p_uf VARCHAR2) RETURN NUMBER IS
				BEGIN
				  RETURN 0.25;
				END tax_rate;
				/
				""");

		assertThat(taxes.taxRate("SP")).isEqualByComparingTo("0.25");
	}

	@Test
	@DisplayName("a standalone routine has stubs or is replaced by a script in a test, not both")
	void stubs_and_scripts_do_not_mix() {
		mocks.applyScriptText("CREATE OR REPLACE FUNCTION tax_rate(p_uf VARCHAR2) RETURN NUMBER IS BEGIN RETURN 0.25; END;\n/");

		assertThatThrownBy(() -> when(mocks.mock(Taxes.class).taxRate("SP")).thenReturn(BigDecimal.ONE))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("TAX_RATE")
				.hasMessageContaining("script");
	}

	@Test
	@DisplayName("a standalone routine of another schema is refused: its editions cannot be checked")
	void other_schema() {
		Registry mock = mocks.mock(Registry.class);

		assertThatThrownBy(() -> when(mock.regionRate()).thenReturn(BigDecimal.TEN))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("PLSQL_REGISTRY.REGION_RATE")
				.hasMessageContaining("schema of the mocks");
	}

	@Test
	@DisplayName("a standalone routine whose source is wrapped is refused: it cannot be parsed")
	void wrapped() {
		Wrapped mock = mocks.mock(Wrapped.class);

		assertThatThrownBy(() -> when(mock.wrappedRate()).thenReturn(BigDecimal.TEN))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("WRAPPED_RATE")
				.hasMessageContaining("wrapped");
	}
}
