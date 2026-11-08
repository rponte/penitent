package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlOut;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.exception.PlsqlRoutineRaisedException;

/**
 * The examples of docs/getting-started.md, as the page shows them. A change here changes the page,
 * and the other way around.
 */
class DocsGettingStartedTest extends OracleIntegrationTest {

	@PlsqlPackage("ORDERS_PKG")
	interface Orders {
		BigDecimal calculateFreight(String origin, String destination);

		void placeOrder(long customerId, BigDecimal total, PlsqlOut<Long> orderId);

		void cancelOrder(long orderId);

		String orderStatus(long orderId);
	}

	static class Broken {

		@PlsqlPackage("ORDERS_PKG")
		interface Orders {
			String cancelOrder(long orderId);

			void placeOrder(long customerId, BigDecimal total, Long orderId);
		}
	}

	private final Orders orders = PlsqlPackages.create(Orders.class, DATA_SOURCE);

	@Test
	@DisplayName("step 3: a function returns its value")
	void calls_a_function() {
		assertThat(orders.calculateFreight("SP", "CE")).isEqualByComparingTo("25.5");
	}

	@Test
	@DisplayName("step 4: a procedure fills its OUT parameter")
	void calls_a_procedure() {
		PlsqlOut<Long> orderId = new PlsqlOut<>();
		orders.placeOrder(42, new BigDecimal("100.00"), orderId);

		assertThat(orderId.get()).isNotNull();
		assertThat(orders.orderStatus(orderId.get())).isEqualTo("OPEN");
	}

	@Test
	@DisplayName("step 5: an error raised by the routine keeps its code and text")
	void routine_error() {
		PlsqlOut<Long> orderId = new PlsqlOut<>();
		orders.placeOrder(42, new BigDecimal("100.00"), orderId);
		orders.cancelOrder(orderId.get());

		assertThatThrownBy(() -> orders.cancelOrder(orderId.get()))
				.isInstanceOfSatisfying(PlsqlRoutineRaisedException.class, e -> {
					assertThat(e.getCode()).isEqualTo(20001);
					assertThat(e.getReason()).isEqualTo("order " + orderId.get() + " is not open");
				});
	}

	@Test
	@DisplayName("step 6: validate lists every method that does not match")
	void validation() {
		PlsqlPackages.validate(orders);

		Broken.Orders broken = PlsqlPackages.create(Broken.Orders.class, DATA_SOURCE);
		assertThatThrownBy(() -> PlsqlPackages.validate(broken))
				.isInstanceOf(PlsqlMappingException.class)
				.extracting(e -> Docs.asDocumented(e.getMessage(), Broken.class))
				.isEqualTo("""
						2 methods of com.example.shop.Orders do not match the database:
						  - cancelOrder declares a return value, but SHOP.ORDERS_PKG.CANCEL_ORDER is a procedure and returns nothing; use void
						  - placeOrder -> SHOP.ORDERS_PKG.PLACE_ORDER: P_ORDER_ID is OUT; declare PlsqlOut<T> in this position, not Long""");
	}
}
