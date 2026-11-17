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
 * A {@code %ROWTYPE} of a table whose columns are not all plain and visible. PL/SQL leaves an invisible column out of
 * a {@code %ROWTYPE}, and keeps a virtual one; a column of an object type or a collection is one in the record too.
 */
class RowtypeColumnsTest extends OracleIntegrationTest {

	/** FIXTURE_PARCEL%ROWTYPE: DOUBLE_WEIGHT is virtual, and SECRET, invisible, is not part of it. */
	record Parcel(BigDecimal id, BigDecimal weight, BigDecimal doubleWeight) {
	}

	/** A row of FIXTURE_PARCEL with its invisible column, which a query has to name. */
	record ParcelSecret(BigDecimal id, String secret) {
	}

	/** ADDRESS_T. */
	record Address(String street, Integer num) {
	}

	/** FIXTURE_BUNDLE%ROWTYPE: an object type, a nested table and a VARRAY as columns. */
	record Bundle(BigDecimal id, Address address, List<String> names, List<BigDecimal> codes) {
	}

	@PlsqlPackage("FIXTURE_ROWTYPE_COLUMNS")
	interface RowtypeColumns {
		Parcel echoParcel(Parcel value);

		Bundle echoBundle(Bundle value);

		List<Bundle> echoBundles(List<Bundle> value);

		String describeBundle(Bundle value);
	}

	interface Blocks {
		@PlsqlBlock("begin :result := :value.weight; end;")
		BigDecimal weightOf(@PlsqlType("FIXTURE_PARCEL%ROWTYPE") Parcel value);

		@PlsqlBlock("begin open :result for select * from FIXTURE_PARCEL order by id; end;")
		List<Parcel> allParcels();

		@PlsqlBlock("begin open :result for select id, secret from FIXTURE_PARCEL order by id; end;")
		List<ParcelSecret> secrets();

		@PlsqlBlock(value = "declare l FIXTURE_BUNDLE%ROWTYPE; begin l.id := 1; l.address := ADDRESS_T('Main', 7);"
				+ " l.names := NAME_LIST_T('a'); l.codes := NUMBER_VARRAY_T(4); :result := l; end;",
				resultType = "FIXTURE_BUNDLE%ROWTYPE")
		Bundle bundle();
	}

	private final RowtypeColumns rowtypes = pkg(RowtypeColumns.class);

	private static final Parcel PARCEL = new Parcel(BigDecimal.ONE, BigDecimal.TEN, new BigDecimal("20"));

	private static final Bundle BUNDLE = new Bundle(BigDecimal.ONE, new Address("Main", 7), List.of("a", "b"),
			List.of(BigDecimal.ONE, BigDecimal.TEN));

	@Test
	@DisplayName("a %ROWTYPE of a table with a virtual and an invisible column, in and back")
	void virtual_and_invisible_columns() {
		assertThat(rowtypes.echoParcel(PARCEL)).isEqualTo(PARCEL);
		assertThat(pkg(Blocks.class).weightOf(PARCEL)).isEqualByComparingTo("10");
	}

	@Test
	@DisplayName("a cursor over a table with an invisible column: SELECT * leaves it out, a query that names it has it")
	void cursors_and_an_invisible_column() {
		assertThat(pkg(Blocks.class).allParcels()).containsExactly(
				new Parcel(BigDecimal.ONE, BigDecimal.TEN, new BigDecimal("20")),
				new Parcel(new BigDecimal("2"), new BigDecimal("20"), new BigDecimal("40")));
		assertThat(pkg(Blocks.class).secrets()).containsExactly(new ParcelSecret(BigDecimal.ONE, null),
				new ParcelSecret(new BigDecimal("2"), "shh"));
	}

	@Test
	@DisplayName("a %ROWTYPE with an object type, a nested table and a VARRAY as columns, in and back")
	void composite_columns() {
		assertThat(rowtypes.echoBundle(BUNDLE)).isEqualTo(BUNDLE);
		assertThat(rowtypes.describeBundle(BUNDLE)).isEqualTo("Main/2/2");
	}

	@Test
	@DisplayName("a collection of such %ROWTYPE records, and one read from a block")
	void collection_and_block() {
		assertThat(rowtypes.echoBundles(List.of(BUNDLE, BUNDLE))).containsExactly(BUNDLE, BUNDLE);
		assertThat(pkg(Blocks.class).bundle()).isEqualTo(new Bundle(BigDecimal.ONE, new Address("Main", 7),
				List.of("a"), List.of(new BigDecimal("4"))));
	}

	@Test
	@DisplayName("null columns of an object type and of collections come back as null")
	void null_composite_columns() {
		Bundle empty = new Bundle(BigDecimal.ONE, null, null, null);

		assertThat(rowtypes.echoBundle(empty)).isEqualTo(empty);
	}
}
