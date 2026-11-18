package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlOut;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.exception.PlsqlMappingException;

/**
 * {@code NaN} and the infinities, sent to the database: {@code BINARY_DOUBLE} and {@code BINARY_FLOAT} hold them, and
 * {@code NUMBER} does not, so a {@code NUMBER} refuses them with a message rather than a {@code NumberFormatException}.
 */
class NonFiniteNumbersTest extends OracleIntegrationTest {

	@PlsqlPackage("FIXTURE_TYPES")
	interface Types {
		Double echoBinaryDouble(Double value);

		Float echoBinaryFloat(Float value);
	}

	record AttrBinaryDouble(Double v) {
	}

	@PlsqlPackage("FIXTURE_ATTRIBUTES")
	interface Attributes {
		AttrBinaryDouble echoBinaryDouble(AttrBinaryDouble value);
	}

	interface Blocks {
		@io.github.rponte.penitent.PlsqlBlock("begin :result := case when :value is nan then 'NaN'"
				+ " when :value = binary_double_infinity then 'Inf' else 'other' end; end;")
		String describe(Double value);
	}

	@PlsqlPackage("FIXTURE_NON_FINITE")
	interface NonFinite {
		void negate(PlsqlOut<Double> value);

		List<Double> echoDoubles(List<Double> value);

		BigDecimal echoNumber(Double value);

		String describe(Double value);
	}

	@Test
	@DisplayName("NaN and the infinities as an IN of a BINARY_DOUBLE and a BINARY_FLOAT, which PL/SQL sees as such")
	void as_in() {
		Types types = pkg(Types.class);
		NonFinite nonFinite = pkg(NonFinite.class);

		assertThat(types.echoBinaryDouble(Double.NaN)).isNaN();
		assertThat(types.echoBinaryDouble(Double.POSITIVE_INFINITY)).isEqualTo(Double.POSITIVE_INFINITY);
		assertThat(types.echoBinaryFloat(Float.NEGATIVE_INFINITY)).isEqualTo(Float.NEGATIVE_INFINITY);
		assertThat(types.echoBinaryFloat(Float.NaN)).isNaN();
		assertThat(nonFinite.describe(Double.NaN)).isEqualTo("NaN");
		assertThat(nonFinite.describe(Double.NEGATIVE_INFINITY)).isEqualTo("-Inf");
	}

	@Test
	@DisplayName("an infinity as an IN OUT of a BINARY_DOUBLE")
	void as_in_out() {
		PlsqlOut<Double> value = PlsqlOut.of(Double.POSITIVE_INFINITY);
		pkg(NonFinite.class).negate(value);

		assertThat(value.get()).isEqualTo(Double.NEGATIVE_INFINITY);
	}

	@Test
	@DisplayName("NaN and an infinity as an attribute of an object type and as elements of a collection")
	void as_attribute_and_element() {
		assertThat(pkg(Attributes.class).echoBinaryDouble(new AttrBinaryDouble(Double.NaN)).v()).isNaN();
		assertThat(pkg(NonFinite.class).echoDoubles(List.of(Double.NaN, Double.NEGATIVE_INFINITY, 1.5)))
				.containsExactly(Double.NaN, Double.NEGATIVE_INFINITY, 1.5);
	}

	@Test
	@DisplayName("NaN and an infinity as a parameter of a block, which has no declared type")
	void in_a_block() {
		assertThat(pkg(Blocks.class).describe(Double.NaN)).isEqualTo("NaN");
		assertThat(pkg(Blocks.class).describe(Double.POSITIVE_INFINITY)).isEqualTo("Inf");
		assertThat(pkg(Blocks.class).describe(1.5)).isEqualTo("other");
	}

	@Test
	@DisplayName("NaN for a NUMBER is refused, naming the parameter and the types that hold it")
	void number_refuses_them() {
		assertThatThrownBy(() -> pkg(NonFinite.class).echoNumber(Double.NaN))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("P_VALUE")
				.hasMessageContaining("NaN")
				.hasMessageContaining("BINARY_DOUBLE");
	}
}
