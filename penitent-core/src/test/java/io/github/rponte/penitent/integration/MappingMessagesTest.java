package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlBind;
import io.github.rponte.penitent.PlsqlBlock;
import io.github.rponte.penitent.PlsqlOut;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.PlsqlParam;
import io.github.rponte.penitent.PlsqlSchema;
import io.github.rponte.penitent.convert.PlsqlConverter;
import io.github.rponte.penitent.convert.PlsqlConverters;
import io.github.rponte.penitent.exception.PlsqlMappingException;

/**
 * One shape for every mapping error of a method: {@code method(types): detail}. The method's name
 * and parameter types tell the overloads apart, and the detail says what was looked up and what
 * exists, without repeating the method.
 */
class MappingMessagesTest extends OracleIntegrationTest {

	record Address(String street, String city) {
	}

	record TaxId(String value) {
	}

	@PlsqlPackage("ORDERS_PKG")
	interface Orders {
		BigDecimal calculateFrieght(String origin, String destination);

		BigDecimal calculateFreight(String origin);

		String cancelOrder(long orderId);

		void orderStatus(long orderId);

		void placeOrder(long customerId, BigDecimal total, Long orderId);

		void cancelOrder(PlsqlOut<Long> orderId);

		String describe(Optional<String> status);
	}

	@PlsqlPackage("ORDER_PKG")
	interface MissingPackage {
		String orderStatus(long orderId);
	}

	@PlsqlPackage(value = "ORDERS_PKG", bind = PlsqlBind.BY_NAME)
	interface ByName {
		long countOrders(@PlsqlParam("P_CUSTOMER") long customerId);

		long countOrders(@PlsqlParam("P_STATUS") String status);

		long countOrders(@PlsqlParam("P_CUSTOMER_ID") long customerId, @PlsqlParam("P_CUSTOMER_ID") long again);
	}

	@PlsqlPackage("CUSTOMERS_PKG")
	interface Customers {
		boolean vipFlag(long id);

		LocalDateTime deliveryDeadline(LocalDateTime orderedAt);

		Address normalizeAddress(Address address);

		Long findByTaxId(TaxId taxId);

		String ordersOf(long customerId);
	}

	@PlsqlSchema
	interface Money {
		long roundToCents(BigDecimal value);
	}

	interface Blocks {
		@PlsqlBlock("declare l tag_list_t := :tags; begin :result := l.count; end;")
		int countTags(List<String> tags);

		@PlsqlBlock("begin :result := customers_pkg.tags_of(:customerId); end;")
		List<String> tagsOf(long customerId);

		@PlsqlBlock("begin :result := :doesNotExist; end;")
		String unknownPlaceholder(String value);
	}

	/** A mapping error of {@code call}, which must start with {@code signature: } and say the rest once. */
	private record Case(String signature, Consumer<Void> call) {
	}

	private static Case of(String signature, Runnable call) {
		return new Case(signature, ignored -> call.run());
	}

	@Test
	@DisplayName("every mapping error of a method starts with method(types):, once, and the detail does not repeat it")
	void one_shape() {
		Orders orders = pkg(Orders.class);
		ByName byName = pkg(ByName.class);
		Customers customers = PlsqlPackages.create(Customers.class, DATA_SOURCE, PlsqlConverters.of(
				PlsqlConverter.of(TaxId.class, String.class, TaxId::value, TaxId::new)));
		Blocks blocks = pkg(Blocks.class);
		OverloadingTest.Overload overload = pkg(OverloadingTest.Overload.class);

		List<Case> cases = List.of(
				of("calculateFrieght(String, String)", () -> orders.calculateFrieght("SP", "CE")),
				of("calculateFreight(String)", () -> orders.calculateFreight("SP")),
				of("cancelOrder(long)", () -> orders.cancelOrder(1)),
				of("orderStatus(long)", () -> orders.orderStatus(1)),
				of("placeOrder(long, BigDecimal, Long)", () -> orders.placeOrder(1, BigDecimal.ONE, 1L)),
				of("cancelOrder(PlsqlOut)", () -> orders.cancelOrder(new PlsqlOut<Long>())),
				of("describe(Optional)", () -> orders.describe(Optional.empty())),
				of("orderStatus(long)", () -> pkg(MissingPackage.class).orderStatus(1)),
				of("countOrders(long)", () -> byName.countOrders(1)),
				of("countOrders(String)", () -> byName.countOrders("OPEN")),
				of("countOrders(long, long)", () -> byName.countOrders(1, 1)),
				of("vipFlag(long)", () -> customers.vipFlag(1)),
				of("deliveryDeadline(LocalDateTime)", () -> customers.deliveryDeadline(LocalDateTime.now())),
				of("normalizeAddress(Address)", () -> customers.normalizeAddress(new Address("a", "b"))),
				of("findByTaxId(TaxId)", () -> customers.findByTaxId(null)),
				of("ordersOf(long)", () -> customers.ordersOf(1)),
				of("roundToCents(BigDecimal)", () -> pkg(Money.class).roundToCents(null)),
				of("countTags(List)", () -> blocks.countTags(List.of("a"))),
				of("tagsOf(long)", () -> blocks.tagsOf(1)),
				of("unknownPlaceholder(String)", () -> blocks.unknownPlaceholder("x")),
				of("sameJava(int)", () -> overload.sameJava(1)),
				of("noVersion(byte[])", () -> overload.noVersion(new byte[] { 1 })));

		List<String> problems = new ArrayList<>();
		for (Case c : cases) {
			String name = c.signature().substring(0, c.signature().indexOf('('));
			try {
				c.call().accept(null);
				problems.add(c.signature() + ": no error");
			} catch (PlsqlMappingException e) {
				String message = e.getMessage();
				String prefix = c.signature() + ": ";
				if (!message.startsWith(prefix)) {
					problems.add("does not start with '" + prefix + "': " + message);
					continue;
				}
				String detail = message.substring(prefix.length());
				if (detail.startsWith(name + " ") || detail.startsWith(name + ":") || detail.contains(name + " -> ")
						|| detail.contains(c.signature())) {
					problems.add("repeats the method: " + message);
				}
			} catch (RuntimeException e) {
				problems.add(c.signature() + ": not a mapping error: " + e);
			}
		}
		assertThat(problems).isEmpty();
	}

	@Test
	@DisplayName("validate lists each method once, in the same shape, under the interface")
	void validate_in_the_same_shape() {
		Orders orders = pkg(Orders.class);

		assertThatThrownBy(() -> PlsqlPackages.validate(orders))
				.isInstanceOf(PlsqlMappingException.class)
				.satisfies(e -> {
					List<String> lines = e.getMessage().lines().skip(1).toList();
					assertThat(lines).isNotEmpty().allSatisfy(line -> assertThat(line).matches("  - \\w+\\([^)]*\\): .+"));
				});
	}

	@Test
	@DisplayName("a validation with a single error names the interface, the method and its types")
	void validate_a_single_error() {
		MissingPackage missing = pkg(MissingPackage.class);

		assertThatThrownBy(() -> PlsqlPackages.validate(missing))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageStartingWith(MissingPackage.class.getName() + ".orderStatus(long): ");
	}

	@Test
	@DisplayName("the versions of an overloaded routine are shown as signatures, without a null for the return value")
	void versions_as_signatures() {
		OverloadingTest.Overload overload = pkg(OverloadingTest.Overload.class);

		assertThatThrownBy(() -> overload.noVersion(new byte[] { 1 }))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("(P_VALUE NUMBER) RETURN VARCHAR2")
				.hasMessageContaining("(P_A NUMBER, P_B NUMBER) RETURN VARCHAR2")
				.hasMessageNotContaining("null ");
	}

	@Test
	@DisplayName("the routines that exist are listed once each, even when overloaded")
	void available_without_repetition() {
		Orders orders = pkg(Orders.class);

		assertThatThrownBy(() -> orders.calculateFrieght("SP", "CE"))
				.isInstanceOf(PlsqlMappingException.class)
				.extracting(e -> Docs.asDocumented(e.getMessage(), getClass()))
				.isEqualTo("calculateFrieght(String, String): routine SHOP.ORDERS_PKG.CALCULATE_FRIEGHT does not exist."
						+ " Available: APPLY_DISCOUNT, CALCULATE_FREIGHT, CANCEL_ORDER, COUNT_ORDERS, DESCRIBE, ORDER_STATUS,"
						+ " PLACE_ORDER");
	}
}
