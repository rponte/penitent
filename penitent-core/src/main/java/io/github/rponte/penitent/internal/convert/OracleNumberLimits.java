package io.github.rponte.penitent.internal.convert;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.SQLException;

import oracle.sql.NUMBER;

/**
 * The range of Oracle's {@code NUMBER}: a magnitude from {@code 1E-130} to just under {@code 1E+126}, besides 0. A
 * value beyond it is refused before it goes to the database, with the same message on every driver (#141).
 *
 * <p><b>Why the library checks it.</b> The driver refuses such a value while binding, so nothing is stored, but each
 * driver says it its own way: {@code ORA-17001: Internal error: Overflow Exception trying to bind}, SQLState
 * {@code 72000}, with driver 23; "Internal Error: Overflow Exception trying to bind", SQLState {@code 99999}, with
 * drivers 21 and 19; {@code ORA-17059} for an attribute of an OBJECT; and a bare {@code IllegalArgumentException},
 * "Overflow", for a {@code Double}, measured with every driver on 23ai, 21c and 19c. Checking here gives one message
 * that names the value and where it is.
 *
 * <p><b>The driver's own edges.</b> The check asks the driver's own conversion, {@code oracle.sql.NUMBER.toBytes},
 * which drivers 19, 21 and 23 do the same way, measured offline. So it refuses exactly what the driver would: the
 * edges are not simple, since the driver keeps 40 digits and drops the rest, so that {@code 9.} followed by 40 nines,
 * {@code E+125}, goes, and with 45 nines it overflows. Dropping the digits beyond 40 is Oracle's own rounding, which
 * a value with many digits, such as {@code 1/3}, relies on: it is not refused.
 *
 * <p><b>Only where the value becomes a {@code NUMBER}:</b> a {@code BINARY_DOUBLE} or a {@code BINARY_FLOAT} holds a
 * magnitude up to about {@code 1.8E+308}, so the callers skip the check for those types, which they know. A
 * {@code NaN} or an infinity is {@code ValueEncoder.requireFinite}'s, and passes here.
 */
public final class OracleNumberLimits {

	private OracleNumberLimits() {
	}

	/**
	 * For a value of any type, as a caller holds it: a {@code BigDecimal}, a {@code BigInteger}, a {@code Double} or a
	 * {@code Float} is checked; an integer type always fits, and anything else is not a number, and passes.
	 *
	 * @throws ScalarCodec.ConversionException as {@link #requireWithin(BigDecimal)}
	 */
	public static void requireWithinIfNumber(Object value) {
		if (value instanceof BigDecimal decimal) {
			requireWithin(decimal);
		} else if (value instanceof BigInteger integer) {
			requireWithin(integer);
		} else if (value instanceof Double number) {
			requireWithin(number.doubleValue());
		} else if (value instanceof Float number) {
			requireWithin(number.doubleValue());
		}
	}

	/**
	 * @throws ScalarCodec.ConversionException with no context: whoever knows the parameter, the attribute or the
	 *         element adds it
	 */
	public static void requireWithin(BigDecimal value) {
		try {
			NUMBER.toBytes(value);
		} catch (SQLException e) {
			throw beyond(value, e);
		}
	}

	public static void requireWithin(BigInteger value) {
		try {
			NUMBER.toBytes(value);
		} catch (SQLException e) {
			throw beyond(value, e);
		}
	}

	/** A finite {@code double}; {@code NaN} and the infinities are {@code requireFinite}'s. */
	public static void requireWithin(double value) {
		if (!Double.isFinite(value)) {
			return;
		}
		try {
			NUMBER.toBytes(value);
		} catch (SQLException e) {
			throw beyond(value, e);
		}
	}

	/** Whether the Oracle type, as the dictionary spells it, holds a magnitude beyond {@code NUMBER}'s. */
	public static boolean isBinaryFloatingPoint(String oracleType) {
		return oracleType != null && (oracleType.startsWith("BINARY_DOUBLE") || oracleType.startsWith("BINARY_FLOAT"));
	}

	private static ScalarCodec.ConversionException beyond(Object value, SQLException cause) {
		return new ScalarCodec.ConversionException("the value " + value + " is beyond the range of Oracle's NUMBER, whose"
				+ " magnitude goes from 1E-130 to just under 1E+126, besides 0: send a value within it, or declare the"
				+ " Oracle type BINARY_DOUBLE, which holds up to about 1.8E+308", cause);
	}
}
