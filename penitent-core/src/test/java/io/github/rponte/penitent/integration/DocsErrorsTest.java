package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlOut;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.PlsqlSettings;
import io.github.rponte.penitent.exception.DefaultPlsqlExceptionTranslator;
import io.github.rponte.penitent.exception.PlsqlExceptionTranslator;
import io.github.rponte.penitent.exception.PlsqlRoutineRaisedException;

/**
 * The examples of docs/errors.md. The regions are what the page shows, and {@link DocsPagesTest}
 * checks that the page still shows them.
 */
class DocsErrorsTest extends OracleIntegrationTest {

	@PlsqlPackage("ORDERS_PKG")
	interface Orders {
		void placeOrder(long customerId, BigDecimal total, PlsqlOut<Long> orderId);

		void cancelOrder(long orderId);

		String orderStatus(long orderId);
	}

	@PlsqlPackage("CUSTOMERS_PKG")
	interface Customers {
		void register(long id, String name);
	}

	static class OrderNotOpenException extends RuntimeException {

		private static final long serialVersionUID = 1L;

		OrderNotOpenException(Throwable cause) {
			super(cause);
		}
	}

	private final DataSource dataSource = DATA_SOURCE;

	private final Orders orders = PlsqlPackages.create(Orders.class, DATA_SOURCE);

	private final Customers customers = PlsqlPackages.create(Customers.class, DATA_SOURCE);

	private long cancelledOrder(long customerId) {
		PlsqlOut<Long> orderId = new PlsqlOut<>();
		orders.placeOrder(customerId, new BigDecimal("10.00"), orderId);
		orders.cancelOrder(orderId.get());
		return orderId.get();
	}

	@Test
	@DisplayName("RAISE_APPLICATION_ERROR keeps its code, its text and Oracle's message")
	void raise_application_error() {
		long orderId = cancelledOrder(401);

		// snippet: errors/catch
		try {
		    orders.cancelOrder(orderId);
		} catch (PlsqlRoutineRaisedException e) {
		    e.getCode();      // 20001
		    e.getReason();    // "order 7 is not open"
		    e.getMessage();   // "ORA-20001: order 7 is not open\nORA-06512: at \"SHOP.ORDERS_PKG\", line 18\n..."
		}
		// end-snippet

		assertThatThrownBy(() -> orders.cancelOrder(orderId))
				.isInstanceOfSatisfying(PlsqlRoutineRaisedException.class, e -> {
					assertThat(e.getCode()).isEqualTo(20001);
					assertThat(e.getReason()).isEqualTo("order " + orderId + " is not open");
					assertThat(Docs.asDocumented(e.getMessage(), getClass())).startsWith("ORA-20001: order " + orderId
							+ " is not open\nORA-06512: at \"SHOP.ORDERS_PKG\", line 18\n");
				});
	}

	@Test
	@DisplayName("errors Oracle raises inside the routine are the routine's too")
	void oracle_errors() {
		assertThatThrownBy(() -> {
			// snippet: errors/no-data-found
			orders.orderStatus(-1);          // SELECT ... INTO finds no row: getCode() is 1403, NO_DATA_FOUND
			// end-snippet
		}).isInstanceOfSatisfying(PlsqlRoutineRaisedException.class, e -> assertThat(e.getCode()).isEqualTo(1403));

		assertThatThrownBy(() -> {
			// snippet: errors/primary-key
			customers.register(1, "Ana");    // the INSERT breaks the primary key: getCode() is 1
			// end-snippet
		}).isInstanceOfSatisfying(PlsqlRoutineRaisedException.class, e -> assertThat(e.getCode()).isEqualTo(1));
	}

	@Test
	@DisplayName("a translator turns a routine error into a domain exception")
	void translator() {
		// snippet: errors/translator
		PlsqlExceptionTranslator translator = failure -> failure.cause().getErrorCode() == 20001
		        ? new OrderNotOpenException(failure.cause())
		        : new DefaultPlsqlExceptionTranslator().translate(failure);

		Orders orders = PlsqlPackages.create(Orders.class, dataSource,
		        PlsqlSettings.defaults().withExceptionTranslator(translator));
		// end-snippet

		long orderId = cancelledOrder(402);
		assertThatThrownBy(() -> orders.cancelOrder(orderId)).isInstanceOf(OrderNotOpenException.class);
		assertThatThrownBy(() -> orders.orderStatus(-1)).isInstanceOf(PlsqlRoutineRaisedException.class);
	}
}
