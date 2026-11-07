package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlRoutine;
import io.github.rponte.penitent.exception.PlsqlMappingException;

class CollectionsTest extends OracleIntegrationTest {

	record Address(String street, Integer num) {
	}

	@PlsqlPackage("FIXTURE_COMPOSITES")
	interface Composites {
		List<Address> echoAddresses(List<Address> value);

		@PlsqlRoutine("ECHO_ADDRESSES")
		List<Address> echoAddressesFromArray(Address[] value);

		List<Address> nullAddresses();

		List<String> echoNames(List<String> value);

		List<BigDecimal> echoNumbers(int[] value);

		List<String> echoNamesPkg(List<String> value);

		@PlsqlRoutine("ECHO_NAMES")
		List<String> echoNamesFromSet(Set<String> value);

		@PlsqlRoutine("ECHO_NAMES")
		ArrayList<String> echoNamesAsArrayList(List<String> value);

		@PlsqlRoutine("ECHO_NAMES")
		String[] echoNamesAsArray(List<String> value);

		@PlsqlRoutine("ECHO_NAMES")
		Set<String> echoNamesAsSet(List<String> value);

		@PlsqlRoutine("ECHO_NAMES")
		Collection<String> echoNamesAsCollection(List<String> value);
	}

	@PlsqlPackage("FIXTURE_SHAPES")
	interface Shapes {
		List<List<String>> echoMatrix(List<List<String>> value);

		List<String> echoAssocByInteger(List<String> value);

		List<String> echoAssocByText(List<String> value);

		@PlsqlRoutine("ECHO_ASSOC_BY_TEXT")
		Map<String, String> echoAssocAsMap(Map<String, String> value);

		@PlsqlRoutine("ECHO_MATRIX")
		List<List<String>> echoMatrixFromArray(String[][] value);

		@PlsqlRoutine("ECHO_MATRIX")
		List<String> echoMatrixFlat(List<String> value);

		@PlsqlRoutine("ECHO_MATRIX")
		List<String> echoMatrixAsFlatList(List<List<String>> value);
	}

	record Grid(List<List<String>> cells) {
	}

	@PlsqlPackage("FIXTURE_NESTED")
	interface Nested {
		List<List<List<String>>> echoCube(List<List<List<String>>> value);

		List<List<String>> echoMatrixPkg(List<List<String>> value);

		List<List<String>> nullMatrix();

		Grid echoGrid(Grid value);
	}

	@PlsqlPackage("FIXTURE_COMPOSITES")
	interface FlatAsMatrix {
		@PlsqlRoutine("ECHO_NAMES")
		List<List<String>> echoNames(List<List<String>> value);
	}

	private final Composites composites = pkg(Composites.class);
	private final Shapes shapes = pkg(Shapes.class);

	@Test
	@DisplayName("schema-level TABLE OF OBJECT, receiving a List or an array")
	void collection_of_object() {
		List<Address> addresses = List.of(new Address("Street A", 1), new Address("Street B", 2));

		assertThat(composites.echoAddresses(addresses)).isEqualTo(addresses);
		assertThat(composites.echoAddressesFromArray(addresses.toArray(Address[]::new))).isEqualTo(addresses);
	}

	@Test
	@DisplayName("schema-level TABLE OF scalar")
	void collection_of_scalar() {
		assertThat(composites.echoNames(List.of("a", "b"))).containsExactly("a", "b");
	}

	@Test
	@DisplayName("VARRAY, receiving an array of primitives")
	void varray() {
		assertThat(composites.echoNumbers(new int[] { 1, 2, 3 }))
				.containsExactly(new BigDecimal("1"), new BigDecimal("2"), new BigDecimal("3"));
	}

	@Test
	@DisplayName("TABLE OF scalar declared in the package spec")
	void package_collection() {
		assertThat(composites.echoNamesPkg(List.of("a", "b"))).containsExactly("a", "b");
	}

	@Test
	@DisplayName("empty stays empty, a null element stays null, and a NULL return becomes an empty list")
	void empties_and_nulls() {
		assertThat(composites.echoNames(List.of())).isEmpty();
		assertThat(composites.echoNames(Arrays.asList("a", null))).containsExactly("a", null);
		assertThat(composites.echoAddresses(Arrays.asList(new Address("Street A", 1), null)))
				.containsExactly(new Address("Street A", 1), null);
		assertThat(composites.nullAddresses()).isEmpty();
	}

	@Test
	@DisplayName("null in place of the whole collection")
	void whole_collection_null() {
		assertThat(composites.echoAddresses(null)).isEmpty();
		assertThat(composites.echoNames(null)).isEmpty();
		assertThat(composites.echoNumbers(null)).isEmpty();
		assertThat(composites.echoNamesPkg(null)).isEmpty();
	}

	@Test
	@DisplayName("Set as a parameter")
	void set_as_parameter() {
		assertThat(composites.echoNamesFromSet(Set.of("a"))).containsExactly("a");
	}

	@Test
	@DisplayName("ArrayList as the return")
	void array_list_as_return() {
		assertThat(composites.echoNamesAsArrayList(List.of("a"))).containsExactly("a");
	}

	@Test
	@DisplayName("a return that is not a List says what to declare")
	void return_that_is_not_a_list() {
		assertThatThrownBy(() -> composites.echoNamesAsArray(List.of("a"))).isInstanceOf(PlsqlMappingException.class);
		assertThatThrownBy(() -> composites.echoNamesAsSet(List.of("a"))).isInstanceOf(PlsqlMappingException.class);
		assertThatThrownBy(() -> composites.echoNamesAsCollection(List.of("a"))).isInstanceOf(PlsqlMappingException.class);
	}

	@Test
	@DisplayName("collection of collections, in both directions, from a List or an array")
	void collection_of_collections() {
		assertThat(shapes.echoMatrix(List.of(List.of("a")))).containsExactly(List.of("a"));
		assertThat(shapes.echoMatrix(Arrays.asList(List.of("a", "b"), List.of(), null, Arrays.asList("c", null))))
				.containsExactly(List.of("a", "b"), List.of(), null, Arrays.asList("c", null));
		assertThat(shapes.echoMatrixFromArray(new String[][] { { "a" }, { "b", "c" } }))
				.containsExactly(List.of("a"), List.of("b", "c"));
		assertThat(shapes.echoMatrix(null)).isEmpty();
	}

	@Test
	@DisplayName("collection of collections three levels deep, declared in a package, and NULL")
	void deeper_collections() {
		Nested nested = pkg(Nested.class);
		List<List<List<String>>> cube = List.of(List.of(List.of("a"), List.of("b", "c")), List.of());

		assertThat(nested.echoCube(cube)).isEqualTo(cube);
		assertThat(nested.echoMatrixPkg(List.of(List.of("a"), List.of("b")))).containsExactly(List.of("a"), List.of("b"));
		assertThat(nested.nullMatrix()).isEmpty();
	}

	@Test
	@DisplayName("a Java element that does not match the levels of the collection is an error, before the call")
	void collection_levels_that_do_not_match() {
		assertThatThrownBy(() -> shapes.echoMatrixFlat(List.of("a")))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("MATRIX_T holds collections")
				.hasMessageContaining("NAME_LIST_T")
				.hasMessageContaining("not java.lang.String");
		assertThatThrownBy(() -> shapes.echoMatrixAsFlatList(List.of(List.of("a"))))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("the return value")
				.hasMessageContaining("MATRIX_T holds collections");
		assertThatThrownBy(() -> pkg(FlatAsMatrix.class).echoNames(List.of(List.of("a"))))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("NAME_LIST_T holds scalars, not collections");
	}

	@Test
	@DisplayName("collection of collections as an attribute is rejected with a clear error")
	void collection_of_collections_as_attribute() {
		assertThatThrownBy(() -> pkg(Nested.class).echoGrid(new Grid(List.of(List.of("a")))))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("CELLS of GRID_T")
				.hasMessageContaining("a collection of collections");
	}

	@Test
	@DisplayName("INDEX BY PLS_INTEGER associative array as a parameter is rejected")
	void assoc_by_integer() {
		// see IndexByTest for reading
		assertThatThrownBy(() -> shapes.echoAssocByInteger(List.of("a", "b")))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("starting at index 0");
	}

	@Test
	@DisplayName("INDEX BY VARCHAR2 associative array is rejected: JDBC does not carry the keys")
	void assoc_by_varchar2() {
		assertThatThrownBy(() -> shapes.echoAssocByText(List.of("a")))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("INDEX BY VARCHAR2")
				.hasMessageContaining("does not carry the keys");
		assertThatThrownBy(() -> shapes.echoAssocAsMap(Map.of("k", "v")))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("INDEX BY VARCHAR2");
	}
}
