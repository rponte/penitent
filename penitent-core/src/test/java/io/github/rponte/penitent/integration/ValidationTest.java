package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlAttr;
import io.github.rponte.penitent.PlsqlBoolean;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.PlsqlRoutine;
import io.github.rponte.penitent.exception.PlsqlExecutionException;
import io.github.rponte.penitent.exception.PlsqlMappingException;

class ValidationTest extends OracleIntegrationTest {

	record AddressWithoutNum(String street) {
	}

	record CustomerWithBrokenAddress(List<ObjectTypesTest.Address> addresses, Long id,
			@PlsqlAttr("FULL_NAME") String name, Boolean active,
			@PlsqlBoolean(trueValue = "1", falseValue = "0") Boolean vip, LocalDate birthday,
			ObjectTypesTest.Status status, AddressWithoutNum address, String[] nicknames) {
	}

	@PlsqlPackage("FIXTURE_COMPOSITES")
	interface NestedMismatch {
		@PlsqlRoutine("CUSTOMER_NAME")
		String customerName(CustomerWithBrokenAddress value);
	}

	@PlsqlPackage("FIXTURE_COMPOSITES")
	interface ElementMismatch {
		@PlsqlRoutine("ECHO_ADDRESSES")
		List<AddressWithoutNum> echoAddresses(List<ObjectTypesTest.Address> value);
	}

	@PlsqlPackage("FIXTURE_SCALARS")
	interface SeveralMismatches {
		void routineThatDoesNotExist();

		String noArguments();

		void constant();

		int plus(int a);

		default int notMapped() {
			return 1;
		}
	}

	@PlsqlPackage("FIXTURE_SCALARS")
	interface OneMismatch {
		int constant();

		void routineThatDoesNotExist();
	}

	@PlsqlPackage("FIXTURE_COMPOSITES")
	interface CursorNotAList {
		@PlsqlRoutine("ITEMS")
		CursorTest.Item notAList(int count);
	}

	@Test
	@DisplayName("interfaces that work pass, without calling any routine")
	void valid_interfaces() {
		assertThatCode(() -> {
			PlsqlPackages.validate(pkg(ScalarsTest.Scalars.class));
			PlsqlPackages.validate(pkg(ObjectTypesTest.Composites.class));
			PlsqlPackages.validate(pkg(RecordAndRowtypeTest.Composites.class));
			PlsqlPackages.validate(pkg(AnonymousBlockTest.Periods.class));
		}).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("finds, without calling, the same mismatches the calls of OutParametersTest find")
	void same_mismatches_as_the_calls() {
		assertThatThrownBy(() -> PlsqlPackages.validate(pkg(OutParametersTest.Outputs.class)))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageStartingWith("6 methods of")
				.hasMessageContaining("\n  - collectionWithoutList (P_VALUE)")
				.hasMessageContaining("\n  - cursorWithoutList (P_VALUE)")
				.hasMessageContaining("\n  - holderAsIn")
				.hasMessageContaining("\n  - rawHolder")
				.hasMessageContaining("\n  - wildcardHolder")
				.hasMessageContaining("\n  - withoutHolder");
	}

	@Test
	@DisplayName("every mismatch of the interface comes in one exception, one method per line")
	void collects_every_mismatch() {
		assertThatThrownBy(() -> PlsqlPackages.validate(pkg(SeveralMismatches.class)))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageStartingWith("4 methods of " + SeveralMismatches.class.getName() + " do not match")
				.hasMessageContaining("\n  - constant")
				.hasMessageContaining("\n  - noArguments")
				.hasMessageContaining("\n  - plus")
				.hasMessageContaining("\n  - routineThatDoesNotExist")
				.hasMessageNotContaining("notMapped")
				.satisfies(e -> assertThat(e.getSuppressed()).hasSize(4));
	}

	@Test
	@DisplayName("a single mismatch keeps its message, prefixed by the method")
	void single_mismatch() {
		assertThatThrownBy(() -> PlsqlPackages.validate(pkg(OneMismatch.class)))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageStartingWith(OneMismatch.class.getName() + ".routineThatDoesNotExist")
				.hasMessageContaining("Available");
	}

	@Test
	@DisplayName("a nested OBJECT that does not match is found without sending a value")
	void nested_composite() {
		assertThatThrownBy(() -> PlsqlPackages.validate(pkg(NestedMismatch.class)))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("customerName")
				.hasMessageContaining("AddressWithoutNum does not cover attribute(s)")
				.hasMessageContaining("NUM");
	}

	@Test
	@DisplayName("the element of a returned collection is checked too")
	void collection_element() {
		assertThatThrownBy(() -> PlsqlPackages.validate(pkg(ElementMismatch.class)))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("AddressWithoutNum does not cover attribute(s)");
	}

	@Test
	@DisplayName("a cursor must land in a List; its columns are only known on the call")
	void cursor_not_a_list() {
		assertThatThrownBy(() -> PlsqlPackages.validate(pkg(CursorNotAList.class)))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("returns a cursor; declare List<T>");
	}

	@Test
	@DisplayName("a block is checked against its placeholders")
	void block() {
		assertThatThrownBy(() -> PlsqlPackages.validate(pkg(AnonymousBlockTest.BlockWithPlaceholderWithoutParameter.class)))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("doesNotExist")
				.hasMessageContaining("matches no parameter");
	}

	@Test
	@DisplayName("a database failure is not a mapping error: it is translated, as on a call")
	void database_failure() {
		DataSource down = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
				new Class<?>[] { DataSource.class }, (proxy, method, args) -> {
					throw new SQLException("database is down");
				});
		ScalarsTest.Scalars scalars = PlsqlPackages.create(ScalarsTest.Scalars.class, down);

		assertThatThrownBy(() -> PlsqlPackages.validate(scalars))
				.isInstanceOf(PlsqlExecutionException.class)
				.hasRootCauseMessage("database is down");
	}

	@Test
	@DisplayName("only a proxy of this library can be validated")
	void not_a_proxy() {
		Runnable other = (Runnable) Proxy.newProxyInstance(Runnable.class.getClassLoader(),
				new Class<?>[] { Runnable.class }, (proxy, method, args) -> null);

		assertThatThrownBy(() -> PlsqlPackages.validate("text"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("is not a proxy created by PlsqlPackages");
		assertThatThrownBy(() -> PlsqlPackages.validate(other))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> PlsqlPackages.validate(null))
				.isInstanceOf(NullPointerException.class);
	}
}
