package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

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
import io.github.rponte.penitent.exception.DefaultPlsqlExceptionTranslator;
import io.github.rponte.penitent.exception.PlsqlExceptionTranslator;
import io.github.rponte.penitent.exception.PlsqlRoutineRaisedException;

/**
 * The examples of docs/errors.md, docs/settings.md and docs/validation.md, as the pages show them. A
 * change here changes the pages, and the other way around.
 */
class DocsErrorsAndSettingsTest extends OracleIntegrationTest {

	@PlsqlPackage("ORDERS_PKG")
	interface Orders {
		void placeOrder(long customerId, BigDecimal total, PlsqlOut<Long> orderId);

		void cancelOrder(long orderId);

		String orderStatus(long orderId);
	}

	@PlsqlPackage("CUSTOMERS_PKG")
	interface Customers {
		void register(long id, String name);

		Long findByTaxId(TaxId taxId);
	}

	record TaxId(String value) {
	}

	static class OrderNotOpenException extends RuntimeException {

		private static final long serialVersionUID = 1L;

		OrderNotOpenException(Throwable cause) {
			super(cause);
		}
	}

	@PlsqlPackage("ORDERS_PKG")
	interface OrderSearch {
		long countOrders(@PlsqlParam("P_CUSTOMER_ID") long customerId,
				@PlsqlParam("P_MIN_TOTAL") BigDecimal minTotal);
	}

	@PlsqlPackage("CUSTOMERS_PKG")
	interface CustomerLookup {
		Long findByTaxId(@PlsqlParam("P_TAX_ID") TaxId taxId);
	}

	@PlsqlPackage(value = "ORDERS_PKG", bind = PlsqlBind.BY_POSITION)
	interface PositionalOrders {
		long countOrders(long customerId, String status);
	}

	private final Orders orders = PlsqlPackages.create(Orders.class, DATA_SOURCE);

	private long place(long customerId, String total) {
		PlsqlOut<Long> orderId = new PlsqlOut<>();
		orders.placeOrder(customerId, new BigDecimal(total), orderId);
		return orderId.get();
	}

	@Test
	@DisplayName("errors: RAISE_APPLICATION_ERROR, NO_DATA_FOUND and a constraint, all from the routine")
	void routine_errors() {
		long orderId = place(401, "10.00");
		orders.cancelOrder(orderId);

		assertThatThrownBy(() -> orders.cancelOrder(orderId))
				.isInstanceOfSatisfying(PlsqlRoutineRaisedException.class, e -> {
					assertThat(e.getCode()).isEqualTo(20001);
					assertThat(e.getReason()).isEqualTo("order " + orderId + " is not open");
					assertThat(e.getMessage()).startsWith("ORA-20001: order " + orderId + " is not open\n"
							+ "ORA-06512: at \"PLSQL_TEST.ORDERS_PKG\"");
				});

		assertThatThrownBy(() -> orders.orderStatus(-1))
				.isInstanceOfSatisfying(PlsqlRoutineRaisedException.class, e -> assertThat(e.getCode()).isEqualTo(1403));

		Customers customers = PlsqlPackages.create(Customers.class, DATA_SOURCE);
		assertThatThrownBy(() -> customers.register(1, "Ana Lima"))
				.isInstanceOfSatisfying(PlsqlRoutineRaisedException.class, e -> assertThat(e.getCode()).isEqualTo(1));
	}

	@Test
	@DisplayName("errors: a translator turns a routine error into a domain exception")
	void translator() {
		PlsqlExceptionTranslator translator = failure -> failure.cause().getErrorCode() == 20001
				? new OrderNotOpenException(failure.cause())
				: new DefaultPlsqlExceptionTranslator().translate(failure);

		Orders translated = PlsqlPackages.create(Orders.class, DATA_SOURCE,
				PlsqlSettings.defaults().withExceptionTranslator(translator));

		long orderId = place(402, "10.00");
		translated.cancelOrder(orderId);
		assertThatThrownBy(() -> translated.cancelOrder(orderId)).isInstanceOf(OrderNotOpenException.class);
		assertThatThrownBy(() -> translated.orderStatus(-1)).isInstanceOf(PlsqlRoutineRaisedException.class);
	}

	@Test
	@DisplayName("settings: bind and converters for every interface; the interface's own bind wins")
	void settings() {
		PlsqlSettings settings = PlsqlSettings.defaults()
				.withBind(PlsqlBind.BY_NAME)
				.withConverters(PlsqlConverters.of(
						PlsqlConverter.ofNullable(TaxId.class, String.class, TaxId::value, TaxId::new)));

		place(403, "50.00");
		place(403, "150.00");

		OrderSearch search = PlsqlPackages.create(OrderSearch.class, DATA_SOURCE, settings);
		CustomerLookup lookup = PlsqlPackages.create(CustomerLookup.class, DATA_SOURCE, settings);
		PositionalOrders positional = PlsqlPackages.create(PositionalOrders.class, DATA_SOURCE, settings);

		assertThat(search.countOrders(403, new BigDecimal("100"))).isEqualTo(1);
		assertThat(lookup.findByTaxId(new TaxId("12345678901"))).isEqualTo(1L);
		assertThat(positional.countOrders(403, "OPEN")).isEqualTo(2);
	}

	@Test
	@DisplayName("validation: the interfaces of the pages match the database")
	void validation() {
		PlsqlPackages.validate(orders);
		PlsqlPackages.validate(PlsqlPackages.create(Customers.class, DATA_SOURCE));
	}
}
