package io.github.rponte.penitent.internal.convert;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Struct;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Map;

import io.github.rponte.penitent.PlsqlBoolean;
import io.github.rponte.penitent.exception.PlsqlMappingException;
import oracle.sql.TIMESTAMP;
import oracle.sql.TIMESTAMPLTZ;
import oracle.sql.TIMESTAMPTZ;

/**
 * Converts scalar values between Java and JDBC, in both directions.
 *
 * <p>Each type has its own {@link BuiltInConverter}. What comes before them lives here: choosing
 * which one to use, handling {@code null} and unwrapping the driver's types, which need the
 * connection.
 *
 * <p>Here null is null in both directions, always.
 *
 * <p>It is public only for the rest of the library: this package is not part of the API and may
 * change.
 */
public final class ScalarCodec {

	/** Enum and boolean are not here: they depend on the enum class and on the literals of each point. */
	private static final Map<Class<?>, BuiltInConverter<?>> BY_TYPE = byType();

	private ScalarCodec() {
	}

	private static Map<Class<?>, BuiltInConverter<?>> byType() {
		BuiltInConverter<Integer> integers = new IntegerConverter();
		BuiltInConverter<Long> longs = new LongConverter();
		BuiltInConverter<Double> doubles = new DoubleConverter();
		BuiltInConverter<Float> floats = new FloatConverter();
		BuiltInConverter<Short> shorts = new ShortConverter();
		BuiltInConverter<Byte> bytes = new ByteConverter();
		BuiltInConverter<Character> chars = new CharacterConverter();
		return Map.ofEntries(
				Map.entry(LocalDate.class, new LocalDateConverter()),
				Map.entry(LocalDateTime.class, new LocalDateTimeConverter()),
				Map.entry(OffsetDateTime.class, new OffsetDateTimeConverter()),
				Map.entry(ZonedDateTime.class, new ZonedDateTimeConverter()),
				Map.entry(Instant.class, new InstantConverter()),
				Map.entry(BigDecimal.class, new BigDecimalConverter()),
				Map.entry(Integer.class, integers),
				Map.entry(int.class, integers),
				Map.entry(Long.class, longs),
				Map.entry(long.class, longs),
				Map.entry(Double.class, doubles),
				Map.entry(double.class, doubles),
				Map.entry(Float.class, floats),
				Map.entry(float.class, floats),
				Map.entry(String.class, new StringConverter()),
				Map.entry(Short.class, shorts),
				Map.entry(short.class, shorts),
				Map.entry(Byte.class, bytes),
				Map.entry(byte.class, bytes),
				Map.entry(Character.class, chars),
				Map.entry(char.class, chars),
				Map.entry(java.math.BigInteger.class, new BigIntegerConverter()),
				Map.entry(java.util.UUID.class, new UuidConverter()),
				Map.entry(java.util.Date.class, new UtilDateConverter()),
				Map.entry(java.sql.Timestamp.class, new TimestampConverter()),
				Map.entry(byte[].class, new ByteArrayConverter()),
				Map.entry(java.sql.Clob.class, new ClobConverter()),
				Map.entry(java.sql.Blob.class, new BlobConverter()),
				Map.entry(java.time.Duration.class, new DurationConverter()),
				Map.entry(java.time.Period.class, new PeriodConverter()));
	}

	/**
	 * Whether the codec converts this Java type on its own, as a single value: the types of its table,
	 * enums and booleans. A cursor of such a type is read from its only column.
	 */
	public static boolean isScalar(Class<?> type) {
		return type.isPrimitive() || type.isEnum() || type == Boolean.class || BY_TYPE.containsKey(type);
	}

	/** Java → JDBC. {@code conversion} describes what the attribute/parameter expects. */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public static Object encode(Object value, ValueConversion conversion) {
		if (value == null) {
			return null;
		}
		if (value instanceof Enum<?> e) {
			return new EnumConverter(e.getDeclaringClass()).toDatabase(e);
		}
		if (value instanceof Boolean b) {
			return new BooleanConverter(conversion).toDatabase(b);
		}
		BuiltInConverter converter = BY_TYPE.get(value.getClass());
		return converter == null ? value : converter.toDatabase(value);
	}

	/**
	 * A value that did not fit in the type Java declared. It only lives until whoever knows the
	 * context (the attribute, the column, the routine) adds it to the message.
	 */
	public static final class ConversionException extends PlsqlMappingException {

		private static final long serialVersionUID = 1L;

		public ConversionException(String message) {
			super(message);
		}

		public ConversionException(String message, Throwable cause) {
			super(message, cause);
		}
	}

	/**
	 * Why a Java type does not fit an Oracle date type, or {@code null} if it does. It only rejects
	 * what would lose or invent the time zone without any error, and then it applies in both
	 * directions.
	 *
	 * @param oracleType as the dictionary spells it: {@code ALL_TYPE_ATTRS} includes the precision,
	 *        as in {@code TIMESTAMP(6) WITH TIME ZONE}
	 */
	public static String temporalRefusal(String oracleType, Class<?> javaType) {
		if (oracleType.startsWith("TIMESTAMP") && oracleType.contains(" WITH ")) {
			// java.util.Date, java.sql.Date and java.sql.Timestamp go to the driver without a time zone too
			return javaType == LocalDateTime.class || javaType == LocalDate.class
					|| java.util.Date.class.isAssignableFrom(javaType)
					? javaType.getSimpleName() + " has no time zone; declare OffsetDateTime, ZonedDateTime or Instant"
					: null;
		}
		if (oracleType.equals("DATE") || oracleType.startsWith("TIMESTAMP")) {
			if (javaType == Instant.class) {
				return "Instant would need a time zone that " + oracleType + " does not store; declare LocalDateTime";
			}
			return javaType == OffsetDateTime.class || javaType == ZonedDateTime.class
					? javaType.getSimpleName() + " would lose the time zone, which " + oracleType
							+ " does not store; declare LocalDateTime"
					: null;
		}
		return null;
	}

	/** JDBC → Java, guided by the type Java declared. */
	public static Object decode(Object value, Class<?> targetType, ValueConversion conversion, Connection connection) {
		try {
			return convert(unwrapOracleTimestamp(value, connection), targetType, conversion);
		} catch (IllegalArgumentException | ArithmeticException e) {
			throw new ConversionException("cannot convert '" + value + "' to " + targetType.getName()
					+ ": " + e.getMessage(), e);
		}
	}

	/**
	 * In a cursor, the driver returns its own types. The two with a time zone become
	 * {@link ZonedDateTime}, the only one that does not lose the region, and {@link TIMESTAMPLTZ}
	 * needs the connection because it does not store a time zone: the session decides it.
	 */
	private static Object unwrapOracleTimestamp(Object value, Connection connection) {
		try {
			if (value instanceof TIMESTAMPTZ t) {
				return t.toZonedDateTime();
			}
			if (value instanceof TIMESTAMPLTZ t) {
				return t.zonedDateTimeValue(connection);
			}
			if (value instanceof TIMESTAMP t) {
				return t.timestampValue();
			}
			if (value instanceof oracle.sql.INTERVALDS interval) {
				return interval.getDuration();
			}
			if (value instanceof oracle.sql.INTERVALYM interval) {
				return interval.getPeriod();
			}
			return value;
		} catch (SQLException e) {
			throw new ConversionException("failed to read " + value.getClass().getName() + ": " + e.getMessage(), e);
		}
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static Object convert(Object value, Class<?> targetType, ValueConversion conversion) {
		if (value == null) {
			// turning it into false or 0 would hide a "not provided", and NULL is common in legacy data
			if (targetType.isPrimitive()) {
				throw new ConversionException("NULL does not fit in " + targetType.getName() + "; declare "
						+ java.lang.invoke.MethodType.methodType(targetType).wrap().returnType().getSimpleName());
			}
			return null;
		}
		if (targetType.isEnum()) {
			return new EnumConverter((Class) targetType).fromDatabase(value);
		}
		if (targetType == Boolean.class || targetType == boolean.class) {
			return new BooleanConverter(conversion).fromDatabase(value);
		}
		BuiltInConverter<?> converter = BY_TYPE.get(targetType);
		if (converter != null) {
			return converter.fromDatabase(value);
		}
		if (targetType.isInstance(value)) {
			return value;
		}
		throw new ConversionException("cannot convert " + value.getClass().getName()
				+ " to " + targetType.getName() + " (value: " + value + ")");
	}

	static void requireNoTimeZone(Object value, Class<?> targetType) {
		if (value instanceof ZonedDateTime || value instanceof OffsetDateTime) {
			throw new ConversionException(targetType.getSimpleName()
					+ " has no time zone; declare OffsetDateTime, ZonedDateTime or Instant");
		}
	}

	/** What the conversion needs to know besides the type: currently only the boolean literals. */
	public record ValueConversion(String trueValue, String falseValue) {

		public static final ValueConversion DEFAULT = new ValueConversion("Y", "N");

		/** How a BOOLEAN attribute crosses a {@link Struct}: there are no literals to choose. */
		public static final ValueConversion NATIVE_BOOLEAN = new ValueConversion("1", "0");

		/** The default for a boolean stored as a number. */
		public static final ValueConversion NUMERIC_BOOLEAN = new ValueConversion("1", "0");

		public static ValueConversion from(PlsqlBoolean annotation) {
			return annotation == null
					? DEFAULT
					: new ValueConversion(annotation.trueValue(), annotation.falseValue());
		}
	}
}
