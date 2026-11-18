package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlBlock;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlType;

/**
 * Composite types whose quoted names hold a dot, such as {@code "Dot.T"}, which a name split on its dots would cut in
 * two. A double quote cannot be part of a name: Oracle refuses it.
 */
class DotsInTypeNamesTest extends OracleIntegrationTest {

	record Item(BigDecimal id, String name) {
	}

	@PlsqlPackage("FIXTURE_ODD_NAMES")
	interface OddNames {
		Item echoDotRow(Item value);

		Item echoDotObject(Item value);

		List<Item> echoDotList(List<Item> value);

		Item echoDotRecord(Item value);

		List<Item> echoDotRows(List<Item> value);
	}

	@PlsqlPackage("\"Dot.Pkg\"")
	interface DotPackage {
		Item echoRecord(Item value);
	}

	interface Blocks {
		@PlsqlBlock("begin :result := :value.name; end;")
		String nameOfDotObject(@PlsqlType("\"Dot.T\"") Item value);

		@PlsqlBlock("begin :result := :value.name; end;")
		String nameOfDotRow(@PlsqlType("\"Dot.Table\"%ROWTYPE") Item value);
	}

	private static final Item ITEM = new Item(BigDecimal.ONE, "one");
	private static final List<Item> ITEMS = List.of(ITEM, new Item(BigDecimal.TEN, "ten"));

	@Test
	@DisplayName("a %ROWTYPE of a table whose name holds a dot")
	void dot_rowtype() {
		assertThat(pkg(OddNames.class).echoDotRow(ITEM)).isEqualTo(ITEM);
	}

	@Test
	@DisplayName("an object type and a collection whose names hold a dot")
	void dot_object() {
		assertThat(pkg(OddNames.class).echoDotObject(ITEM)).isEqualTo(ITEM);
		assertThat(pkg(OddNames.class).echoDotList(ITEMS)).isEqualTo(ITEMS);
	}

	@Test
	@DisplayName("a package RECORD whose name holds a dot")
	void dot_record() {
		assertThat(pkg(OddNames.class).echoDotRecord(ITEM)).isEqualTo(ITEM);
	}

	@Test
	@DisplayName("a collection of rows of a table whose name holds a dot")
	void dot_rows() {
		assertThat(pkg(OddNames.class).echoDotRows(ITEMS)).isEqualTo(ITEMS);
	}

	@Test
	@DisplayName("a RECORD of a package whose name holds a dot")
	void dot_package() {
		assertThat(pkg(DotPackage.class).echoRecord(ITEM)).isEqualTo(ITEM);
	}

	@Test
	@DisplayName("an anonymous block's @PlsqlType naming an object type with a dot")
	void block_dot_object() {
		assertThat(pkg(Blocks.class).nameOfDotObject(ITEM)).isEqualTo("one");
	}

	@Test
	@DisplayName("an anonymous block's @PlsqlType naming a %ROWTYPE of a table with a dot")
	void block_dot_row() {
		assertThat(pkg(Blocks.class).nameOfDotRow(ITEM)).isEqualTo("one");
	}
}
