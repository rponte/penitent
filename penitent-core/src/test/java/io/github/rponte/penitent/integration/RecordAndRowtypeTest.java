package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlPackage;

class RecordAndRowtypeTest extends OracleIntegrationTest {

	record MiniItem(Long id, String description, BigDecimal quantity) {
	}

	record Item(Long id, String description, BigDecimal quantity, LocalDate registeredAt) {
	}

	record Address(String street, Integer num) {
	}

	record RichRecord(Long id, MiniItem item, Address address, List<String> nicknames, List<String> names) {
	}

	@PlsqlPackage("FIXTURE_COMPOSITES")
	interface Composites {
		MiniItem echoRecord(MiniItem value);

		List<MiniItem> echoRecords(List<MiniItem> value);

		Item echoRowtype(Item value);

		List<Item> echoRowtypes(List<Item> value);

		RichRecord echoRichRecord(RichRecord value);
	}

	private final Composites composites = pkg(Composites.class);

	private static final MiniItem MINI = new MiniItem(1L, "one", new BigDecimal("1.5"));
	private static final Item ITEM = new Item(1L, "one", new BigDecimal("1.5"), LocalDate.parse("2018-01-15"));

	@Test
	@DisplayName("package RECORD round trip")
	void package_record() {
		assertThat(composites.echoRecord(MINI)).isEqualTo(MINI);
	}

	@Test
	@DisplayName("collection of RECORD round trip")
	void collection_of_record() {
		assertThat(composites.echoRecords(List.of(MINI, new MiniItem(2L, "two", new BigDecimal("3")))))
				.containsExactly(MINI, new MiniItem(2L, "two", new BigDecimal("3")));
	}

	@Test
	@DisplayName("%ROWTYPE round trip")
	void rowtype() {
		assertThat(composites.echoRowtype(ITEM)).isEqualTo(ITEM);
	}

	@Test
	@DisplayName("collection of %ROWTYPE round trip")
	void collection_of_rowtype() {
		assertThat(composites.echoRowtypes(List.of(ITEM))).containsExactly(ITEM);
	}

	@Test
	@DisplayName("RECORD containing another RECORD, an OBJECT, and schema-level and package-level collections")
	void nested_record() {
		RichRecord rich = new RichRecord(9L, MINI, new Address("Street A", 1), List.of("a", "b"), List.of("c"));

		assertThat(composites.echoRichRecord(rich)).isEqualTo(rich);
	}

	@Test
	@DisplayName("null in place of the RECORD and the %ROWTYPE: a RECORD is never null, and comes back with null fields")
	void whole_record_null() {
		assertThat(composites.echoRecord(null)).isEqualTo(new MiniItem(null, null, null));
		assertThat(composites.echoRowtype(null)).isEqualTo(new Item(null, null, null, null));
	}
}
