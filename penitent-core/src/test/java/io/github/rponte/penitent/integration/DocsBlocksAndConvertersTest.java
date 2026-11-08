package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlBlock;
import io.github.rponte.penitent.PlsqlBoolean;
import io.github.rponte.penitent.PlsqlOut;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.PlsqlType;
import io.github.rponte.penitent.convert.PlsqlConvert;
import io.github.rponte.penitent.convert.PlsqlConverter;
import io.github.rponte.penitent.convert.PlsqlConverters;
import io.github.rponte.penitent.exception.PlsqlMappingException;

/**
 * The examples of docs/anonymous-blocks.md and docs/converters.md, as the pages show them. A change
 * here changes the pages, and the other way around.
 */
class DocsBlocksAndConvertersTest extends OracleIntegrationTest {

	record Address(String street, String city, String zipCode) {
	}

	enum OrderStatus {
		OPEN, CANCELLED
	}

	record OrderRow(Long id, BigDecimal total, OrderStatus status) {
	}

	interface AddressBlocks {
		@PlsqlBlock("declare l postal_address_t := :address; begin :result := l.formatted(); end;")
		String format(@PlsqlType("POSTAL_ADDRESS_T") Address address);

		@PlsqlBlock("declare l tag_list_t := :tags; begin :result := l.count; end;")
		int countTags(@PlsqlType("TAG_LIST_T") List<String> tags);

		@PlsqlBlock(value = "begin :result := customers_pkg.tags_of(:customerId); end;", resultType = "TAG_LIST_T")
		List<String> tagsOf(long customerId);

		@PlsqlBlock("begin open :result for select id, total, status from orders where customer_id = :customerId"
				+ " order by id; end;")
		List<OrderRow> ordersOf(long customerId);
	}

	static class Broken {

		interface AddressBlocks {
			@PlsqlBlock("declare l tag_list_t := :tags; begin :result := l.count; end;")
			int countTags(List<String> tags);
		}
	}

	public record TaxId(String value) {
	}

	public enum Tier {
		GOLD("G"), SILVER("S");

		private final String code;

		Tier(String code) {
			this.code = code;
		}

		public String code() {
			return code;
		}
	}

	record Customer(Long id, String name, TaxId taxId, Tier tier,
			@PlsqlBoolean(trueValue = "S", falseValue = "N") boolean vip,
			LocalDate bornOn, LocalDateTime createdAt) {
	}

	@PlsqlPackage("CUSTOMERS_PKG")
	interface Customers {
		Long findByTaxId(TaxId taxId);

		Customer findCustomer(long id);

		Tier tierOf(long id);

		int countByTier(Tier tier);
	}

	public static class TaxIdConverter implements PlsqlConverter<TaxId, String> {

		@Override
		public String toDatabase(TaxId taxId) {
			return taxId == null ? null : taxId.value();
		}

		@Override
		public TaxId fromDatabase(String value) {
			return value == null ? null : new TaxId(value);
		}
	}

	@PlsqlPackage("CUSTOMERS_PKG")
	interface CustomerLookup {
		Long findByTaxId(@PlsqlConvert(TaxIdConverter.class) TaxId taxId);
	}

	@PlsqlPackage("ORDERS_PKG")
	interface Orders {
		void placeOrder(long customerId, BigDecimal total, PlsqlOut<Long> orderId);
	}

	@Test
	@DisplayName("blocks: a member method, a collection in and out, and a cursor")
	void blocks() {
		AddressBlocks blocks = PlsqlPackages.create(AddressBlocks.class, DATA_SOURCE);

		assertThat(blocks.format(new Address("Rua das Flores 10", "Fortaleza", "60000000")))
				.isEqualTo("Rua das Flores 10, Fortaleza 60000000");
		assertThat(blocks.countTags(List.of("vip", "newsletter"))).isEqualTo(2);
		assertThat(blocks.tagsOf(1)).containsExactly("vip", "newsletter");

		PlsqlOut<Long> orderId = new PlsqlOut<>();
		PlsqlPackages.create(Orders.class, DATA_SOURCE).placeOrder(301, new BigDecimal("75.00"), orderId);
		assertThat(blocks.ordersOf(301))
				.containsExactly(new OrderRow(orderId.get(), new BigDecimal("75"), OrderStatus.OPEN));
	}

	@Test
	@DisplayName("blocks: a List without @PlsqlType fails before the call")
	void block_without_type() {
		Broken.AddressBlocks broken = PlsqlPackages.create(Broken.AddressBlocks.class, DATA_SOURCE);

		assertThatThrownBy(() -> broken.countTags(List.of("vip")))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessage("countTags: parameter tags is a List; in a block, declare its Oracle type with @PlsqlType, "
						+ "as in @PlsqlType(\"NAME_LIST_T\")");
	}

	private static final PlsqlConverters CONVERTERS = PlsqlConverters.of(
			PlsqlConverter.ofNullable(TaxId.class, String.class, TaxId::value, TaxId::new),
			PlsqlConverter.enumByCode(Tier.class, String.class, Tier::code));

	@Test
	@DisplayName("converters: a value object and an enum by code, in parameters, results and attributes")
	void converters() {
		Customers customers = PlsqlPackages.create(Customers.class, DATA_SOURCE, CONVERTERS);

		assertThat(customers.findByTaxId(new TaxId("12345678901"))).isEqualTo(1L);
		assertThat(customers.tierOf(1)).isEqualTo(Tier.GOLD);
		assertThat(customers.countByTier(Tier.SILVER)).isEqualTo(1);

		assertThat(customers.findCustomer(1).taxId()).isEqualTo(new TaxId("12345678901"));
		assertThat(customers.findCustomer(1).tier()).isEqualTo(Tier.GOLD);
		assertThat(customers.findCustomer(2).taxId()).isNull();
	}

	@Test
	@DisplayName("converters: @PlsqlConvert at a single point")
	void annotation() {
		CustomerLookup lookup = PlsqlPackages.create(CustomerLookup.class, DATA_SOURCE);

		assertThat(lookup.findByTaxId(new TaxId("12345678901"))).isEqualTo(1L);
	}

	@Test
	@DisplayName("converters: the messages quoted on the page")
	void converter_messages() {
		Customers strict = PlsqlPackages.create(Customers.class, DATA_SOURCE, PlsqlConverters.of(
				PlsqlConverter.of(TaxId.class, String.class, TaxId::value, TaxId::new),
				PlsqlConverter.enumByCode(Tier.class, String.class, Tier::code)));

		assertThatThrownBy(() -> strict.findByTaxId(null))
				.isInstanceOf(PlsqlMappingException.class)
				.extracting(e -> Docs.asDocumented(e.getMessage(), DocsBlocksAndConvertersTest.class))
				.isEqualTo("findByTaxId -> SHOP.CUSTOMERS_PKG.FIND_BY_TAX_ID: parameter P_TAX_ID: the converter of "
						+ "TaxId failed to write NULL: java.lang.NullPointerException");
	}
}
