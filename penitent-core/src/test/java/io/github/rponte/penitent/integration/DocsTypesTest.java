package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlBoolean;
import io.github.rponte.penitent.PlsqlOut;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.integration.DocsTypesTest.Customers.Address;

/**
 * The examples of docs/types.md. The regions are what the page shows, and {@link DocsPagesTest}
 * checks that the page still shows them. The page shows the records next to the methods that use
 * them, and both are valid inside an interface, so the regions live in {@link Customers}.
 */
class DocsTypesTest extends OracleIntegrationTest {

	@PlsqlPackage("CUSTOMERS_PKG")
	interface Customers {

		// snippet: types/date-methods
		Optional<LocalDate> bornOn(long id);
		LocalDateTime createdAt(long id);
		// end-snippet

		// snippet: types/boolean-methods
		boolean isVip(long id);

		@PlsqlBoolean(trueValue = "S", falseValue = "N")
		boolean vipFlag(long id);
		// end-snippet

		// snippet: types/time-zone-method
		OffsetDateTime deliveryDeadline(OffsetDateTime orderedAt);
		// end-snippet

		// snippet: types/object
		public record Address(String street, String city, String zipCode) {
		}

		Address normalizeAddress(Address address);
		// end-snippet

		// snippet: types/rowtype
		public record Customer(Long id, String name, String taxId, String tier,
		                       @PlsqlBoolean(trueValue = "S", falseValue = "N") boolean vip,
		                       LocalDate bornOn, LocalDateTime createdAt) {
		}

		Customer findCustomer(long id);
		// end-snippet

		// snippet: types/record
		public record CustomerSummary(Long customerId, String name, Address address, List<String> tags) {
		}

		CustomerSummary summary(long id);
		// end-snippet

		// snippet: types/collection-methods
		List<String> tagsOf(long id);

		int countTags(List<String> tags);
		int countTags(Set<String> tags);
		int countTags(String[] tags);

		List<String> cities(List<Address> addresses);
		// end-snippet

		// snippet: types/cursor
		public enum OrderStatus { OPEN, CANCELLED }

		public record OrderRow(Long id, BigDecimal total, OrderStatus status) {
		}

		List<OrderRow> ordersOf(long customerId);
		// end-snippet
	}

	static class Broken {

		record Address(String street, String city) {
		}

		@PlsqlPackage("CUSTOMERS_PKG")
		interface Customers {
			boolean vipFlag(long id);

			LocalDateTime deliveryDeadline(LocalDateTime orderedAt);

			Address normalizeAddress(Address address);
		}
	}

	@PlsqlPackage("ORDERS_PKG")
	interface Orders {
		void placeOrder(long customerId, BigDecimal total, PlsqlOut<Long> orderId);

		void cancelOrder(long orderId);
	}

	private final Customers customers = PlsqlPackages.create(Customers.class, DATA_SOURCE);

	@Test
	@DisplayName("booleans: native BOOLEAN, and other literals with @PlsqlBoolean")
	void booleans() {
		assertThat(customers.isVip(1)).isTrue();
		assertThat(customers.vipFlag(1)).isTrue();
		assertThat(customers.vipFlag(2)).isFalse();
	}

	@Test
	@DisplayName("dates, and Optional for NULL")
	void dates() {
		// snippet: types/date-calls
		customers.bornOn(1);      // Optional[1990-04-12]
		customers.bornOn(2);      // Optional.empty
		customers.createdAt(1);   // 2025-01-15T09:30
		// end-snippet

		assertThat(customers.bornOn(1)).hasToString("Optional[1990-04-12]");
		assertThat(customers.bornOn(2)).hasToString("Optional.empty");
		assertThat(customers.createdAt(1)).hasToString("2025-01-15T09:30");
	}

	@Test
	@DisplayName("a TIMESTAMP WITH TIME ZONE keeps its offset")
	void time_zones() {
		// snippet: types/time-zone-call
		customers.deliveryDeadline(OffsetDateTime.parse("2025-03-10T18:00-03:00"));   // 2025-03-12T18:00-03:00
		// end-snippet

		assertThat(customers.deliveryDeadline(OffsetDateTime.parse("2025-03-10T18:00-03:00")))
				.hasToString("2025-03-12T18:00-03:00");
	}

	@Test
	@DisplayName("OBJECT, %ROWTYPE and a RECORD with nested types")
	void composites() {
		// snippet: types/object-call
		customers.normalizeAddress(new Address("rua das flores 10", "Fortaleza", "60000-000"));
		// Address[street=Rua Das Flores 10, city=FORTALEZA, zipCode=60000000]
		// end-snippet

		assertThat(customers.normalizeAddress(new Customers.Address("rua das flores 10", "Fortaleza", "60000-000")))
				.hasToString("Address[street=Rua Das Flores 10, city=FORTALEZA, zipCode=60000000]");

		// snippet: types/rowtype-call
		customers.findCustomer(1);
		// Customer[id=1, name=Ana Lima, taxId=12345678901, tier=G, vip=true, bornOn=1990-04-12, createdAt=2025-01-15T09:30]
		// end-snippet

		assertThat(customers.findCustomer(1)).hasToString("Customer[id=1, name=Ana Lima, taxId=12345678901, tier=G, "
				+ "vip=true, bornOn=1990-04-12, createdAt=2025-01-15T09:30]");

		assertThat(customers.summary(1)).isEqualTo(new Customers.CustomerSummary(1L, "Ana Lima",
				new Customers.Address("Rua das Flores 10", "Fortaleza", "60000000"), List.of("vip", "newsletter")));
	}

	@Test
	@DisplayName("collections, as a parameter and as a return value")
	void collections() {
		// snippet: types/collection-calls
		customers.tagsOf(1);                                    // [vip, newsletter]
		customers.countTags(new String[] { "a", "b", "c" });   // 3
		customers.cities(List.of(
		        new Address("Rua A 1", "Fortaleza", "60000000"),
		        new Address("Rua B 2", "Recife", "50000000"))); // [Fortaleza, Recife]
		// end-snippet

		assertThat(customers.tagsOf(1)).containsExactly("vip", "newsletter");
		assertThat(customers.tagsOf(2)).isEmpty();
		assertThat(customers.countTags(List.of("vip", "newsletter"))).isEqualTo(2);
		assertThat(customers.countTags(Set.of("vip"))).isEqualTo(1);
		assertThat(customers.countTags(new String[] { "a", "b", "c" })).isEqualTo(3);
		assertThat(customers.cities(List.of(
				new Customers.Address("Rua A 1", "Fortaleza", "60000000"),
				new Customers.Address("Rua B 2", "Recife", "50000000"))))
				.containsExactly("Fortaleza", "Recife");
	}

	@Test
	@DisplayName("cursors, of rows and of a single column")
	void cursors() {
		Orders orders = PlsqlPackages.create(Orders.class, DATA_SOURCE);
		PlsqlOut<Long> first = new PlsqlOut<>();
		PlsqlOut<Long> second = new PlsqlOut<>();
		orders.placeOrder(201, new BigDecimal("100.00"), first);
		orders.placeOrder(201, new BigDecimal("40.00"), second);
		orders.cancelOrder(second.get());

		// snippet: types/cursor-call
		customers.ordersOf(201);
		// [OrderRow[id=1, total=100, status=OPEN], OrderRow[id=2, total=40, status=CANCELLED]]
		// end-snippet

		assertThat(customers.ordersOf(201)).containsExactly(
				new Customers.OrderRow(first.get(), new BigDecimal("100"), Customers.OrderStatus.OPEN),
				new Customers.OrderRow(second.get(), new BigDecimal("40"), Customers.OrderStatus.CANCELLED));
	}

	@Test
	@DisplayName("the messages shown on the page")
	void mismatches() {
		Broken.Customers broken = PlsqlPackages.create(Broken.Customers.class, DATA_SOURCE);

		assertThatThrownBy(() -> broken.vipFlag(1))
				.isInstanceOf(PlsqlMappingException.class)
				.satisfies(e -> Docs.assertPagesShow(Docs.asDocumented(e.getMessage(), Broken.class),
						"types.md#boolean-literal", "troubleshooting.md#boolean-literal"));

		assertThatThrownBy(() -> broken.deliveryDeadline(LocalDateTime.now()))
				.isInstanceOf(PlsqlMappingException.class)
				.satisfies(e -> Docs.assertPagesShow(Docs.asDocumented(e.getMessage(), Broken.class),
						"types.md#local-on-tstz", "troubleshooting.md#local-on-tstz"));

		assertThatThrownBy(() -> broken.normalizeAddress(new Broken.Address("Rua A 1", "Fortaleza")))
				.isInstanceOf(PlsqlMappingException.class)
				.satisfies(e -> Docs.assertPagesShow(Docs.asDocumented(e.getMessage(), Broken.class),
						"types.md#missing-attribute", "troubleshooting.md#missing-attribute"));
	}
}
