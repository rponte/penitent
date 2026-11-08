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
import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.exception.PlsqlRoutineRaisedException;

/**
 * The examples of docs/getting-started.md. The regions are what the page shows, and
 * {@link DocsPagesTest} checks that the page still shows them.
 */
class DocsGettingStartedTest extends OracleIntegrationTest {

	// snippet: getting-started/interface
	@PlsqlPackage("ORDERS_PKG")
	public interface Orders {

		BigDecimal calculateFreight(String origin, String destination);

		void placeOrder(long customerId, BigDecimal total, PlsqlOut<Long> orderId);

		void cancelOrder(long orderId);

		String orderStatus(long orderId);
	}
	// end-snippet

	static class Broken {

		// snippet: getting-started/broken-interface
		@PlsqlPackage("ORDERS_PKG")
		public interface Orders {

			String cancelOrder(long orderId);

			void placeOrder(long customerId, BigDecimal total, Long orderId);
		}
		// end-snippet
	}

	private final DataSource dataSource = DATA_SOURCE;

	@Test
	@DisplayName("step 3: a function returns its value")
	void calls_a_function() {
		// snippet: getting-started/create-and-call
		Orders orders = PlsqlPackages.create(Orders.class, dataSource);

		BigDecimal freight = orders.calculateFreight("SP", "CE");   // 25.5
		// end-snippet

		assertThat(freight).isEqualByComparingTo("25.5");
	}

	@Test
	@DisplayName("steps 4 and 5: a procedure fills its OUT parameter, and a routine error keeps its code and text")
	void out_parameter_and_routine_error() {
		Orders orders = PlsqlPackages.create(Orders.class, dataSource);

		// snippet: getting-started/out-parameter
		PlsqlOut<Long> orderId = new PlsqlOut<>();
		orders.placeOrder(42, new BigDecimal("100.00"), orderId);

		orders.orderStatus(orderId.get());   // "OPEN"
		// end-snippet

		assertThat(orders.orderStatus(orderId.get())).isEqualTo("OPEN");

		// snippet: getting-started/routine-error
		orders.cancelOrder(orderId.get());

		try {
			orders.cancelOrder(orderId.get());
		} catch (PlsqlRoutineRaisedException e) {
			e.getCode();     // 20001, without the sign
			e.getReason();   // "order 1 is not open"
		}
		// end-snippet

		assertThatThrownBy(() -> orders.cancelOrder(orderId.get()))
				.isInstanceOfSatisfying(PlsqlRoutineRaisedException.class, e -> {
					assertThat(e.getCode()).isEqualTo(20001);
					assertThat(e.getReason()).isEqualTo("order " + orderId.get() + " is not open");
				});
	}

	@Test
	@DisplayName("step 6: validate lists every method that does not match")
	void validation() {
		PlsqlPackages.validate(PlsqlPackages.create(Orders.class, dataSource));

		Broken.Orders orders = PlsqlPackages.create(Broken.Orders.class, dataSource);
		assertThatThrownBy(() -> {
			// snippet: getting-started/validate
			PlsqlPackages.validate(orders);
			// end-snippet
		})
				.isInstanceOf(PlsqlMappingException.class)
				.satisfies(e -> {
					String message = Docs.asDocumented(e.getMessage(), Broken.class);
					Docs.assertPagesShow(e.getClass().getName() + ":\n" + message, "getting-started.md#validate-exception");
					Docs.assertPagesShow(message, "validation.md#validate-message");
				});
	}
}
