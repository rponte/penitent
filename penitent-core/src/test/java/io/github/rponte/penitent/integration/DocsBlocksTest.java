package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlBlock;
import io.github.rponte.penitent.PlsqlOut;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.PlsqlType;
import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.integration.DocsBlocksTest.First.AddressBlocks;

/**
 * The examples of docs/anonymous-blocks.md. The regions are what the page shows, and
 * {@link DocsPagesTest} checks that the page still shows them. The page shows two versions of
 * {@code AddressBlocks}, so each one lives in its own holder.
 */
class DocsBlocksTest extends OracleIntegrationTest {

	record Address(String street, String city, String zipCode) {
	}

	enum OrderStatus {
		OPEN, CANCELLED
	}

	record OrderRow(Long id, BigDecimal total, OrderStatus status) {
	}

	static class First {

		// snippet: blocks/first
		public interface AddressBlocks {

		    @PlsqlBlock("declare l postal_address_t := :address; begin :result := l.formatted(); end;")
		    String format(@PlsqlType("POSTAL_ADDRESS_T") Address address);
		}
		// end-snippet
	}

	static class Types {

		// snippet: blocks/types
		public interface AddressBlocks {

		    // a collection parameter declares its Oracle type
		    @PlsqlBlock("declare l tag_list_t := :tags; begin :result := l.count; end;")
		    int countTags(@PlsqlType("TAG_LIST_T") List<String> tags);

		    // a collection :result declares its type in resultType
		    @PlsqlBlock(value = "begin :result := customers_pkg.tags_of(:customerId); end;", resultType = "TAG_LIST_T")
		    List<String> tagsOf(long customerId);

		    // a cursor opened with OPEN :result FOR needs nothing else
		    @PlsqlBlock("begin open :result for select id, total, status from orders where customer_id = :customerId order by id; end;")
		    List<OrderRow> ordersOf(long customerId);
		}
		// end-snippet
	}

	static class Broken {

		interface AddressBlocks {
			@PlsqlBlock("declare l tag_list_t := :tags; begin :result := l.count; end;")
			int countTags(List<String> tags);
		}
	}

	@PlsqlPackage("ORDERS_PKG")
	interface Orders {
		void placeOrder(long customerId, BigDecimal total, PlsqlOut<Long> orderId);
	}

	private final DataSource dataSource = DATA_SOURCE;

	@Test
	@DisplayName("a member method, through a block")
	void member_method() {
		// snippet: blocks/first-call
		AddressBlocks blocks = PlsqlPackages.create(AddressBlocks.class, dataSource);

		blocks.format(new Address("Rua das Flores 10", "Fortaleza", "60000000"));   // "Rua das Flores 10, Fortaleza 60000000"
		// end-snippet

		assertThat(blocks.format(new Address("Rua das Flores 10", "Fortaleza", "60000000")))
				.isEqualTo("Rua das Flores 10, Fortaleza 60000000");
	}

	@Test
	@DisplayName("a collection in and out, and a cursor")
	void types() {
		Types.AddressBlocks blocks = PlsqlPackages.create(Types.AddressBlocks.class, dataSource);

		assertThat(blocks.countTags(List.of("vip", "newsletter"))).isEqualTo(2);
		assertThat(blocks.tagsOf(1)).containsExactly("vip", "newsletter");

		PlsqlOut<Long> orderId = new PlsqlOut<>();
		PlsqlPackages.create(Orders.class, dataSource).placeOrder(301, new BigDecimal("75.00"), orderId);
		assertThat(blocks.ordersOf(301))
				.containsExactly(new OrderRow(orderId.get(), new BigDecimal("75"), OrderStatus.OPEN));
	}

	@Test
	@DisplayName("a List without @PlsqlType fails before the call")
	void block_without_type() {
		Broken.AddressBlocks broken = PlsqlPackages.create(Broken.AddressBlocks.class, dataSource);

		assertThatThrownBy(() -> broken.countTags(List.of("vip")))
				.isInstanceOf(PlsqlMappingException.class)
				.satisfies(e -> Docs.assertPagesShow(e.getMessage(), "anonymous-blocks.md#block-without-type"));
	}
}
