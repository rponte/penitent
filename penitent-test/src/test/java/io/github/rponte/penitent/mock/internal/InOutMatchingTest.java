package io.github.rponte.penitent.mock.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlOut;
import io.github.rponte.penitent.internal.metadata.RoutineMetadata;
import io.github.rponte.penitent.internal.metadata.RoutineMetadata.Argument;

/**
 * How the matcher of a {@link PlsqlOut} argument is resolved by its parameter's mode (#150), and the conditions the
 * new matchers write. {@code StubInOutTest} runs them in the database; this pins each branch.
 */
class InOutMatchingTest {

	interface Stamps {
		void stampBoth(String pCode, PlsqlOut<String> pText, PlsqlOut<String> pLabel);
	}

	private static final RoutineMetadata ROUTINE = new RoutineMetadata("PLSQL_TEST", "FREIGHT_STAMPS", "STAMP_BOTH",
			List.of(new Argument(1, "P_CODE", "VARCHAR2", "IN", null, null, null, false),
					new Argument(2, "P_TEXT", "VARCHAR2", "IN/OUT", null, null, null, false),
					new Argument(3, "P_LABEL", "VARCHAR2", "OUT", null, null, null, false)),
			false);

	private static List<Matcher> resolved(boolean verifying, Matcher... matchers) throws NoSuchMethodException {
		Method method = Stamps.class.getMethod("stampBoth", String.class, PlsqlOut.class, PlsqlOut.class);
		return MockSession.resolved(ROUTINE, new Invocation(null, method, List.of(matchers)), verifying).matchers();
	}

	@Test
	@DisplayName("on an IN OUT, a PlsqlOut matches by its value, null by SQL NULL; on a pure OUT, an empty one is any()")
	void came_in() throws Exception {
		assertThat(resolved(true, new Matcher.Equal("CE"), new Matcher.CameIn("raw"), new Matcher.CameIn(null)))
				.containsExactly(new Matcher.Equal("CE"), new Matcher.Equal("raw"), new Matcher.Any());
		assertThat(resolved(false, new Matcher.Any(), new Matcher.CameIn(null), new Matcher.Any()))
				.containsExactly(new Matcher.Any(), new Matcher.Equal(null), new Matcher.Any());
	}

	@Test
	@DisplayName("inOut stays on an IN OUT, in a stub; out and any() are left as they are")
	void in_out_in_a_stub() throws Exception {
		assertThat(resolved(false, new Matcher.Any(), new Matcher.InOut("raw", "stamped"), new Matcher.Out("label")))
				.containsExactly(new Matcher.Any(), new Matcher.InOut("raw", "stamped"), new Matcher.Out("label"));
	}

	@Test
	@DisplayName("a PlsqlOut with a value on a pure OUT is refused, naming the parameter and what fits it")
	void value_on_a_pure_out() {
		assertThatThrownBy(() -> resolved(true, new Matcher.Any(), new Matcher.Any(), new Matcher.CameIn("label")))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("PLSQL_TEST.FREIGHT_STAMPS.STAMP_BOTH: P_LABEL is an OUT parameter, which nothing comes in"
						+ " through, so it matches no value: give it new PlsqlOut<>() or any(), and set it in a stub with"
						+ " out(value)");
	}

	@Test
	@DisplayName("inOut is refused on a pure OUT, on an IN, and in verify")
	void in_out_refused() {
		assertThatThrownBy(() -> resolved(false, new Matcher.Any(), new Matcher.Any(), new Matcher.InOut("a", "b")))
				.hasMessage("PLSQL_TEST.FREIGHT_STAMPS.STAMP_BOTH: P_LABEL is an OUT parameter, which nothing comes in"
						+ " through, so inOut(cameIn, goesOut) does not fit it: set it with out(value)");
		assertThatThrownBy(() -> resolved(false, new Matcher.InOut("a", "b"), new Matcher.Any(), new Matcher.Any()))
				.hasMessage("PLSQL_TEST.FREIGHT_STAMPS.STAMP_BOTH: P_CODE is an IN parameter, which nothing goes out"
						+ " through, so inOut(cameIn, goesOut) does not fit it: match it by its value");
		assertThatThrownBy(() -> resolved(true, new Matcher.Any(), new Matcher.InOut("a", "b"), new Matcher.Any()))
				.hasMessage("PLSQL_TEST.FREIGHT_STAMPS.STAMP_BOTH: verify matches the value that came in through P_TEXT,"
						+ " not the one that went out, which the stub set: give it PlsqlOut.of(value), not"
						+ " inOut(cameIn, goesOut)");
	}

	@Test
	@DisplayName("inOut writes the condition of what came in, as eq() does, and shows both ends")
	void in_out_matcher() {
		assertThat(new Matcher.InOut("raw", "stamped").condition("P_TEXT")).isEqualTo("P_TEXT = 'raw'");
		assertThat(new Matcher.InOut(null, "stamped").condition("P_TEXT")).isEqualTo("P_TEXT IS NULL");
		assertThat(new Matcher.InOut("raw", "stamped")).hasToString("inOut('raw', 'stamped')");
		assertThat(new Matcher.CameIn("raw")).hasToString("'raw'");
		assertThat(new Matcher.CameIn(null).condition("P_TEXT")).isEqualTo("P_TEXT IS NULL");
	}
}
