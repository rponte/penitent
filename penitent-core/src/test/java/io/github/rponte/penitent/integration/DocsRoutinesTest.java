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
import io.github.rponte.penitent.PlsqlRoutine;
import io.github.rponte.penitent.PlsqlSchema;
import io.github.rponte.penitent.exception.PlsqlMappingException;

/**
 * The examples of docs/routines.md, as the page shows them. A change here changes the page, and the
 * other way around.
 */
class DocsRoutinesTest extends OracleIntegrationTest {

	@PlsqlPackage("ORDERS_PKG")
	interface Orders {
		BigDecimal calculateFreight(String origin, String destination);

		void cancelOrder(long orderId);

		@PlsqlRoutine("ORDER_STATUS")
		String statusOf(long orderId);

		long countOrders(long customerId);

		long countOrders(long customerId, String status);

		String describe(long orderId);

		String describe(String status);

		void placeOrder(long customerId, BigDecimal total, PlsqlOut<Long> orderId);

		void applyDiscount(PlsqlOut<BigDecimal> total);

		void applyDiscount(PlsqlOut<BigDecimal> total, int percent);
	}

	@PlsqlPackage(value = "ORDERS_PKG", bind = PlsqlBind.BY_NAME)
	interface OrderSearch {
		long countOrders(@PlsqlParam("P_CUSTOMER_ID") long customerId,
				@PlsqlParam("P_MIN_TOTAL") BigDecimal minTotal);
	}

	@PlsqlSchema
	interface Money {
		BigDecimal roundToCents(BigDecimal value);
	}

	static class Broken {

		@PlsqlPackage("ORDERS_PKG")
		interface Orders {
			BigDecimal calculateFrieght(String origin, String destination);

			BigDecimal calculateFreight(String origin);
		}

		@PlsqlPackage(value = "ORDERS_PKG", bind = PlsqlBind.BY_NAME)
		interface OrderSearch {
			long countOrders(@PlsqlParam("P_CUSTOMER") long customerId);
		}
	}

	private final Orders orders = PlsqlPackages.create(Orders.class, DATA_SOURCE);

	private long place(long customerId, String total) {
		PlsqlOut<Long> orderId = new PlsqlOut<>();
		orders.placeOrder(customerId, new BigDecimal(total), orderId);
		return orderId.get();
	}

	@Test
	@DisplayName("functions, procedures and @PlsqlRoutine")
	void functions_and_procedures() {
		long orderId = place(101, "100.00");

		assertThat(orders.calculateFreight("SP", "SP")).isEqualByComparingTo("10");
		orders.cancelOrder(orderId);
		assertThat(orders.statusOf(orderId)).isEqualTo("CANCELLED");
	}

	@Test
	@DisplayName("DEFAULT at the end, one Java method per way of calling")
	void defaults_by_position() {
		long cancelled = place(102, "50.00");
		place(102, "150.00");
		orders.cancelOrder(cancelled);

		assertThat(orders.countOrders(102)).isEqualTo(2);
		assertThat(orders.countOrders(102, "OPEN")).isEqualTo(1);
	}

	@Test
	@DisplayName("overloads are picked by the Java signature")
	void overloads() {
		assertThat(orders.describe(7)).isEqualTo("order 7");
		assertThat(orders.describe("OPEN")).isEqualTo("orders with status OPEN");
	}

	@Test
	@DisplayName("OUT and IN OUT")
	void out_and_in_out() {
		PlsqlOut<Long> orderId = new PlsqlOut<>();
		orders.placeOrder(103, new BigDecimal("100.00"), orderId);
		assertThat(orderId.get()).isNotNull();

		PlsqlOut<BigDecimal> total = PlsqlOut.of(new BigDecimal("200"));
		orders.applyDiscount(total);
		assertThat(total.get()).isEqualByComparingTo("180");

		orders.applyDiscount(total, 50);
		assertThat(total.get()).isEqualByComparingTo("90");
	}

	@Test
	@DisplayName("by name, a DEFAULT in the middle can be left out")
	void by_name() {
		place(104, "50.00");
		place(104, "150.00");

		OrderSearch search = PlsqlPackages.create(OrderSearch.class, DATA_SOURCE);
		assertThat(search.countOrders(104, new BigDecimal("100"))).isEqualTo(1);
	}

	@Test
	@DisplayName("a routine outside any package")
	void outside_a_package() {
		Money money = PlsqlPackages.create(Money.class, DATA_SOURCE);
		assertThat(money.roundToCents(new BigDecimal("10.456"))).isEqualByComparingTo("10.46");
	}

	@Test
	@DisplayName("the messages quoted on the page")
	void mismatches() {
		Broken.Orders broken = PlsqlPackages.create(Broken.Orders.class, DATA_SOURCE);

		assertThatThrownBy(() -> broken.calculateFrieght("SP", "CE"))
				.isInstanceOf(PlsqlMappingException.class)
				.extracting(e -> Docs.asDocumented(e.getMessage(), Broken.class))
				.isEqualTo("routine SHOP.ORDERS_PKG.CALCULATE_FRIEGHT does not exist. Available: APPLY_DISCOUNT, "
						+ "CALCULATE_FREIGHT, CANCEL_ORDER, COUNT_ORDERS, DESCRIBE, DESCRIBE, ORDER_STATUS, PLACE_ORDER");

		assertThatThrownBy(() -> broken.calculateFreight("SP"))
				.isInstanceOf(PlsqlMappingException.class)
				.extracting(e -> Docs.asDocumented(e.getMessage(), Broken.class))
				.isEqualTo("calculateFreight declares 1 parameter(s), but SHOP.ORDERS_PKG.CALCULATE_FREIGHT expects 2: "
						+ "[P_ORIGIN VARCHAR2, P_DESTINATION VARCHAR2]");

		Broken.OrderSearch search = PlsqlPackages.create(Broken.OrderSearch.class, DATA_SOURCE);
		assertThatThrownBy(() -> search.countOrders(104))
				.isInstanceOf(PlsqlMappingException.class)
				.extracting(e -> Docs.asDocumented(e.getMessage(), Broken.class))
				.isEqualTo("countOrders: parameter customerId looks for P_CUSTOMER, which SHOP.ORDERS_PKG.COUNT_ORDERS "
						+ "does not have. Parameters: [P_CUSTOMER_ID NUMBER, P_STATUS VARCHAR2, P_MIN_TOTAL NUMBER]. "
						+ "Use @PlsqlParam with the Oracle name.");
	}
}
