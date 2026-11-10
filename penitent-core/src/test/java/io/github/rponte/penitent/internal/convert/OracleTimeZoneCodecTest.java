package io.github.rponte.penitent.internal.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import oracle.sql.ZONEIDMAP;

/**
 * The bytes of {@code TIMESTAMP WITH TIME ZONE} and {@code TIMESTAMP WITH LOCAL TIME ZONE}, read and built by the
 * library, so that the result does not depend on the driver version. Driver 23 already reads them this way, and is
 * the reference.
 */
class OracleTimeZoneCodecTest {

	private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");

	/** 2018-01-15 13:30:00 UTC, in the date and time format of the first seven bytes. */
	private static final int[] UTC_13_30 = { 120, 118, 1, 15, 14, 31, 1 };

	private static byte[] bytes(int... values) {
		byte[] bytes = new byte[values.length];
		for (int i = 0; i < values.length; i++) {
			bytes[i] = (byte) values[i];
		}
		return bytes;
	}

	private static int[] unsigned(byte[] bytes) {
		int[] values = new int[bytes.length];
		for (int i = 0; i < bytes.length; i++) {
			values[i] = bytes[i] & 0xFF;
		}
		return values;
	}

	private static byte[] withZone(int[] utc, int byte11, int byte12) {
		int[] all = new int[13];
		System.arraycopy(utc, 0, all, 0, utc.length);
		all[11] = byte11;
		all[12] = byte12;
		return bytes(all);
	}

	@Test
	@DisplayName("TIMESTAMP WITH TIME ZONE with an offset: the instant in UTC, then the hour plus 20 and the minute plus 60")
	void offset() {
		assertThat(OracleTimeZoneCodec.decodeTimestampTz(withZone(UTC_13_30, 20 - 3, 60)))
				.isEqualTo(OffsetDateTime.parse("2018-01-15T10:30:00-03:00").toZonedDateTime());
		assertThat(OracleTimeZoneCodec.decodeTimestampTz(withZone(UTC_13_30, 20 + 5, 60 + 30)))
				.isEqualTo(ZonedDateTime.of(2018, 1, 15, 19, 0, 0, 0, ZoneOffset.ofHoursMinutes(5, 30)));
	}

	@Test
	@DisplayName("TIMESTAMP WITH TIME ZONE with a region: its id in the bits of the last two bytes, its name from the driver")
	void region() {
		int id = ZONEIDMAP.getID("America/Sao_Paulo");
		ZonedDateTime decoded = OracleTimeZoneCodec.decodeTimestampTz(withZone(UTC_13_30, 0x80 | id >> 6, (id & 0x3F) << 2));

		// in January 2018, Sao Paulo was on daylight saving time, at -02:00
		assertThat(decoded).isEqualTo(ZonedDateTime.of(2018, 1, 15, 11, 30, 0, 0, SAO_PAULO));
		assertThat(decoded.getZone()).isEqualTo(SAO_PAULO);
	}

	@Test
	@DisplayName("TIMESTAMP WITH LOCAL TIME ZONE: the bytes are the database's local time, shown in the session's zone")
	void local_time_zone_read() {
		assertThat(OracleTimeZoneCodec.decodeTimestampLtz(bytes(UTC_13_30), ZoneOffset.UTC, SAO_PAULO))
				.isEqualTo(ZonedDateTime.of(2018, 1, 15, 11, 30, 0, 0, SAO_PAULO));
		assertThat(OracleTimeZoneCodec.decodeTimestampLtz(bytes(UTC_13_30), ZoneOffset.ofHours(2), ZoneOffset.UTC))
				.isEqualTo(ZonedDateTime.of(2018, 1, 15, 11, 30, 0, 0, ZoneOffset.UTC));
	}

	@Test
	@DisplayName("TIMESTAMP WITH LOCAL TIME ZONE: an instant goes as the database's local time, whatever its own offset")
	void local_time_zone_write() {
		OffsetDateTime value = OffsetDateTime.parse("2018-01-15T10:30:00-03:00");

		assertThat(unsigned(OracleTimeZoneCodec.encodeTimestampLtz(value.toInstant(), ZoneOffset.UTC).toBytes()))
				.containsExactly(UTC_13_30);
		assertThat(unsigned(OracleTimeZoneCodec.encodeTimestampLtz(value.toInstant(), ZoneOffset.ofHours(2)).toBytes()))
				.containsExactly(120, 118, 1, 15, 16, 31, 1);
	}

	@Test
	@DisplayName("TIMESTAMP WITH LOCAL TIME ZONE: writing and reading back keeps the instant, nanoseconds included")
	void local_time_zone_round_trip() {
		for (ZoneId database : List.of(ZoneOffset.UTC, ZoneOffset.ofHours(-5), ZoneId.of("Europe/Berlin"))) {
			for (Instant instant : List.of(Instant.parse("2018-01-15T13:30:00Z"), Instant.parse("1900-01-01T00:00:00Z"),
					Instant.parse("2025-03-09T07:30:15.123456789Z"))) {
				byte[] stored = OracleTimeZoneCodec.encodeTimestampLtz(instant, database).toBytes();
				assertThat(OracleTimeZoneCodec.decodeTimestampLtz(stored, database, SAO_PAULO).toInstant())
						.as("%s in %s", instant, database).isEqualTo(instant);
			}
		}
	}

	@Test
	@DisplayName("TIMESTAMP WITH TIME ZONE: the same as driver 23, over regions, offsets, daylight saving time and old dates")
	void same_as_driver_23() {
		assumeTrue(Driver23.isTheDriver(), "driver 23 is the reference");

		List<String> differences = new ArrayList<>();
		List<ZoneId> zones = List.of(ZoneOffset.UTC, ZoneOffset.ofHoursMinutes(5, 30), ZoneOffset.ofHours(-3),
				ZoneOffset.ofHoursMinutes(-9, -30), SAO_PAULO, ZoneId.of("America/New_York"), ZoneId.of("Asia/Tokyo"),
				ZoneId.of("Europe/London"), ZoneId.of("Australia/Lord_Howe"), ZoneId.of("Asia/Kolkata"));
		List<Instant> instants = List.of(Instant.parse("2018-01-15T13:30:00Z"), Instant.parse("2025-03-09T07:30:15Z"),
				Instant.parse("2025-11-02T05:30:15Z"), Instant.parse("1900-01-01T00:00:00Z"),
				Instant.parse("1970-01-01T00:00:00Z"), Instant.parse("2038-01-19T03:14:08.999999999Z"),
				Instant.parse("2000-02-29T23:59:59.000000001Z"));
		for (ZoneId zone : zones) {
			for (Instant instant : instants) {
				byte[] bytes = Driver23.timestampTz(instant.atZone(zone));
				ZonedDateTime ours = OracleTimeZoneCodec.decodeTimestampTz(bytes);
				ZonedDateTime driver = Driver23.zonedDateTime(bytes);
				if (!ours.equals(driver)) {
					differences.add(instant.atZone(zone) + ": " + ours + " != " + driver);
				}
			}
		}

		assertThat(differences).isEmpty();
	}
}
