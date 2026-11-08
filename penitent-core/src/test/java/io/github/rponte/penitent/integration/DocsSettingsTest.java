package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlBind;
import io.github.rponte.penitent.PlsqlOut;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.PlsqlParam;
import io.github.rponte.penitent.PlsqlSettings;
import io.github.rponte.penitent.convert.PlsqlConverter;
import io.github.rponte.penitent.convert.PlsqlConverters;

/**
 * The examples of docs/settings.md and docs/validation.md. The regions are what the pages show, and
 * {@link DocsPagesTest} checks that the pages still show them.
 */
class DocsSettingsTest extends OracleIntegrationTest {

	record TaxId(String value) {
	}

	enum Tier {
		GOLD, SILVER
	}

	@PlsqlPackage("ORDERS_PKG")
	interface OrderSearch {
		long countOrders(@PlsqlParam("P_CUSTOMER_ID") long customerId,
				@PlsqlParam("P_MIN_TOTAL") BigDecimal minTotal);
	}

	@PlsqlPackage("CUSTOMERS_PKG")
	interface CustomerLookup {
		Long findByTaxId(@PlsqlParam("P_TAX_ID") TaxId taxId);

		Tier tierOf(@PlsqlParam("P_ID") long id);
	}

	// snippet: settings/positional
	@PlsqlPackage(value = "ORDERS_PKG", bind = PlsqlBind.BY_POSITION)
	public interface PositionalOrders {

	    long countOrders(long customerId, String status);   // still by position
	}
	// end-snippet

	@PlsqlPackage("ORDERS_PKG")
	interface Orders {
		void placeOrder(long customerId, BigDecimal total, PlsqlOut<Long> orderId);

		String orderStatus(long orderId);
	}

	@PlsqlPackage("CUSTOMERS_PKG")
	interface Customers {
		Long findByTaxId(TaxId taxId);
	}

	private final DataSource dataSource = DATA_SOURCE;

	private void place(long customerId, String total) {
		PlsqlPackages.create(Orders.class, dataSource).placeOrder(customerId, new BigDecimal(total), new PlsqlOut<>());
	}

	@Test
	@DisplayName("bind and converters for every interface; the interface's own bind wins")
	void settings() {
		place(403, "50.00");
		place(403, "150.00");

		// snippet: settings/settings
		PlsqlSettings settings = PlsqlSettings.defaults()
		        .withBind(PlsqlBind.BY_NAME)
		        .withConverters(PlsqlConverters.of(
		                PlsqlConverter.ofNullable(TaxId.class, String.class, TaxId::value, TaxId::new)));

		OrderSearch search = PlsqlPackages.create(OrderSearch.class, dataSource, settings);
		CustomerLookup lookup = PlsqlPackages.create(CustomerLookup.class, dataSource, settings);
		// end-snippet

		PositionalOrders positional = PlsqlPackages.create(PositionalOrders.class, dataSource, settings);

		assertThat(search.countOrders(403, new BigDecimal("100"))).isEqualTo(1);
		assertThat(lookup.findByTaxId(new TaxId("12345678901"))).isEqualTo(1L);
		assertThat(positional.countOrders(403, "OPEN")).isEqualTo(2);
	}

	@Test
	@DisplayName("one more converter on top of the global ones")
	void adding_converters() {
		PlsqlSettings settings = PlsqlSettings.defaults()
				.withBind(PlsqlBind.BY_NAME)
				.withConverters(PlsqlConverters.of(
						PlsqlConverter.ofNullable(TaxId.class, String.class, TaxId::value, TaxId::new)));
		PlsqlConverter<Tier, String> tierConverter = PlsqlConverter.enumByCode(Tier.class, String.class,
				tier -> tier == Tier.GOLD ? "G" : "S");

		// snippet: settings/adding
		PlsqlSettings mine = settings.withConverters(settings.converters().with(PlsqlConverters.of(tierConverter)));
		// end-snippet

		CustomerLookup lookup = PlsqlPackages.create(CustomerLookup.class, dataSource, mine);
		assertThat(lookup.tierOf(1)).isEqualTo(Tier.GOLD);
		assertThat(lookup.findByTaxId(new TaxId("12345678901"))).isEqualTo(1L);
	}

	@Test
	@DisplayName("validation: one interface")
	void validate() {
		// snippet: validation/validate
		Orders orders = PlsqlPackages.create(Orders.class, dataSource);
		PlsqlPackages.validate(orders);
		// end-snippet
	}

	// snippet: validation/test
	@Test
	void interfaces_match_the_database() {
	    PlsqlPackages.validate(PlsqlPackages.create(Orders.class, dataSource));
	    PlsqlPackages.validate(PlsqlPackages.create(Customers.class, dataSource));
	}
	// end-snippet
}
