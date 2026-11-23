package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlBlock;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlRoutine;
import io.github.rponte.penitent.PlsqlType;
import io.github.rponte.penitent.exception.PlsqlMappingException;

/**
 * Oracle's {@code NUMBER} holds a magnitude from {@code 1E-130} to just under {@code 1E+126}, besides 0. A value beyond
 * it is refused before the call, with the same message on every driver (#141). Before, the driver refused it while
 * binding, with a message and a SQLState of each driver's own, {@code ORA-17001} with driver 23 and "Internal Error"
 * with drivers 21 and 19, and a {@code Double} escaped as a bare {@code IllegalArgumentException}. A
 * {@code BINARY_DOUBLE} holds such a value, so it goes there. Digits beyond what a {@code NUMBER} holds, 38 to 40, are
 * dropped, as Oracle does, with no error.
 */
class NumberLimitsTest extends OracleIntegrationTest {

	private static final String BEYOND = "is beyond the range of Oracle's NUMBER, whose magnitude goes from 1E-130 to just"
			+ " under 1E+126, besides 0: send a value within it, or declare the Oracle type BINARY_DOUBLE, which holds up"
			+ " to about 1.8E+308";

	/** The largest NUMBER: 9.999...E+125, with 40 nines, which every driver takes. */
	private static final BigDecimal LARGEST = new BigDecimal("9." + "9".repeat(39) + "E+125");

	@PlsqlType("NUMBER_ATTR_T")
	public record NumberAttr(BigDecimal n, Double b) {
	}

	@PlsqlPackage("FIXTURE_LIMITS")
	interface Limits {
		String numText(BigDecimal pValue);

		@PlsqlRoutine("NUM_TEXT")
		String bigIntegerText(BigInteger pValue);

		@PlsqlRoutine("NUM_TEXT")
		String doubleText(Double pValue);

		String bdText(Double pValue);

		String attrText(NumberAttr pValue);

		String listText(List<BigDecimal> pValue);
	}

	interface Blocks {
		@PlsqlBlock("begin :result := fixture_limits.num_text(:amount); end;")
		String numText(BigDecimal amount);
	}

	private Limits limits() {
		return pkg(Limits.class);
	}

	/** What arrived, by value: Oracle writes a large NUMBER in scientific notation. */
	private static BigDecimal arrived(String text) {
		return new BigDecimal(text);
	}

	@Test
	@DisplayName("the edges arrive: the largest and the smallest magnitude, positive and negative, and 0")
	void edges_arrive() {
		assertThat(arrived(limits().numText(LARGEST))).isEqualByComparingTo(LARGEST);
		assertThat(arrived(limits().numText(LARGEST.negate()))).isEqualByComparingTo(LARGEST.negate());
		assertThat(arrived(limits().numText(new BigDecimal("1E-130")))).isEqualByComparingTo("1E-130");
		assertThat(arrived(limits().numText(new BigDecimal("-1E-130")))).isEqualByComparingTo("-1E-130");
		assertThat(limits().numText(BigDecimal.ZERO)).isEqualTo("0");
		assertThat(arrived(limits().bigIntegerText(BigInteger.TEN.pow(125)))).isEqualByComparingTo("1E+125");
		assertThat(arrived(limits().doubleText(1e125))).isEqualByComparingTo("1E+125");
	}

	@Test
	@DisplayName("beyond is refused before the call, naming the parameter and the value: a BigDecimal, a BigInteger and"
			+ " a Double, too large or too small")
	void beyond_is_refused() {
		assertThatThrownBy(() -> limits().numText(new BigDecimal("1E+126")))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("P_VALUE is NUMBER: the value 1E+126 " + BEYOND);
		assertThatThrownBy(() -> limits().numText(new BigDecimal("-1E+200"))).hasMessageContaining("-1E+200 " + BEYOND);
		assertThatThrownBy(() -> limits().numText(new BigDecimal("1E-131"))).hasMessageContaining("1E-131 " + BEYOND);
		assertThatThrownBy(() -> limits().numText(new BigDecimal("9." + "9".repeat(44) + "E+125")))
				.as("45 nines, which rounds up past the largest").hasMessageContaining(BEYOND);
		assertThatThrownBy(() -> limits().bigIntegerText(BigInteger.TEN.pow(126)))
				.isInstanceOf(PlsqlMappingException.class).hasMessageContaining(BEYOND);
		assertThatThrownBy(() -> limits().doubleText(1e200))
				.isInstanceOf(PlsqlMappingException.class).hasMessageContaining("the value 1.0E200 " + BEYOND);
		assertThatThrownBy(() -> limits().doubleText(1e-200)).isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining(BEYOND);
		assertThatThrownBy(() -> limits().doubleText(Double.MAX_VALUE)).isInstanceOf(PlsqlMappingException.class);
		assertThatThrownBy(() -> limits().doubleText(Double.MIN_VALUE)).isInstanceOf(PlsqlMappingException.class);
	}

	@Test
	@DisplayName("a BINARY_DOUBLE holds what a NUMBER does not: there, 1e200 goes")
	void binary_double_holds_it() {
		// Oracle writes the double's binary value: 9.9999999999999997E+199
		assertThat(Double.parseDouble(limits().bdText(1e200))).isEqualTo(1e200);
	}

	@Test
	@DisplayName("digits beyond what a NUMBER holds are dropped with no error, as Oracle does: 41 digits keep 40")
	void extra_digits_are_dropped() {
		assertThat(limits().numText(new BigDecimal("12345678901234567890123456789012345678909")))
				.isEqualTo("12345678901234567890123456789012345678900");
	}

	@Test
	@DisplayName("every path names where the value is: an attribute of an OBJECT, whose BINARY_DOUBLE attribute holds it,"
			+ " an element of a collection, and a block's parameter")
	void every_path() {
		assertThatThrownBy(() -> limits().attrText(new NumberAttr(new BigDecimal("1E+200"), null)))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("attribute N of PLSQL_TEST.NUMBER_ATTR_T: the value 1E+200 " + BEYOND);
		assertThat(limits().attrText(new NumberAttr(BigDecimal.ONE, 1e200))).isEqualTo("1 9.9999999999999997E+199");
		assertThatThrownBy(() -> limits().listText(List.of(BigDecimal.ONE, new BigDecimal("1E+200"))))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("element 2 of PLSQL_TEST.NUMBER_LIST_T: the value 1E+200 " + BEYOND);
		assertThatThrownBy(() -> pkg(Blocks.class).numText(new BigDecimal("1E+200")))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("parameter amount: the value 1E+200 " + BEYOND);
	}
}
