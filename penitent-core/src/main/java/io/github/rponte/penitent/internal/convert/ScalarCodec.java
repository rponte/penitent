package io.github.rponte.penitent.internal.convert;

import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
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
import oracle.jdbc.OracleConnection;
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
	 * A parameter or a function's return value. A {@code LocalDateTime} or {@code LocalDate} is asked
	 * of the driver as such, because its default, a {@code Timestamp}, goes through the JVM's time
	 * zone and moves a local time that zone skips.
	 *
	 * @param declared the Java type the value becomes, or the converter's database type
	 */
	public static Object read(CallableStatement statement, int position, Class<?> declared) throws SQLException {
		return isLocalTemporal(declared) ? statement.getObject(position, declared) : statement.getObject(position);
	}

	/**
	 * A cursor column, for the reason in {@link #read(CallableStatement, int, Class)}. Only a
	 * {@code DATE} or {@code TIMESTAMP} column: asked for a {@code LocalDateTime}, the driver would also
	 * drop the time zone of a column that has one, which has to be refused instead. A parameter needs
	 * no such check, because the dictionary already refused it before the call.
	 */
	public static Object read(ResultSet rs, int column, Class<?> declared) throws SQLException {
		if (isLocalTemporal(declared)) {
			int type = rs.getMetaData().getColumnType(column);
			if (type == java.sql.Types.DATE || type == java.sql.Types.TIMESTAMP) {
				return rs.getObject(column, declared);
			}
		}
		return rs.getObject(column);
	}

	/**
	 * What goes to the driver for an encoded value. A {@code LocalDateTime} or {@code LocalDate} goes as
	 * the driver's own {@code TIMESTAMP} or {@code DATE}, built from the local date and time: as a
	 * {@code Timestamp} it would go through the JVM's time zone; as itself, {@code createStruct} refuses
	 * it, and a parameter loses its SQL type, so Oracle can no longer choose between overloads. The log
	 * keeps the encoded value, which reads better.
	 *
	 * <p>The library builds them with {@link OracleDatetimeCodec}, not with the driver's
	 * {@code TIMESTAMP.of} and {@code DATE.of}: before driver 23, those go through the JVM's time zone,
	 * and a time that the zone skips moves an hour without any error.
	 */
	public static Object forDriver(Object encoded) {
		if (encoded instanceof LocalDateTime local) {
			return OracleDatetimeCodec.encodeTimestamp(local);
		}
		if (encoded instanceof LocalDate date) {
			return OracleDatetimeCodec.encodeDate(date);
		}
		return encoded;
	}

	/** {@link #forDriver(Object)} for the attributes of a {@code Struct} or the elements of an array. */
	public static Object[] forComposite(Object[] encoded) throws SQLException {
		Object[] values = encoded.clone();
		for (int i = 0; i < values.length; i++) {
			values[i] = forDriver(values[i]);
		}
		return values;
	}

	/** The types without a time zone that the driver can deliver without going through the JVM's. */
	public static boolean isLocalTemporal(Class<?> type) {
		return type == LocalDateTime.class || type == LocalDate.class;
	}

	/**
	 * In a cursor, an attribute or an element, the driver may return its own types. The two with a
	 * time zone become {@link ZonedDateTime}, the only one that does not lose the region, and
	 * {@link TIMESTAMPLTZ} needs the connection because it does not store a time zone: the session
	 * decides it. {@code DATE} and {@code TIMESTAMP} become {@link LocalDateTime} straight from their
	 * bytes, without the JVM's time zone.
	 */
	private static Object unwrapOracleTimestamp(Object value, Connection connection) {
		try {
			if (value instanceof TIMESTAMPTZ t) {
				return t.toZonedDateTime();
			}
			if (value instanceof TIMESTAMPLTZ t) {
				// The driver itself, not the pool's proxy: before driver 23, zonedDateTimeValue casts its argument
				// to OracleConnection, and fails behind any pool, such as Hikari's. Unwrapping works on every
				// driver version, as OracleArrays does for the same reason.
				return t.zonedDateTimeValue(connection.unwrap(OracleConnection.class));
			}
			// From the bytes, not with toLocalDateTime(): before driver 23, that goes through the JVM's time
			// zone, and 02:30 on the day the clocks jump comes back as 03:30. See OracleDatetimeCodec.
			if (value instanceof TIMESTAMP t) {
				return OracleDatetimeCodec.decode(t.toBytes());
			}
			if (value instanceof oracle.sql.DATE d) {
				return OracleDatetimeCodec.decode(d.toBytes());
			}
			// From the bytes: getDuration() and getPeriod() only exist from driver 23 on. See OracleIntervalCodec.
			if (value instanceof oracle.sql.INTERVALDS interval) {
				return OracleIntervalCodec.duration(interval.toBytes());
			}
			if (value instanceof oracle.sql.INTERVALYM interval) {
				return OracleIntervalCodec.period(interval.toBytes());
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
