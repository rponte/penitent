package io.github.rponte.penitent.mock;

import static io.github.rponte.penitent.mock.PlsqlMocks.any;
import static io.github.rponte.penitent.mock.PlsqlMocks.doNothing;
import static io.github.rponte.penitent.mock.PlsqlMocks.eq;
import static io.github.rponte.penitent.mock.PlsqlMocks.inOut;
import static io.github.rponte.penitent.mock.PlsqlMocks.isNull;
import static io.github.rponte.penitent.mock.PlsqlMocks.never;
import static io.github.rponte.penitent.mock.PlsqlMocks.out;
import static io.github.rponte.penitent.mock.PlsqlMocks.times;
import static io.github.rponte.penitent.mock.PlsqlMocks.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlOut;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.exception.PlsqlRoutineRaisedException;
import io.github.rponte.penitent.mock.StubCompositesTest.FreightItem;

/**
 * A procedure with an {@code IN OUT} parameter. What came in is matched as an {@code IN} parameter's value is, in a
 * stub and in {@code verify}, from a {@link PlsqlOut} given as it is; what goes out is set with {@code out(value)}, any
 * value coming in, or {@code inOut(cameIn, goesOut)}. A pure {@code OUT} has nothing coming in, so a {@code PlsqlOut}
 * with a value, or {@code inOut}, is refused there (#150).
 */
class StubInOutTest {

	@PlsqlPackage("FREIGHT_STAMPS")
	interface Stamps {
		void stamp(PlsqlOut<String> pText);

		void stampNote(PlsqlOut<String> pNote);

		void stampItem(PlsqlOut<FreightItem> pItem);

		void stampBoth(String pCode, PlsqlOut<String> pText, PlsqlOut<String> pLabel);
	}

	private static DataSource dataSource;

	private PlsqlMocks mocks;
	private Stamps stamps;

	@BeforeAll
	static void startDatabase() {
		dataSource = PlsqlMocks.wrap(TestDatabase.pool());
	}

	@BeforeEach
	void begin() {
		mocks = PlsqlMocks.on(dataSource);
		mocks.begin();
		stamps = PlsqlPackages.create(Stamps.class, dataSource);
	}

	@AfterEach
	void end() {
		mocks.end();
	}

	private String stamped(String text) {
		PlsqlOut<String> value = PlsqlOut.of(text);
		stamps.stamp(value);
		return value.get();
	}

	@Test
	@DisplayName("out(value) sets an IN OUT, whatever came in")
	void out_sets_it() {
		doNothing().when(mocks.mock(Stamps.class)).stamp(out("stamped"));

		assertThat(stamped("raw")).isEqualTo("stamped");
		assertThat(stamped(null)).isEqualTo("stamped");
	}

	@Test
	@DisplayName("inOut(cameIn, goesOut) matches by what came in and sets what goes out; null matches SQL NULL")
	void in_out_matches_and_sets() {
		Stamps mock = mocks.mock(Stamps.class);
		doNothing().when(mock).stamp(inOut("raw", "stamped"));
		doNothing().when(mock).stamp(inOut(null, "was null"));

		assertThat(stamped("raw")).isEqualTo("stamped");
		assertThat(stamped(null)).isEqualTo("was null");
		assertThatThrownBy(() -> stamped("other"))
				.isInstanceOf(PlsqlRoutineRaisedException.class)
				.hasMessageContaining("PLSQL_TEST.FREIGHT_STAMPS.STAMP: no stub matches the arguments [\"other\"]");
	}

	@Test
	@DisplayName("a PlsqlOut given as it is matches by its value, and sets nothing: the IN OUT goes back as it came in,"
			+ " as in PL/SQL")
	void a_plsql_out_matches_by_its_value() {
		Stamps mock = mocks.mock(Stamps.class);
		doNothing().when(mock).stamp(PlsqlOut.of("raw"));

		assertThat(stamped("raw")).isEqualTo("raw");
		assertThatThrownBy(() -> stamped("other"))
				.isInstanceOf(PlsqlRoutineRaisedException.class)
				.hasMessageContaining("no stub matches the arguments [\"other\"]");
	}

	@Test
	@DisplayName("an empty PlsqlOut matches SQL NULL coming in, as null does for an IN parameter; any() matches anything")
	void an_empty_plsql_out_matches_null() {
		Stamps mock = mocks.mock(Stamps.class);
		doNothing().when(mock).stamp(new PlsqlOut<>());

		assertThat(stamped(null)).isNull();
		assertThatThrownBy(() -> stamped("raw")).isInstanceOf(PlsqlRoutineRaisedException.class);

		doNothing().when(mock).stamp(any());
		assertThat(stamped("raw")).isEqualTo("raw");
	}

	@Test
	@DisplayName("verify counts calls by the value that came in, recorded at the call: the PlsqlOut the stub changed"
			+ " afterwards does not count")
	void verify_by_the_value_that_came_in() {
		Stamps mock = mocks.mock(Stamps.class);
		doNothing().when(mock).stamp(out("stamped"));
		PlsqlOut<String> text = PlsqlOut.of("raw");
		stamps.stamp(text);
		stamped("raw");
		stamped(null);
		assertThat(text.get()).isEqualTo("stamped");

		verify(mock, times(2)).stamp(PlsqlOut.of("raw"));
		verify(mock, never()).stamp(PlsqlOut.of("stamped"));
		verify(mock, never()).stamp(PlsqlOut.of("other"));
		verify(mock).stamp(new PlsqlOut<>());
		verify(mock, times(3)).stamp(any());
		assertThatThrownBy(() -> verify(mock).stamp(PlsqlOut.of("other")))
				.isInstanceOf(AssertionError.class)
				.hasMessageStartingWith("PLSQL_TEST.FREIGHT_STAMPS.STAMP('other') was expected 1 time in this test, and was"
						+ " called 0 times. The calls of PLSQL_TEST.FREIGHT_STAMPS.STAMP in this test, in order:\n"
						+ "  1. ('raw')");
	}

	@Test
	@DisplayName("a CLOB IN OUT is matched and counted by its value, a long one included")
	void a_clob() {
		Stamps mock = mocks.mock(Stamps.class);
		String longText = "x".repeat(40_000);
		doNothing().when(mock).stampNote(inOut(longText, "short"));
		doNothing().when(mock).stampNote(inOut("short", "long"));
		PlsqlOut<String> note = PlsqlOut.of(longText);
		stamps.stampNote(note);
		assertThat(note.get()).isEqualTo("short");
		stamps.stampNote(note);
		assertThat(note.get()).isEqualTo("long");

		verify(mock).stampNote(PlsqlOut.of(longText));
		verify(mock).stampNote(PlsqlOut.of("short"));
		verify(mock, never()).stampNote(PlsqlOut.of("long"));
	}

	@Test
	@DisplayName("beside other arguments: an IN, an IN OUT and a pure OUT, given as values or as matchers")
	void beside_other_arguments() {
		Stamps mock = mocks.mock(Stamps.class);
		doNothing().when(mock).stampBoth(eq("CE"), inOut("raw", "stamped"), out("label"));
		PlsqlOut<String> text = PlsqlOut.of("raw");
		PlsqlOut<String> label = new PlsqlOut<>();
		stamps.stampBoth("CE", text, label);

		assertThat(text.get()).isEqualTo("stamped");
		assertThat(label.get()).isEqualTo("label");
		verify(mock).stampBoth("CE", PlsqlOut.of("raw"), new PlsqlOut<>());
		verify(mock, never()).stampBoth("CE", PlsqlOut.of("other"), new PlsqlOut<>());
		verify(mock).stampBoth(eq("CE"), eq(PlsqlOut.of("raw")), any());
	}

	@Test
	@DisplayName("a pure OUT has nothing coming in: a PlsqlOut with a value, or inOut, is refused there")
	void a_pure_out_takes_no_value_coming_in() {
		Stamps mock = mocks.mock(Stamps.class);
		assertThatThrownBy(() -> verify(mock).stampBoth("CE", PlsqlOut.of("raw"), PlsqlOut.of("label")))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("PLSQL_TEST.FREIGHT_STAMPS.STAMP_BOTH: P_LABEL is an OUT parameter, which nothing comes in"
						+ " through, so it matches no value: give it new PlsqlOut<>() or any(), and set it in a stub with"
						+ " out(value)");
		assertThatThrownBy(() -> doNothing().when(mock).stampBoth(eq("CE"), out("x"), inOut("a", "b")))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("PLSQL_TEST.FREIGHT_STAMPS.STAMP_BOTH: P_LABEL is an OUT parameter, which nothing comes in"
						+ " through, so inOut(cameIn, goesOut) does not fit it: set it with out(value)");
	}

	@Test
	@DisplayName("verify matches what came in only: inOut, which also sets what goes out, is refused there")
	void verify_refuses_in_out() {
		Stamps mock = mocks.mock(Stamps.class);
		doNothing().when(mock).stamp(out("stamped"));
		assertThatThrownBy(() -> verify(mock).stamp(inOut("raw", "stamped")))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("PLSQL_TEST.FREIGHT_STAMPS.STAMP: verify matches the value that came in through P_TEXT,"
						+ " not the one that went out, which the stub set: give it PlsqlOut.of(value), isNull()"
						+ " or any(), not inOut(cameIn, goesOut)");
	}

	@Test
	@DisplayName("an IN OUT of an object type is set with out(value) and matched with any() only, as an IN one is")
	void an_object_type() {
		Stamps mock = mocks.mock(Stamps.class);
		doNothing().when(mock).stampItem(out(new FreightItem(2L, "SP")));
		PlsqlOut<FreightItem> item = PlsqlOut.of(new FreightItem(1L, "CE"));
		stamps.stampItem(item);

		assertThat(item.get()).isEqualTo(new FreightItem(2L, "SP"));
		verify(mock).stampItem(any());
		assertThatThrownBy(() -> verify(mock).stampItem(PlsqlOut.of(new FreightItem(1L, "CE"))))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("P_ITEM is of OBJECT, which the mocks match with any() only, so far");
	}

	@Test
	@DisplayName("the example of the testing page")
	void documented_example() {
		Stamps mock = mocks.mock(Stamps.class);
		// snippet: testing/in-out
		// whatever came in: goes out as "default"
		doNothing().when(mock).stamp(out("default"));
		// came in as "raw": goes out as "stamped". The last stub that matches wins, so this one, for "raw"
		doNothing().when(mock).stamp(inOut("raw", "stamped"));
		// end-snippet
		assertThat(stamped("raw")).isEqualTo("stamped");
		assertThat(stamped("other")).isEqualTo("default");
		// snippet: testing/in-out-verify
		// what came in, recorded at the call: "stamped", which went out, does not count
		verify(mock).stamp(PlsqlOut.of("raw"));
		// SQL NULL came in: as for an IN parameter, so null and new PlsqlOut<>() do too
		verify(mock, never()).stamp(isNull());
		// end-snippet
	}

	@Test
	@DisplayName("SQL NULL coming in, in a stub and in verify: isNull(), a plain null, or an empty PlsqlOut")
	void null_coming_in() {
		Stamps mock = mocks.mock(Stamps.class);
		doNothing().when(mock).stamp(out("other"));
		doNothing().when(mock).stamp(isNull());
		assertThat(stamped(null)).isNull();
		assertThat(stamped("raw")).isEqualTo("other");
		doNothing().when(mock).stamp(null);
		assertThat(stamped(null)).isNull();

		verify(mock, times(2)).stamp(isNull());
		verify(mock, times(2)).stamp(null);
		verify(mock, times(2)).stamp(new PlsqlOut<>());
		verify(mock).stamp(PlsqlOut.of("raw"));
	}

	@Test
	@DisplayName("inOut(cameIn, null) sets SQL NULL going out; eq(PlsqlOut.of(x)) beside other matchers matches x, in a"
			+ " stub as in verify; any() matches anything and sets nothing")
	void the_other_forms_in_a_stub() {
		Stamps mock = mocks.mock(Stamps.class);
		doNothing().when(mock).stamp(inOut("raw", null));
		assertThat(stamped("raw")).isNull();

		doNothing().when(mock).stampBoth(eq("CE"), eq(PlsqlOut.of("raw")), out("label"));
		PlsqlOut<String> text = PlsqlOut.of("raw");
		PlsqlOut<String> label = new PlsqlOut<>();
		stamps.stampBoth("CE", text, label);
		assertThat(text.get()).isEqualTo("raw");
		assertThat(label.get()).isEqualTo("label");
		assertThatThrownBy(() -> stamps.stampBoth("CE", PlsqlOut.of("other"), new PlsqlOut<>()))
				.isInstanceOf(PlsqlRoutineRaisedException.class);

		doNothing().when(mock).stamp(any());
		assertThat(stamped("other")).isEqualTo("other");
	}

	@Test
	@DisplayName("out(value) sets a stub's value: it is refused in verify, which matches what came in")
	void out_refused() {
		Stamps mock = mocks.mock(Stamps.class);
		doNothing().when(mock).stamp(out("stamped"));
		assertThatThrownBy(() -> verify(mock).stamp(out("stamped")))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("PLSQL_TEST.FREIGHT_STAMPS.STAMP: verify matches the value that came in through P_TEXT,"
						+ " not the one that went out, which the stub set: give it PlsqlOut.of(value), isNull() or any(),"
						+ " not out(value)");
		assertThatThrownBy(() -> verify(mock).stampBoth(eq("CE"), any(), out("label")))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("PLSQL_TEST.FREIGHT_STAMPS.STAMP_BOTH: verify matches the values that came in, and P_LABEL"
						+ " is an OUT parameter, which nothing comes in through: give it new PlsqlOut<>() or any(), not"
						+ " out(value)");
		// on an IN parameter, out(value) does not compile: it is a PlsqlOut, and the parameter is not
	}
}
