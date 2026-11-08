package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import javax.sql.DataSource;

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
 * The examples of docs/routines.md. The regions are what the page shows, and {@link DocsPagesTest}
 * checks that the page still shows them. Each section of the page has its own interface, with only
 * the methods the section shows.
 */
class DocsRoutinesTest extends OracleIntegrationTest {

	// snippet: routines/interface
	@PlsqlPackage("ORDERS_PKG")
	public interface Orders {

	    BigDecimal calculateFreight(String origin, String destination);   // a function

	    void cancelOrder(long orderId);                                   // a procedure

	    @PlsqlRoutine("ORDER_STATUS")
	    String statusOf(long orderId);
	}
	// end-snippet

	@PlsqlPackage("ORDERS_PKG")
	interface Counting {
		// snippet: routines/default-methods
		long countOrders(long customerId);                  // every order of the customer
		long countOrders(long customerId, String status);   // only those with this status
		// end-snippet
	}

	@PlsqlPackage("ORDERS_PKG")
	interface Overloads {
		// snippet: routines/overload-methods
		String describe(long orderId);    // describe(p_order_id NUMBER)
		String describe(String status);   // describe(p_status VARCHAR2)
		// end-snippet
	}

	@PlsqlPackage("ORDERS_PKG")
	interface Outs {
		// snippet: routines/out-method
		void placeOrder(long customerId, BigDecimal total, PlsqlOut<Long> orderId);
		// end-snippet

		void cancelOrder(long orderId);

		// snippet: routines/in-out-methods
		void applyDiscount(PlsqlOut<BigDecimal> total);                 // p_percent takes its DEFAULT, 10
		void applyDiscount(PlsqlOut<BigDecimal> total, int percent);
		// end-snippet
	}

	// snippet: routines/by-name-interface
	@PlsqlPackage(value = "ORDERS_PKG", bind = PlsqlBind.BY_NAME)
	public interface OrderSearch {

	    long countOrders(@PlsqlParam("P_CUSTOMER_ID") long customerId,
	                     @PlsqlParam("P_MIN_TOTAL") BigDecimal minTotal);
	}
	// end-snippet

	// snippet: routines/schema-interface
	@PlsqlSchema                        // the session's schema; or @PlsqlSchema("FINANCE")
	public interface Money {

	    BigDecimal roundToCents(BigDecimal value);
	}
	// end-snippet

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

	private final DataSource dataSource = DATA_SOURCE;

	private final Outs outs = PlsqlPackages.create(Outs.class, DATA_SOURCE);

	private long place(long customerId, String total) {
		PlsqlOut<Long> orderId = new PlsqlOut<>();
		outs.placeOrder(customerId, new BigDecimal(total), orderId);
		return orderId.get();
	}

	@Test
	@DisplayName("functions, procedures and @PlsqlRoutine")
	void functions_and_procedures() {
		long orderId = place(101, "100.00");

		// snippet: routines/calls
		Orders orders = PlsqlPackages.create(Orders.class, dataSource);

		orders.calculateFreight("SP", "SP");   // 10
		orders.cancelOrder(orderId);
		orders.statusOf(orderId);              // "CANCELLED"
		// end-snippet

		assertThat(orders.calculateFreight("SP", "SP")).isEqualByComparingTo("10");
		assertThat(orders.statusOf(orderId)).isEqualTo("CANCELLED");
	}

	@Test
	@DisplayName("DEFAULT at the end, one Java method per way of calling")
	void defaults_by_position() {
		long cancelled = place(102, "50.00");
		place(102, "150.00");
		outs.cancelOrder(cancelled);
		Counting orders = PlsqlPackages.create(Counting.class, dataSource);

		// snippet: routines/default-calls
		orders.countOrders(102);           // 2
		orders.countOrders(102, "OPEN");   // 1
		// end-snippet

		assertThat(orders.countOrders(102)).isEqualTo(2);
		assertThat(orders.countOrders(102, "OPEN")).isEqualTo(1);
	}

	@Test
	@DisplayName("overloads are picked by the Java signature")
	void overloads() {
		Overloads orders = PlsqlPackages.create(Overloads.class, dataSource);

		// snippet: routines/overload-calls
		orders.describe(7);        // "order 7"
		orders.describe("OPEN");   // "orders with status OPEN"
		// end-snippet

		assertThat(orders.describe(7)).isEqualTo("order 7");
		assertThat(orders.describe("OPEN")).isEqualTo("orders with status OPEN");
	}

	@Test
	@DisplayName("OUT and IN OUT")
	void out_and_in_out() {
		Outs orders = outs;

		// snippet: routines/out-call
		PlsqlOut<Long> orderId = new PlsqlOut<>();
		orders.placeOrder(103, new BigDecimal("100.00"), orderId);
		orderId.get();   // the new order's id
		// end-snippet

		assertThat(orderId.get()).isNotNull();

		// snippet: routines/in-out-calls
		PlsqlOut<BigDecimal> total = PlsqlOut.of(new BigDecimal("200"));

		orders.applyDiscount(total);       // total.get() is 180
		orders.applyDiscount(total, 50);   // total.get() is 90
		// end-snippet

		assertThat(total.get()).isEqualByComparingTo("90");
		PlsqlOut<BigDecimal> once = PlsqlOut.of(new BigDecimal("200"));
		orders.applyDiscount(once);
		assertThat(once.get()).isEqualByComparingTo("180");
	}

	@Test
	@DisplayName("by name, a DEFAULT in the middle can be left out")
	void by_name() {
		place(104, "50.00");
		place(104, "150.00");
		OrderSearch search = PlsqlPackages.create(OrderSearch.class, dataSource);

		// snippet: routines/by-name-call
		search.countOrders(104, new BigDecimal("100"));   // p_status is left out and takes its DEFAULT
		// end-snippet

		assertThat(search.countOrders(104, new BigDecimal("100"))).isEqualTo(1);
	}

	@Test
	@DisplayName("a routine outside any package")
	void outside_a_package() {
		Money money = PlsqlPackages.create(Money.class, dataSource);

		// snippet: routines/schema-call
		money.roundToCents(new BigDecimal("10.456"));   // 10.46
		// end-snippet

		assertThat(money.roundToCents(new BigDecimal("10.456"))).isEqualByComparingTo("10.46");
	}

	@Test
	@DisplayName("the messages shown on the page")
	void mismatches() {
		Broken.Orders broken = PlsqlPackages.create(Broken.Orders.class, dataSource);

		assertThatThrownBy(() -> broken.calculateFrieght("SP", "CE"))
				.isInstanceOf(PlsqlMappingException.class)
				.satisfies(e -> Docs.assertPagesShow(Docs.asDocumented(e.getMessage(), Broken.class),
						"routines.md#routine-not-found", "troubleshooting.md#routine-not-found"));

		assertThatThrownBy(() -> broken.calculateFreight("SP"))
				.isInstanceOf(PlsqlMappingException.class)
				.satisfies(e -> Docs.assertPagesShow(Docs.asDocumented(e.getMessage(), Broken.class),
						"routines.md#wrong-arity", "troubleshooting.md#wrong-arity"));

		Broken.OrderSearch search = PlsqlPackages.create(Broken.OrderSearch.class, dataSource);
		assertThatThrownBy(() -> search.countOrders(104))
				.isInstanceOf(PlsqlMappingException.class)
				.satisfies(e -> Docs.assertPagesShow(Docs.asDocumented(e.getMessage(), Broken.class),
						"routines.md#unknown-name", "troubleshooting.md#unknown-name"));
	}
}
