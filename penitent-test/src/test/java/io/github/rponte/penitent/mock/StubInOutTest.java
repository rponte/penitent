package io.github.rponte.penitent.mock;

import static io.github.rponte.penitent.mock.PlsqlMocks.doNothing;
import static io.github.rponte.penitent.mock.PlsqlMocks.never;
import static io.github.rponte.penitent.mock.PlsqlMocks.out;
import static io.github.rponte.penitent.mock.PlsqlMocks.verify;
import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlOut;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;

/**
 * A stub of a procedure with an {@code IN OUT} parameter: {@code out(value)} sets what comes back. Matching by the value
 * that came in, in a stub or in {@code verify}, is not supported yet (#150): the argument counts as {@code any()}.
 */
class StubInOutTest {

	@PlsqlPackage("FREIGHT_STAMPS")
	interface Stamps {
		void stamp(PlsqlOut<String> pText);
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

	@Test
	@DisplayName("a stub sets an IN OUT with out(value)")
	void in_out() {
		doNothing().when(mocks.mock(Stamps.class)).stamp(out("stamped"));
		PlsqlOut<String> text = PlsqlOut.of("raw");
		stamps.stamp(text);

		assertThat(text.get()).isEqualTo("stamped");
	}

	@Test
	@DisplayName("verify does not match an IN OUT by the value that came in, yet: it counts as any(). #150 turns this"
			+ " red on purpose")
	void verify_ignores_the_value_that_came_in() {
		Stamps mock = mocks.mock(Stamps.class);
		doNothing().when(mock).stamp(out("stamped"));
		stamps.stamp(PlsqlOut.of("raw"));

		verify(mock).stamp(PlsqlOut.of("other"));
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> verify(mock, never()).stamp(PlsqlOut.of("other")))
				.isInstanceOf(AssertionError.class)
				.hasMessageContaining("STAMP(any()) was expected 0 times in this test, and was called 1 time");
	}
}
