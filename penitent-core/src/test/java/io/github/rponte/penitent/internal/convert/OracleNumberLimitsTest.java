package io.github.rponte.penitent.internal.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The range of Oracle's {@code NUMBER}, a magnitude from {@code 1E-130} to just under {@code 1E+126}, besides 0, as the
 * driver's own conversion, {@code oracle.sql.NUMBER.toBytes}, draws it. The edges are measured offline with drivers 19,
 * 21 and 23, which agree; this test runs with whichever driver the build uses, so a driver that drew them otherwise
 * would show here.
 */
class OracleNumberLimitsTest {

	private static final String BEYOND = "is beyond the range of Oracle's NUMBER, whose magnitude goes from 1E-130 to just"
			+ " under 1E+126, besides 0: send a value within it, or declare the Oracle type BINARY_DOUBLE, which holds up"
			+ " to about 1.8E+308";

	private static BigDecimal nines(int count) {
		return new BigDecimal("9." + "9".repeat(count - 1) + "E+125");
	}

	@Test
	@DisplayName("the largest magnitudes: the driver keeps 40 digits, so up to 40 nines go, and 45 round up past the"
			+ " largest")
	void the_largest() {
		assertThatCode(() -> OracleNumberLimits.requireWithin(nines(38))).doesNotThrowAnyException();
		assertThatCode(() -> OracleNumberLimits.requireWithin(nines(40))).doesNotThrowAnyException();
		assertThatCode(() -> OracleNumberLimits.requireWithin(nines(40).negate())).doesNotThrowAnyException();
		assertThatThrownBy(() -> OracleNumberLimits.requireWithin(nines(45)))
				.isInstanceOf(ScalarCodec.ConversionException.class).hasMessageContaining(BEYOND);
		assertThatThrownBy(() -> OracleNumberLimits.requireWithin(new BigDecimal("1E+126")))
				.isInstanceOf(ScalarCodec.ConversionException.class)
				.hasMessage("the value 1E+126 " + BEYOND)
				.hasCauseInstanceOf(java.sql.SQLException.class);
		assertThatThrownBy(() -> OracleNumberLimits.requireWithin(new BigDecimal("-1E+200")))
				.hasMessage("the value -1E+200 " + BEYOND);
	}

	@Test
	@DisplayName("the smallest magnitude: 1E-130 goes, anything smaller but 0 does not")
	void the_smallest() {
		assertThatCode(() -> OracleNumberLimits.requireWithin(new BigDecimal("1E-130"))).doesNotThrowAnyException();
		assertThatCode(() -> OracleNumberLimits.requireWithin(new BigDecimal("-1E-130"))).doesNotThrowAnyException();
		assertThatCode(() -> OracleNumberLimits.requireWithin(BigDecimal.ZERO)).doesNotThrowAnyException();
		assertThatThrownBy(() -> OracleNumberLimits.requireWithin(new BigDecimal("9.9E-131"))).hasMessageContaining(BEYOND);
		assertThatThrownBy(() -> OracleNumberLimits.requireWithin(new BigDecimal("1E-200")))
				.hasMessage("the value 1E-200 " + BEYOND);
	}

	@Test
	@DisplayName("digits beyond 40 are Oracle's rounding, not a range: they are not refused")
	void extra_digits() {
		assertThatCode(() -> OracleNumberLimits.requireWithin(new BigDecimal("12345678901234567890123456789012345678909")))
				.doesNotThrowAnyException();
		assertThatCode(() -> OracleNumberLimits.requireWithin(BigDecimal.ONE.divide(BigDecimal.valueOf(3),
				new java.math.MathContext(100)))).as("1/3 with 100 digits").doesNotThrowAnyException();
	}

	@Test
	@DisplayName("a BigInteger, by the same edges")
	void big_integer() {
		assertThatCode(() -> OracleNumberLimits.requireWithin(BigInteger.TEN.pow(125))).doesNotThrowAnyException();
		assertThatThrownBy(() -> OracleNumberLimits.requireWithin(BigInteger.TEN.pow(126))).hasMessageContaining(BEYOND);
	}

	@Test
	@DisplayName("a double, by the same edges; NaN and the infinities are requireFinite's, and pass here")
	void doubles() {
		assertThatCode(() -> OracleNumberLimits.requireWithin(1e125)).doesNotThrowAnyException();
		assertThatCode(() -> OracleNumberLimits.requireWithin(1e-130)).doesNotThrowAnyException();
		assertThatThrownBy(() -> OracleNumberLimits.requireWithin(1e126)).hasMessage("the value 1.0E126 " + BEYOND);
		assertThatThrownBy(() -> OracleNumberLimits.requireWithin(1e-131)).hasMessageContaining(BEYOND);
		assertThatThrownBy(() -> OracleNumberLimits.requireWithin(Double.MAX_VALUE)).hasMessageContaining(BEYOND);
		assertThatThrownBy(() -> OracleNumberLimits.requireWithin(Double.MIN_VALUE)).hasMessageContaining(BEYOND);
		assertThatCode(() -> OracleNumberLimits.requireWithin(Double.NaN)).doesNotThrowAnyException();
		assertThatCode(() -> OracleNumberLimits.requireWithin(Double.POSITIVE_INFINITY)).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("for a value of any type: the decimal types and the floating point ones are checked; the integer types"
			+ " and a Float always fit; anything else, and null, passes")
	void dispatch() {
		assertThatThrownBy(() -> OracleNumberLimits.requireWithinIfNumber(new BigDecimal("1E+200")))
				.hasMessageContaining(BEYOND);
		assertThatThrownBy(() -> OracleNumberLimits.requireWithinIfNumber(BigInteger.TEN.pow(200)))
				.hasMessageContaining(BEYOND);
		assertThatThrownBy(() -> OracleNumberLimits.requireWithinIfNumber(1e200)).hasMessageContaining(BEYOND);
		assertThatCode(() -> OracleNumberLimits.requireWithinIfNumber(Float.MAX_VALUE)).doesNotThrowAnyException();
		assertThatCode(() -> OracleNumberLimits.requireWithinIfNumber(Long.MAX_VALUE)).doesNotThrowAnyException();
		assertThatCode(() -> OracleNumberLimits.requireWithinIfNumber(Integer.MIN_VALUE)).doesNotThrowAnyException();
		assertThatCode(() -> OracleNumberLimits.requireWithinIfNumber("1E+200")).doesNotThrowAnyException();
		assertThatCode(() -> OracleNumberLimits.requireWithinIfNumber(null)).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("the types that hold more than a NUMBER, as the dictionary spells them, with or without a precision")
	void binary_floating_point() {
		assertThat(OracleNumberLimits.isBinaryFloatingPoint("BINARY_DOUBLE")).isTrue();
		assertThat(OracleNumberLimits.isBinaryFloatingPoint("BINARY_FLOAT")).isTrue();
		assertThat(OracleNumberLimits.isBinaryFloatingPoint("NUMBER")).isFalse();
		assertThat(OracleNumberLimits.isBinaryFloatingPoint("FLOAT")).isFalse();
		assertThat(OracleNumberLimits.isBinaryFloatingPoint(null)).isFalse();
	}
}
