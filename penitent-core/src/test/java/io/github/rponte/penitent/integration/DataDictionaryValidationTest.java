package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlAttr;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.PlsqlRoutine;
import io.github.rponte.penitent.PlsqlType;
import io.github.rponte.penitent.exception.PlsqlMappingException;

/**
 * Validation against the data dictionary exists to turn {@code ORA-06550} into an actionable
 * message. If the messages do not say what the database expects, it is useless — hence this
 * test asserts on the <b>text</b>, not only on the exception type.
 */
class DataDictionaryValidationTest extends OracleIntegrationTest {

	@PlsqlPackage("FIXTURE_SCALARS")
	interface NonexistentRoutine {
		void routineThatDoesNotExist();
	}

	@PlsqlPackage("PACKAGE_THAT_DOES_NOT_EXIST")
	interface NonexistentPackage {
		void anything();
	}

	@PlsqlPackage("FIXTURE_SCALARS")
	interface WrongArity {
		void register(int id, String description, String leftover);

		@PlsqlRoutine("WITH_DEFAULT")
		int withDefaultOthers(int a, int b, int c);
	}

	@PlsqlPackage("FIXTURE_SCALARS")
	interface ProcedureWithResult {
		String noArguments();
	}

	@PlsqlPackage("FIXTURE_SCALARS")
	interface FunctionMappedToVoid {
		void constant();
	}

	@PlsqlPackage("FIXTURE_SCALARS")
	interface WithParameterOut {
		void withOut(int a, Integer b);
	}

	interface WithoutAnnotation {
		void nothing();
	}

	@Test
	@DisplayName("missing routine: says what exists in the package")
	void missing_routine() {
		assertThatThrownBy(() -> pkg(NonexistentRoutine.class).routineThatDoesNotExist())
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("ROUTINE_THAT_DOES_NOT_EXIST")
				.hasMessageContaining("Available")
				.hasMessageContaining("NO_ARGUMENTS");
	}

	@Test
	@DisplayName("missing package: not confused with a missing routine")
	void missing_package() {
		assertThatThrownBy(() -> pkg(NonexistentPackage.class).anything())
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("PACKAGE_THAT_DOES_NOT_EXIST")
				.hasMessageContaining("does not exist or is not visible");
	}

	@Test
	@DisplayName("wrong arity: names the parameters the database expects")
	void wrong_arity() {
		assertThatThrownBy(() -> pkg(WrongArity.class).register(1, "x", "leftover"))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("declares 3 parameter")
				.hasMessageContaining("expects 2")
				.hasMessageContaining("P_ID");
	}

	@Test
	@DisplayName("wrong arity with DEFAULT: the message shows the range")
	void wrong_arity_with_default() {
		assertThatThrownBy(() -> pkg(WrongArity.class).withDefaultOthers(1, 2, 3))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("expects 1 to 2");
	}

	@Test
	@DisplayName("a procedure with a declared return, and a function mapped to void, do not pass silently")
	void procedure_and_function_mismatch() {
		assertThatThrownBy(() -> pkg(ProcedureWithResult.class).noArguments())
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("is a procedure and returns nothing")
				.hasMessageContaining("use void");

		assertThatThrownBy(() -> pkg(FunctionMappedToVoid.class).constant())
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("is a function that returns")
				.hasMessageContaining("declare the return type");
	}

	@Test
	@DisplayName("OUT parameter without PlsqlOut says what to declare")
	void out_parameter_without_holder() {
		assertThatThrownBy(() -> pkg(WithParameterOut.class).withOut(1, null))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("P_B")
				.hasMessageContaining("declare PlsqlOut<T>");
	}

	@Test
	@DisplayName("an interface without @PlsqlPackage, and a class instead of an interface, fail at creation")
	void factory_misuse() {
		assertThatThrownBy(() -> PlsqlPackages.create(WithoutAnnotation.class, DATA_SOURCE))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("needs @PlsqlPackage or @PlsqlSchema");

		assertThatThrownBy(() -> PlsqlPackages.create(String.class, DATA_SOURCE))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("must be an interface");
	}

	// --- composite types -------------------------------------------------------------

	record AddressWithoutNum(String street) {
	}

	@PlsqlType("OTHER_TYPE")
	record AddressOfAnotherType(String street, Integer num) {
	}

	record AddressWithContestedName(@PlsqlAttr("STREET") String street, @PlsqlAttr("STREET") String alsoStreet, Integer num) {
	}

	/** {@code NICKNAMES} is a collection; a raw {@code List} does not say which class the element lands in. */
	@SuppressWarnings("rawtypes")
	record CustomerWithRawList(List addresses, Long id, String fullName, String active, String vip,
			java.time.LocalDate birthday, String status, Object address, List nicknames) {
	}

	record CustomerWithCollectionAsString(String nicknames, List<Object> addresses, Long id, String fullName,
			String active, String vip, java.time.LocalDate birthday, String status, Object address) {
	}

	@PlsqlPackage("FIXTURE_COMPOSITES")
	interface Composites {
		@PlsqlRoutine("ECHO_ADDRESS")
		AddressWithoutNum withoutNum(AddressWithoutNum value);

		@PlsqlRoutine("ECHO_ADDRESS")
		AddressOfAnotherType ofAnotherType(AddressOfAnotherType value);

		@PlsqlRoutine("ECHO_ADDRESS")
		AddressWithContestedName contested(AddressWithContestedName value);

		@PlsqlRoutine("CUSTOMER_NAME")
		String withRawList(CustomerWithRawList value);

		@PlsqlRoutine("CUSTOMER_NAME")
		String withCollectionAsString(CustomerWithCollectionAsString value);

		@PlsqlRoutine("ECHO_NAMES")
		String collectionAsString(List<String> value);

		@SuppressWarnings("rawtypes")
		@PlsqlRoutine("ECHO_NAMES")
		List rawList(List<String> value);
	}

	private final Composites composites = pkg(Composites.class);

	@Test
	@DisplayName("attribute without a property: names the attribute and what is available")
	void attribute_without_property() {
		assertThatThrownBy(() -> composites.withoutNum(new AddressWithoutNum("Street A")))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("does not cover attribute(s) of ADDRESS_T")
				.hasMessageContaining("NUM")
				.hasMessageContaining("Use @PlsqlAttr");
	}

	@Test
	@DisplayName("a @PlsqlType that disagrees with the data dictionary does not silently match attributes of another type")
	void disagreeing_plsql_type() {
		assertThatThrownBy(() -> composites.ofAnotherType(new AddressOfAnotherType("Street A", 1)))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("OTHER_TYPE")
				.hasMessageContaining("the dictionary says this argument is ADDRESS_T");
	}

	@Test
	@DisplayName("two properties claiming the same Oracle name is an error, not a tie-break")
	void contested_name() {
		assertThatThrownBy(() -> composites.contested(new AddressWithContestedName("a", "b", 1)))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("claim the same Oracle name");
	}

	@Test
	@DisplayName("collection attribute without an element type: says what to declare")
	void collection_attribute_without_element_type() {
		assertThatThrownBy(() -> composites.withRawList(
				new CustomerWithRawList(null, 1L, "x", null, null, null, null, null, null)))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("is a collection")
				.hasMessageContaining("declare List<T> or T[]");
	}

	@Test
	@DisplayName("collection as return: requires a List with an element type")
	void collection_return() {
		assertThatThrownBy(() -> composites.collectionAsString(List.of("a")))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("declare List<T>");

		assertThatThrownBy(() -> composites.rawList(List.of("a")))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("declare List<SomeType>");
	}

	@Test
	@DisplayName("a collection parameter must receive an array or a Collection")
	void collection_parameter_with_wrong_value() {
		@PlsqlPackage("FIXTURE_COMPOSITES")
		interface CollectionAsString {
			List<String> echoNames(String value);
		}

		assertThatThrownBy(() -> pkg(CollectionAsString.class).echoNames("no e list"))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("expected an array or Collection");
		assertThat(CollectionAsString.class.isInterface()).isTrue();
	}
}
