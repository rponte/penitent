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
 * Composite types whose names need quotes, being mixed case, such as {@code CREATE TABLE "Mixed"}. Each one has an
 * unquoted namesake of another shape, {@code MIXED}, which a name that lost its quotes on the way to the driver would
 * reach instead.
 */
class QuotedTypeNamesTest extends OracleIntegrationTest {

	record Item(BigDecimal id, String name) {
	}

	@PlsqlPackage("FIXTURE_QUOTED")
	interface Quoted {
		Item echoRowtype(Item value);

		Item echoObject(Item value);

		Item echoRecord(Item value);

		Item echoTwoWordsRow(Item value);

		Item echoTwoWordsObject(Item value);

		Item echoTwoWordsRecord(Item value);

		List<Item> echoTwoWordsList(List<Item> value);

		List<Item> echoTwoWordsRows(List<Item> value);
	}

	@PlsqlPackage("\"Mixed_Pkg\"")
	interface MixedPackage {
		Item echoRecord(Item value);
	}

	interface Blocks {
		@PlsqlBlock("begin :result := :value.name; end;")
		String nameOfRow(@PlsqlType("\"Mixed\"%ROWTYPE") Item value);

		@PlsqlBlock("begin :result := :value.name; end;")
		String nameOfObject(@PlsqlType("\"Mixed_T\"") Item value);

		@PlsqlBlock("begin :result := :value.name; end;")
		String nameOfTwoWordsRow(@PlsqlType("\"Two Words\"%ROWTYPE") Item value);
	}

	private static final Item ITEM = new Item(BigDecimal.ONE, "one");

	@Test
	@DisplayName("a %ROWTYPE of a table whose name needs quotes")
	void rowtype() {
		assertThat(pkg(Quoted.class).echoRowtype(ITEM)).isEqualTo(ITEM);
	}

	@Test
	@DisplayName("an object type whose name needs quotes")
	void object() {
		assertThat(pkg(Quoted.class).echoObject(ITEM)).isEqualTo(ITEM);
	}

	@Test
	@DisplayName("a package RECORD whose name needs quotes")
	void record() {
		assertThat(pkg(Quoted.class).echoRecord(ITEM)).isEqualTo(ITEM);
	}

	@Test
	@DisplayName("a RECORD of a package whose name needs quotes")
	void record_of_quoted_package() {
		assertThat(pkg(MixedPackage.class).echoRecord(ITEM)).isEqualTo(ITEM);
	}

	@Test
	@DisplayName("a block's @PlsqlType of a %ROWTYPE whose table needs quotes")
	void block_rowtype() {
		assertThat(pkg(Blocks.class).nameOfRow(ITEM)).isEqualTo("one");
	}

	@Test
	@DisplayName("a block's @PlsqlType of an object type whose name needs quotes")
	void block_object() {
		assertThat(pkg(Blocks.class).nameOfObject(ITEM)).isEqualTo("one");
	}

	@Test
	@DisplayName("a %ROWTYPE of a table whose name has a space")
	void rowtype_with_a_space() {
		assertThat(pkg(Quoted.class).echoTwoWordsRow(ITEM)).isEqualTo(ITEM);
	}

	@Test
	@DisplayName("an object type whose name has a space")
	void object_with_a_space() {
		assertThat(pkg(Quoted.class).echoTwoWordsObject(ITEM)).isEqualTo(ITEM);
	}

	@Test
	@DisplayName("a block's @PlsqlType of a %ROWTYPE whose table has a space")
	void block_rowtype_with_a_space() {
		assertThat(pkg(Blocks.class).nameOfTwoWordsRow(ITEM)).isEqualTo("one");
	}

	@Test
	@DisplayName("a package RECORD whose name has a space")
	void record_with_a_space() {
		assertThat(pkg(Quoted.class).echoTwoWordsRecord(ITEM)).isEqualTo(ITEM);
	}

	@Test
	@DisplayName("a collection of an object type, both names with a space")
	void collection_with_a_space() {
		assertThat(pkg(Quoted.class).echoTwoWordsList(List.of(ITEM, ITEM))).containsExactly(ITEM, ITEM);
	}

	@Test
	@DisplayName("a package collection of a %ROWTYPE, both names with a space")
	void collection_of_rowtypes_with_a_space() {
		assertThat(pkg(Quoted.class).echoTwoWordsRows(List.of(ITEM))).containsExactly(ITEM);
	}
}
