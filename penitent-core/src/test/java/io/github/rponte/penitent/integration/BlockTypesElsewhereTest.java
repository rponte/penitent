package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlBlock;
import io.github.rponte.penitent.PlsqlType;
import io.github.rponte.penitent.exception.PlsqlMappingException;

/**
 * A block's {@code @PlsqlType} and {@code resultType} naming a type of another schema, {@code PLSQL_OWNER}, or one
 * reached through a private synonym, as PL/SQL would name it.
 */
class BlockTypesElsewhereTest extends OracleIntegrationTest {

	record Address(String street, Integer num) {
	}

	record Entry(BigDecimal id, String description) {
	}

	private static final Address ADDRESS = new Address("Street A", 1);
	private static final Entry ENTRY = new Entry(BigDecimal.ONE, "one");

	interface Blocks {
		@PlsqlBlock("begin :result := :value.street; end;")
		String streetOfOwner(@PlsqlType("PLSQL_OWNER.OWNER_ADDRESS_T") Address value);

		@PlsqlBlock("begin :result := :value.street; end;")
		String streetOfSynonym(@PlsqlType("OWNER_ADDRESS_SYN") Address value);

		@PlsqlBlock(value = "begin :result := plsql_owner.owner_address_list_t(plsql_owner.owner_address_t('B', 2));"
				+ " end;", resultType = "PLSQL_OWNER.OWNER_ADDRESS_LIST_T")
		List<Address> addressesOfOwner();

		@PlsqlBlock(value = "begin :result := plsql_owner.owner_address_list_t(plsql_owner.owner_address_t('B', 2));"
				+ " end;", resultType = "OWNER_ADDRESSES_SYN")
		List<Address> addressesOfSynonym();

		@PlsqlBlock("begin :result := :value.description; end;")
		String descriptionOfOwnerRecord(@PlsqlType("PLSQL_OWNER.FIXTURE_OF_OWNER.OWNER_ENTRY") Entry value);

		@PlsqlBlock("begin :result := :value.description; end;")
		String descriptionOfSynonymRecord(@PlsqlType("FIXTURE_OWNER_SYNONYM.OWNER_ENTRY") Entry value);

		@PlsqlBlock("begin :result := :value.description; end;")
		String descriptionOfOwnerRow(@PlsqlType("PLSQL_OWNER.OWNER_ITEM%ROWTYPE") Entry value);

		@PlsqlBlock("begin :result := :value.description; end;")
		String descriptionOfSynonymRow(@PlsqlType("OWNER_ITEM_SYN%ROWTYPE") Entry value);

		@PlsqlBlock("begin :result := :value.street; end;")
		String streetOfMissing(@PlsqlType("PLSQL_OWNER.NO_SUCH_T") Address value);
	}

	private final Blocks blocks = pkg(Blocks.class);

	@Test
	@DisplayName("an object type of another schema, SCHEMA.TYPE")
	void object_of_another_schema() {
		assertThat(blocks.streetOfOwner(ADDRESS)).isEqualTo("Street A");
	}

	@Test
	@DisplayName("an object type through a private synonym")
	void object_through_synonym() {
		assertThat(blocks.streetOfSynonym(ADDRESS)).isEqualTo("Street A");
	}

	@Test
	@DisplayName("a collection of another schema, and through a synonym, as resultType")
	void collection() {
		assertThat(blocks.addressesOfOwner()).containsExactly(new Address("B", 2));
		assertThat(blocks.addressesOfSynonym()).containsExactly(new Address("B", 2));
	}

	@Test
	@DisplayName("a RECORD of a package of another schema, SCHEMA.PACKAGE.TYPE, and through the package's synonym")
	void package_record() {
		assertThat(blocks.descriptionOfOwnerRecord(ENTRY)).isEqualTo("one");
		assertThat(blocks.descriptionOfSynonymRecord(ENTRY)).isEqualTo("one");
	}

	@Test
	@DisplayName("a %ROWTYPE of a table of another schema, and through the table's synonym")
	void rowtype() {
		assertThat(blocks.descriptionOfOwnerRow(ENTRY)).isEqualTo("one");
		assertThat(blocks.descriptionOfSynonymRow(ENTRY)).isEqualTo("one");
	}

	@Test
	@DisplayName("a type that does not exist is refused, naming what was searched")
	void missing() {
		assertThatThrownBy(() -> blocks.streetOfMissing(ADDRESS))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("PLSQL_OWNER.NO_SUCH_T");
	}
}
