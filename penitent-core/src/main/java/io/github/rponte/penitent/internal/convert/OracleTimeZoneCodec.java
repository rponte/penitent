package io.github.rponte.penitent.internal.convert;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import oracle.jdbc.OracleConnection;
import oracle.sql.TIMESTAMPLTZ;
import oracle.sql.ZONEIDMAP;

/**
 * The two Oracle types with a time zone, {@code TIMESTAMP WITH TIME ZONE} and {@code TIMESTAMP WITH LOCAL TIME
 * ZONE}, read from their bytes by the library, and the second one also built by it.
 *
 * <p><b>Why the library does it, and not the driver.</b> Driver 19 has no {@code TIMESTAMPTZ.toZonedDateTime()} and
 * no {@code TIMESTAMPLTZ.zonedDateTimeValue(Connection)}: only the {@code OffsetDateTime} versions, which lose the
 * region of a {@code TIMESTAMP WITH TIME ZONE}, and go through {@code java.util.Calendar} with a fixed offset of the
 * session. Driver 19 also builds a {@code TIMESTAMP WITH LOCAL TIME ZONE} element of a collection from the local time
 * of the value, without its offset, so 10:30-03:00 was stored as 10:30 UTC, three hours off, without any error.
 * Reading and building the bytes here, the way driver 23 does, gives driver 23's result on every driver version.
 * {@code OracleTimeZoneCodecTest} compares the reading with driver 23's, and {@code TimeZoneTest} sends the values
 * through the database on each driver the build runs with.
 *
 * <p><b>{@code TIMESTAMP WITH TIME ZONE}</b> is 13 bytes. The first 11 are the instant in UTC, in the format of a
 * {@code TIMESTAMP} ({@link OracleDatetimeCodec}). The last two hold the time zone. When the high bit of byte 11 is
 * set, they hold the id of a region: the low 7 bits of byte 11, then the high 6 bits of byte 12. Otherwise they hold
 * an offset: the hours plus 20 and the minutes plus 60. The name of a region comes from the driver's
 * {@code ZONEIDMAP}, the table that maps Oracle's region ids to names, which is public and the same on drivers 19,
 * 21 and 23; the database's own ids, not Java's, so the library cannot hold its own copy.
 *
 * <p><b>{@code TIMESTAMP WITH LOCAL TIME ZONE}</b> has the format of a {@code TIMESTAMP}, holding the local time in
 * the database's time zone ({@code DBTIMEZONE}). It is shown in the session's time zone, which the driver sets from
 * the JVM's when it connects. Both come from the connection: the session's through the public
 * {@code OracleConnection.getSessionTimeZone()}, the database's through {@code getDatabaseTimeZone()}, which is in the
 * driver's internal interface, {@code oracle.jdbc.internal.OracleConnection}. It is there on drivers 19, 21 and 23,
 * and asking it saves a query to the database; if a driver drops it, {@code TimeZoneTest} fails on that driver.
 */
public final class OracleTimeZoneCodec {

	private static final int REGION_BIT = 0x80;
	private static final int OFFSET_HOUR = 20;
	private static final int OFFSET_MINUTE = 60;

	private OracleTimeZoneCodec() {
	}

	/** A {@code TIMESTAMP WITH TIME ZONE}, with its region when it has one, as driver 23 reads it. */
	public static ZonedDateTime decodeTimestampTz(byte[] bytes) {
		ZonedDateTime utc = OracleDatetimeCodec.decode(bytes).atZone(ZoneOffset.UTC);
		return utc.withZoneSameInstant(zoneOf(bytes[11] & 0xFF, bytes[12] & 0xFF));
	}

	private static ZoneId zoneOf(int byte11, int byte12) {
		if ((byte11 & REGION_BIT) == 0) {
			return ZoneOffset.ofHoursMinutes(byte11 - OFFSET_HOUR, byte12 - OFFSET_MINUTE);
		}
		int id = (byte11 & 0x7F) << 6 | (byte12 & 0xFC) >> 2;
		String region = ZONEIDMAP.getRegion(id);
		if (region == null) {
			throw new ScalarCodec.ConversionException("the time zone region with id " + id
					+ " is unknown to this driver; a newer driver may know it");
		}
		return ZoneId.of(region);
	}

	/**
	 * A {@code TIMESTAMP WITH LOCAL TIME ZONE}: the bytes are the local time in {@code database}, shown in
	 * {@code session}, as driver 23 reads it.
	 */
	public static ZonedDateTime decodeTimestampLtz(byte[] bytes, ZoneId database, ZoneId session) {
		return OracleDatetimeCodec.decode(bytes).atZone(database).withZoneSameInstant(session);
	}

	/** A {@code TIMESTAMP WITH LOCAL TIME ZONE} holding {@code instant}: its local time in {@code database}. */
	public static TIMESTAMPLTZ encodeTimestampLtz(Instant instant, ZoneId database) {
		LocalDateTime local = LocalDateTime.ofInstant(instant, database);
		return new TIMESTAMPLTZ(OracleDatetimeCodec.encodeTimestamp(local).toBytes());
	}

	/**
	 * What goes to the driver for a value in an attribute of an OBJECT or an element of a collection whose Oracle
	 * type is {@code oracleType}: for a {@code TIMESTAMP WITH LOCAL TIME ZONE}, an instant becomes the type's own
	 * bytes, built here, because driver 19 builds them from the value's local time, without its offset (see the
	 * class); anything else goes as it is. A parameter does not need it: the driver binds it as a
	 * {@code TIMESTAMP WITH TIME ZONE}, and the database converts it, on every version.
	 *
	 * @param encoded the value after its converter: an {@code Instant} is already an {@link OffsetDateTime} in UTC
	 * @param oracleType as the dictionary spells it, as in {@code TIMESTAMP(6) WITH LOCAL TIME ZONE}; may be null
	 */
	public static Object forComposite(String oracleType, Object encoded, Connection connection) throws SQLException {
		if (oracleType == null || !isLocalTimeZone(oracleType)) {
			return encoded;
		}
		if (encoded instanceof OffsetDateTime value) {
			return encodeTimestampLtz(value.toInstant(), databaseZone(connection));
		}
		if (encoded instanceof ZonedDateTime value) {
			return encodeTimestampLtz(value.toInstant(), databaseZone(connection));
		}
		return encoded;
	}

	/** Whatever the dictionary's spelling: with or without the precision. */
	static boolean isLocalTimeZone(String oracleType) {
		return oracleType.startsWith("TIMESTAMP") && oracleType.contains("LOCAL");
	}

	/** The database's time zone, {@code DBTIMEZONE}, from the driver's internal interface: see the class. */
	public static ZoneId databaseZone(Connection connection) throws SQLException {
		String zone = connection.unwrap(oracle.jdbc.internal.OracleConnection.class).getDatabaseTimeZone();
		return ZoneId.of(zone);
	}

	/** The session's time zone, or the JVM's when the session has none, as driver 23 does. */
	public static ZoneId sessionZone(Connection connection) throws SQLException {
		String zone = connection.unwrap(OracleConnection.class).getSessionTimeZone();
		return zone == null ? ZoneId.systemDefault() : ZoneId.of(zone);
	}
}
