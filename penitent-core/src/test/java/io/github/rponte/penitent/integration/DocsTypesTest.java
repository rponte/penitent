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

/**
 * The examples of docs/types.md, as the page shows them. A change here changes the page, and the
 * other way around.
 */
class DocsTypesTest extends OracleIntegrationTest {

	record Address(String street, String city, String zipCode) {
	}

	record Customer(Long id, String name, String taxId, String tier,
			@PlsqlBoolean(trueValue = "S", falseValue = "N") boolean vip,
			LocalDate bornOn, LocalDateTime createdAt) {
	}

	record CustomerSummary(Long customerId, String name, Address address, List<String> tags) {
	}

	enum OrderStatus {
		OPEN, CANCELLED
	}

	record OrderRow(Long id, BigDecimal total, OrderStatus status) {
	}

	@PlsqlPackage("CUSTOMERS_PKG")
	interface Customers {
		boolean isVip(long id);

		@PlsqlBoolean(trueValue = "S", falseValue = "N")
		boolean vipFlag(long id);

		Optional<LocalDate> bornOn(long id);

		LocalDateTime createdAt(long id);

		OffsetDateTime deliveryDeadline(OffsetDateTime orderedAt);

		Address normalizeAddress(Address address);

		Customer findCustomer(long id);

		CustomerSummary summary(long id);

		List<String> tagsOf(long id);

		int countTags(List<String> tags);

		int countTags(Set<String> tags);

		int countTags(String[] tags);

		List<String> cities(List<Address> addresses);

		List<OrderRow> ordersOf(long customerId);

		List<String> names();
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
		assertThat(customers.bornOn(1)).contains(LocalDate.of(1990, 4, 12));
		assertThat(customers.bornOn(2)).isEmpty();
		assertThat(customers.createdAt(1)).isEqualTo(LocalDateTime.of(2025, 1, 15, 9, 30));
	}

	@Test
	@DisplayName("a TIMESTAMP WITH TIME ZONE keeps its offset")
	void time_zones() {
		OffsetDateTime deadline = customers.deliveryDeadline(OffsetDateTime.parse("2025-03-10T18:00-03:00"));
		assertThat(deadline.toString()).isEqualTo("2025-03-12T18:00-03:00");
	}

	@Test
	@DisplayName("OBJECT, %ROWTYPE and a RECORD with nested types")
	void composites() {
		assertThat(customers.normalizeAddress(new Address("rua das flores 10", "Fortaleza", "60000-000")))
				.isEqualTo(new Address("Rua Das Flores 10", "FORTALEZA", "60000000"));

		assertThat(customers.findCustomer(1)).isEqualTo(new Customer(1L, "Ana Lima", "12345678901", "G", true,
				LocalDate.of(1990, 4, 12), LocalDateTime.of(2025, 1, 15, 9, 30)));

		assertThat(customers.summary(1)).isEqualTo(new CustomerSummary(1L, "Ana Lima",
				new Address("Rua das Flores 10", "Fortaleza", "60000000"), List.of("vip", "newsletter")));
	}

	@Test
	@DisplayName("collections, as a parameter and as a return value")
	void collections() {
		assertThat(customers.tagsOf(1)).containsExactly("vip", "newsletter");
		assertThat(customers.tagsOf(2)).isEmpty();

		assertThat(customers.countTags(List.of("vip", "newsletter"))).isEqualTo(2);
		assertThat(customers.countTags(Set.of("vip"))).isEqualTo(1);
		assertThat(customers.countTags(new String[] { "a", "b", "c" })).isEqualTo(3);

		assertThat(customers.cities(List.of(
				new Address("Rua A 1", "Fortaleza", "60000000"),
				new Address("Rua B 2", "Recife", "50000000"))))
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

		assertThat(customers.ordersOf(201)).containsExactly(
				new OrderRow(first.get(), new BigDecimal("100"), OrderStatus.OPEN),
				new OrderRow(second.get(), new BigDecimal("40"), OrderStatus.CANCELLED));

		assertThat(customers.names()).containsExactly("Ana Lima", "Bruno Souza");
	}

	@PlsqlPackage("ORDERS_PKG")
	interface Orders {
		void placeOrder(long customerId, BigDecimal total, PlsqlOut<Long> orderId);

		void cancelOrder(long orderId);
	}

	@Test
	@DisplayName("the messages quoted on the page")
	void mismatches() {
		Broken.Customers broken = PlsqlPackages.create(Broken.Customers.class, DATA_SOURCE);

		assertThatThrownBy(() -> broken.vipFlag(1))
				.isInstanceOf(PlsqlMappingException.class)
				.extracting(e -> Docs.asDocumented(e.getMessage(), Broken.class))
				.isEqualTo("vipFlag -> SHOP.CUSTOMERS_PKG.VIP_FLAG: cannot convert 'S' to boolean: expected 'Y' or 'N'. "
						+ "Use @PlsqlBoolean to declare other literals.");

		assertThatThrownBy(() -> broken.deliveryDeadline(LocalDateTime.now()))
				.isInstanceOf(PlsqlMappingException.class)
				.extracting(e -> Docs.asDocumented(e.getMessage(), Broken.class))
				.isEqualTo("deliveryDeadline -> SHOP.CUSTOMERS_PKG.DELIVERY_DEADLINE: P_ORDERED_AT is TIMESTAMP WITH "
						+ "TIME ZONE, and LocalDateTime has no time zone; declare OffsetDateTime, ZonedDateTime or Instant");

		assertThatThrownBy(() -> broken.normalizeAddress(new Broken.Address("Rua A 1", "Fortaleza")))
				.isInstanceOf(PlsqlMappingException.class)
				.extracting(e -> Docs.asDocumented(e.getMessage(), Broken.class))
				.isEqualTo("Address does not cover attribute(s) of POSTAL_ADDRESS_T: [ZIP_CODE VARCHAR2]. "
						+ "Available properties: [STREET, CITY]. Use @PlsqlAttr to declare the name.");
	}
}
