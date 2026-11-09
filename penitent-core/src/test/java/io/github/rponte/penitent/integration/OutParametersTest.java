package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlBlock;
import io.github.rponte.penitent.PlsqlBoolean;
import io.github.rponte.penitent.PlsqlOut;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlRoutine;
import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.exception.PlsqlRoutineRaisedException;

class OutParametersTest extends OracleIntegrationTest {

	record Address(String street, Integer num) {
	}

	record Entry(Long id, String description) {
	}

	record Item(Long id, String description, BigDecimal quantity, LocalDate registeredAt) {
	}

	@PlsqlPackage("FIXTURE_OUT")
	interface Outputs {
		void plus(int a, int b, PlsqlOut<Integer> total);

		void twice(PlsqlOut<Integer> value);

		void several(int input, PlsqlOut<Integer> doubled, PlsqlOut<String> text, PlsqlOut<Integer> accumulated);

		String doubleAndReturn(int a, PlsqlOut<Integer> doubled);

		void outText(PlsqlOut<String> value);

		void outNull(PlsqlOut<Integer> value);

		void outDate(PlsqlOut<LocalDate> value);

		void outTimestamp(PlsqlOut<LocalDateTime> value);

		void outBinaryDouble(PlsqlOut<Double> value);

		void outBoolean(PlsqlOut<Boolean> value);

		void invertBoolean(PlsqlOut<Boolean> value);

		void invertSn(@PlsqlBoolean(trueValue = "S", falseValue = "N") PlsqlOut<Boolean> value);

		@PlsqlRoutine("OUT_INVALID_NUMBER")
		void textAsNumber(PlsqlOut<Integer> value);

		void outObject(PlsqlOut<Address> value);

		void changeObject(PlsqlOut<Address> value);

		void outRecord(PlsqlOut<Entry> value);

		void changeRecord(PlsqlOut<Entry> value);

		void outRowtype(PlsqlOut<Item> value);

		void outNames(PlsqlOut<List<String>> value);

		void changeNames(PlsqlOut<List<String>> value);

		void outAddresses(PlsqlOut<List<Address>> value);

		void outNamesPkg(PlsqlOut<List<String>> value);

		void outCursor(PlsqlOut<List<Address>> value);

		void assignAndFail(PlsqlOut<Integer> value);

		@PlsqlRoutine("OVERLOADED")
		void inVersion(int value);

		@PlsqlRoutine("OVERLOADED")
		void outVersion(PlsqlOut<String> value);

		@PlsqlRoutine("OUT_NAMES")
		void collectionWithoutList(PlsqlOut<String[]> value);

		@PlsqlRoutine("OUT_CURSOR")
		void cursorWithoutList(PlsqlOut<Address> value);

		@PlsqlRoutine("OUT_NAMES")
		void listWithoutElement(PlsqlOut<List> value);

		@PlsqlRoutine("PLUS")
		void holderAsIn(PlsqlOut<Integer> a, int b, PlsqlOut<Integer> total);

		@PlsqlRoutine("PLUS")
		void withoutHolder(int a, int b, Integer total);

		@PlsqlRoutine("PLUS")
		void rawHolder(int a, int b, PlsqlOut total);

		@PlsqlRoutine("PLUS")
		void wildcardHolder(int a, int b, PlsqlOut<?> total);
	}

	interface BlockWithOut {
		@PlsqlBlock("begin :value := 1; end;")
		void call(PlsqlOut<Integer> value);
	}

	private final Outputs outputs = pkg(Outputs.class);

	private static <T> T after(java.util.function.Consumer<PlsqlOut<T>> call) {
		PlsqlOut<T> output = new PlsqlOut<>();
		call.accept(output);
		return output.get();
	}

	@Test
	@DisplayName("OUT: the value is in the PlsqlOut after the call")
	void simple_out() {
		PlsqlOut<Integer> total = new PlsqlOut<>();

		outputs.plus(1, 2, total);

		assertThat(total.get()).isEqualTo(3);
	}

	@Test
	@DisplayName("IN OUT: the database receives the value and replaces it")
	void in_out() {
		PlsqlOut<Integer> value = PlsqlOut.of(21);

		outputs.twice(value);

		assertThat(value.get()).isEqualTo(42);
	}

	@Test
	@DisplayName("IN OUT with null: null in, null out")
	void in_out_null() {
		PlsqlOut<Integer> value = PlsqlOut.of(null);

		outputs.twice(value);

		assertThat(value.get()).isNull();
	}

	@Test
	@DisplayName("IN, OUT and IN OUT together, each in its own position")
	void mixed() {
		PlsqlOut<Integer> doubled = new PlsqlOut<>();
		PlsqlOut<String> text = new PlsqlOut<>();
		PlsqlOut<Integer> accumulated = PlsqlOut.of(10);

		outputs.several(5, doubled, text, accumulated);

		assertThat(doubled.get()).isEqualTo(10);
		assertThat(text.get()).isEqualTo("input 5");
		assertThat(accumulated.get()).isEqualTo(15);
	}

	@Test
	@DisplayName("function with OUT: the return value and the OUT, without mixing up positions")
	void function_with_out() {
		PlsqlOut<Integer> doubled = new PlsqlOut<>();

		assertThat(outputs.doubleAndReturn(21, doubled)).isEqualTo("result 21");
		assertThat(doubled.get()).isEqualTo(42);
	}

	@Test
	@DisplayName("scalar OUT: text, null, date, timestamp and BINARY_DOUBLE")
	void scalars() {
		assertThat(OutParametersTest.<String>after(outputs::outText)).isEqualTo("café");
		assertThat(OutParametersTest.<Integer>after(outputs::outNull)).isNull();
		assertThat(OutParametersTest.<LocalDate>after(outputs::outDate)).isEqualTo("2018-01-15");
		assertThat(OutParametersTest.<LocalDateTime>after(outputs::outTimestamp)).isEqualTo("2018-01-15T10:30:45");
		assertThat(OutParametersTest.<Double>after(outputs::outBinaryDouble)).isEqualTo(1.5d);
	}

	@Test
	@DisplayName("BOOLEAN in OUT and in IN OUT")
	void native_boolean() {
		assertThat(OutParametersTest.<Boolean>after(outputs::outBoolean)).isTrue();

		PlsqlOut<Boolean> value = PlsqlOut.of(true);
		outputs.invertBoolean(value);
		assertThat(value.get()).isFalse();
	}

	@Test
	@DisplayName("a null BOOLEAN in IN OUT comes back null, not false")
	void native_boolean_null() {
		// NOT NULL is NULL. Driver 21 returns false for a null BOOLEAN, and only wasNull() tells them apart.
		PlsqlOut<Boolean> value = PlsqlOut.of(null);
		outputs.invertBoolean(value);
		assertThat(value.get()).isNull();
	}

	@Test
	@DisplayName("@PlsqlBoolean applies in both directions of an IN OUT")
	void boolean_with_literals() {
		PlsqlOut<Boolean> value = PlsqlOut.of(true);

		outputs.invertSn(value);

		assertThat(value.get()).isFalse();
	}

	@Test
	@DisplayName("OUT and IN OUT of OBJECT, RECORD and %ROWTYPE")
	void composites() {
		assertThat(OutParametersTest.<Address>after(outputs::outObject)).isEqualTo(new Address("Street A", 1));
		assertThat(OutParametersTest.<Entry>after(outputs::outRecord)).isEqualTo(new Entry(1L, "one"));
		assertThat(OutParametersTest.<Item>after(outputs::outRowtype))
				.isEqualTo(new Item(1L, "one", new BigDecimal("1.5"), LocalDate.parse("2018-01-15")));

		PlsqlOut<Address> address = PlsqlOut.of(new Address("Street A", 1));
		outputs.changeObject(address);
		assertThat(address.get()).isEqualTo(new Address("Street A", 2));

		PlsqlOut<Entry> entry = PlsqlOut.of(new Entry(1L, "one"));
		outputs.changeRecord(entry);
		assertThat(entry.get()).isEqualTo(new Entry(2L, "one"));
	}

	@Test
	@DisplayName("OUT and IN OUT of schema-level and package-level collections")
	void collections() {
		assertThat(OutParametersTest.<List<String>>after(outputs::outNames)).containsExactly("a", null, "b");
		assertThat(OutParametersTest.<List<String>>after(outputs::outNamesPkg)).containsExactly("a");
		assertThat(OutParametersTest.<List<Address>>after(outputs::outAddresses))
				.containsExactly(new Address("Street A", 1), new Address("Street B", 2));

		PlsqlOut<List<String>> names = PlsqlOut.of(new ArrayList<>(Arrays.asList("a")));
		outputs.changeNames(names);
		assertThat(names.get()).containsExactly("a", "new");
	}

	@Test
	@DisplayName("cursor in OUT")
	void cursor() {
		assertThat(OutParametersTest.<List<Address>>after(outputs::outCursor))
				.containsExactly(new Address("Street A", 1), new Address("Street B", 2));
	}

	@Test
	@DisplayName("if the call fails, the PlsqlOut keeps what it had")
	void failing_call() {
		PlsqlOut<Integer> value = PlsqlOut.of(1);

		assertThatThrownBy(() -> outputs.assignAndFail(value))
				.isInstanceOf(PlsqlRoutineRaisedException.class)
				.hasMessageContaining("ORA-20200: failed after assigning");

		assertThat(value.get()).isEqualTo(1);
	}

	@Test
	@DisplayName("overloading: PlsqlOut picks the OUT version")
	void overloading() {
		outputs.inVersion(1);

		PlsqlOut<String> value = new PlsqlOut<>();
		outputs.outVersion(value);
		assertThat(value.get()).isEqualTo("version OUT");
	}

	@Test
	@DisplayName("a value that does not convert names the parameter")
	void invalid_value() {
		assertThatThrownBy(() -> outputs.textAsNumber(new PlsqlOut<>()))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageStartingWith("textAsNumber(PlsqlOut): P_VALUE of PLSQL_TEST.FIXTURE_OUT.OUT_INVALID_NUMBER: ")
				.hasMessageContaining("'abc'");
	}

	@Test
	@DisplayName("collection and cursor need PlsqlOut<List<T>>, with the element type")
	void holder_shape() {
		assertThatThrownBy(() -> outputs.collectionWithoutList(new PlsqlOut<>()))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("P_VALUE")
				.hasMessageContaining("declare List<T>");
		assertThatThrownBy(() -> outputs.cursorWithoutList(new PlsqlOut<>()))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("declare List<T>");
		assertThatThrownBy(() -> outputs.listWithoutElement(new PlsqlOut<>()))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("without an element type");
	}

	@Test
	@DisplayName("the Java signature must agree with the data dictionary, and the error says what to declare")
	void validation() {
		assertThatThrownBy(() -> outputs.withoutHolder(1, 2, null))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("P_TOTAL of PLSQL_TEST.FIXTURE_OUT.PLUS is OUT")
				.hasMessageContaining("declare PlsqlOut<T>");
		assertThatThrownBy(() -> outputs.holderAsIn(new PlsqlOut<>(), 2, new PlsqlOut<>()))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("P_A of PLSQL_TEST.FIXTURE_OUT.PLUS is IN");
		assertThatThrownBy(() -> outputs.rawHolder(1, 2, new PlsqlOut<Integer>()))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("PlsqlOut without a type");
		assertThatThrownBy(() -> outputs.wildcardHolder(1, 2, new PlsqlOut<Integer>()))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("PlsqlOut without a type");
	}

	@Test
	@DisplayName("null in place of the PlsqlOut is a usage error")
	void null_holder() {
		assertThatThrownBy(() -> outputs.plus(1, 2, null))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("P_TOTAL")
				.hasMessageContaining("pass a PlsqlOut");
	}

	@Test
	@DisplayName("a block does not accept PlsqlOut: its only output is :result")
	void block() {
		assertThatThrownBy(() -> pkg(BlockWithOut.class).call(new PlsqlOut<>()))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("a block does not accept PlsqlOut");
	}
}
