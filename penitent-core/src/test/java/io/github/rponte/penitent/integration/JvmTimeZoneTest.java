package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.TimeZone;
import java.util.function.Consumer;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zaxxer.hikari.HikariDataSource;

import io.github.rponte.penitent.PlsqlBlock;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.PlsqlRoutine;

/**
 * The driver takes the session {@code TIME_ZONE} from the JVM's default time zone when it connects, and
 * {@code java.sql.Date} and {@code Timestamp} hold a local date and time in that zone. The build runs in
 * UTC, which has no daylight saving time; here the JVM moves to a zone that has it, for the duration of
 * each test, so that a local time that does not exist there, or exists twice, still crosses unchanged.
 *
 * <p>The suite runs one test at a time, so changing the default time zone affects only this class.
 */
class JvmTimeZoneTest extends OracleIntegrationTest {

	/** 2025-03-09 in New York: the clocks jump from 02:00 to 03:00, so 02:30 does not exist there. */
	private static final LocalDateTime GAP = LocalDateTime.parse("2025-03-09T02:30:15");

	/** 2025-11-02 in New York: the clocks go back from 02:00 to 01:00, so 01:30 happens twice. */
	private static final LocalDateTime OVERLAP = LocalDateTime.parse("2025-11-02T01:30:15");

	/** 2018-11-04 in São Paulo: the clocks jumped from 00:00 to 01:00, so the day had no midnight. */
	private static final LocalDate NO_MIDNIGHT = LocalDate.parse("2018-11-04");

	record TimestampAttribute(LocalDateTime v) {
	}

	@PlsqlPackage("FIXTURE_TIME_ZONE")
	interface Dates {
		LocalDateTime echoTimestamp(LocalDateTime value);

		@PlsqlRoutine("ECHO_DATE")
		LocalDateTime echoDateTime(LocalDateTime value);

		LocalDate echoDate(LocalDate value);

		OffsetDateTime echoTsltz(OffsetDateTime value);
	}

	@PlsqlPackage("FIXTURE_ATTRIBUTES")
	interface Attributes {
		TimestampAttribute echoTimestamp(TimestampAttribute value);
	}

	@PlsqlPackage("FIXTURE_TIME_ZONE_LISTS")
	interface Lists {
		List<LocalDateTime> echoTimestamp(List<LocalDateTime> value);
	}

	/** What the database received, and values created by the database itself. */
	interface InTheDatabase {
		@PlsqlBlock("begin :result := to_char(:value, 'YYYY-MM-DD HH24:MI:SS'); end;")
		String timestampAsText(LocalDateTime value);

		@PlsqlBlock("begin :result := to_char(:value, 'YYYY-MM-DD HH24:MI:SS'); end;")
		String dateAsText(LocalDate value);

		@PlsqlBlock("begin :result := TIMESTAMP '2025-03-09 02:30:15'; end;")
		LocalDateTime gapTimestamp();

		@PlsqlBlock("begin :result := TO_DATE('2025-03-09 02:30:15', 'YYYY-MM-DD HH24:MI:SS'); end;")
		LocalDateTime gapDate();

		@PlsqlBlock("begin :result := DATE '2018-11-04'; end;")
		LocalDate noMidnightDate();

		@PlsqlBlock("begin open :result for select TIMESTAMP '2025-03-09 02:30:15' as v from dual; end;")
		List<LocalDateTime> gapInCursor();
	}

	/** Runs with the JVM in {@code zone}, on connections opened in it, and restores the zone after. */
	private static void inZone(String zone, Consumer<DataSource> test) {
		TimeZone original = TimeZone.getDefault();
		TimeZone.setDefault(TimeZone.getTimeZone(zone));
		try (HikariDataSource pool = newPool()) {
			test.accept(pool);
		} finally {
			TimeZone.setDefault(original);
		}
	}

	private static String sessionTimeZone(DataSource pool) {
		try (Connection connection = pool.getConnection(); Statement statement = connection.createStatement();
				ResultSet rs = statement.executeQuery("SELECT SESSIONTIMEZONE FROM DUAL")) {
			rs.next();
			return rs.getString(1);
		} catch (SQLException e) {
			throw new IllegalStateException(e);
		}
	}

	@Test
	@DisplayName("the session takes the JVM's time zone, which is what makes the other tests hostile")
	void session_follows_the_jvm() {
		inZone("America/New_York", pool -> assertThat(sessionTimeZone(pool)).isEqualTo("America/New_York"));
	}

	@Test
	@DisplayName("a LocalDateTime that does not exist in the JVM's zone reaches the database unchanged")
	void gap_sent() {
		inZone("America/New_York", pool -> {
			InTheDatabase database = PlsqlPackages.create(InTheDatabase.class, pool);
			Dates dates = PlsqlPackages.create(Dates.class, pool);

			assertThat(database.timestampAsText(GAP)).isEqualTo("2025-03-09 02:30:15");
			assertThat(dates.echoTimestamp(GAP)).isEqualTo(GAP);
			assertThat(dates.echoDateTime(GAP)).isEqualTo(GAP);
		});
	}

	@Test
	@DisplayName("a LocalDateTime that does not exist in the JVM's zone comes from the database unchanged")
	void gap_received() {
		inZone("America/New_York", pool -> {
			InTheDatabase database = PlsqlPackages.create(InTheDatabase.class, pool);

			assertThat(database.gapTimestamp()).isEqualTo(GAP);
			assertThat(database.gapDate()).isEqualTo(GAP);
			assertThat(database.gapInCursor()).containsExactly(GAP);
		});
	}

	@Test
	@DisplayName("a LocalDateTime in the hour that happens twice crosses unchanged")
	void overlap() {
		inZone("America/New_York", pool -> {
			InTheDatabase database = PlsqlPackages.create(InTheDatabase.class, pool);

			assertThat(database.timestampAsText(OVERLAP)).isEqualTo("2025-11-02 01:30:15");
			assertThat(PlsqlPackages.create(Dates.class, pool).echoTimestamp(OVERLAP)).isEqualTo(OVERLAP);
		});
	}

	@Test
	@DisplayName("a LocalDateTime that does not exist in the JVM's zone, as attribute and collection element")
	void gap_in_composites() {
		inZone("America/New_York", pool -> {
			assertThat(PlsqlPackages.create(Attributes.class, pool).echoTimestamp(new TimestampAttribute(GAP)))
					.isEqualTo(new TimestampAttribute(GAP));
			assertThat(PlsqlPackages.create(Lists.class, pool).echoTimestamp(List.of(GAP))).containsExactly(GAP);
		});
	}

	@Test
	@DisplayName("a LocalDate whose midnight does not exist in the JVM's zone stays a date, at 00:00")
	void day_without_midnight() {
		inZone("America/Sao_Paulo", pool -> {
			InTheDatabase database = PlsqlPackages.create(InTheDatabase.class, pool);

			assertThat(database.dateAsText(NO_MIDNIGHT)).isEqualTo("2018-11-04 00:00:00");
			assertThat(PlsqlPackages.create(Dates.class, pool).echoDate(NO_MIDNIGHT)).isEqualTo(NO_MIDNIGHT);
			assertThat(database.noMidnightDate()).isEqualTo(NO_MIDNIGHT);
		});
	}

	@Test
	@DisplayName("TIMESTAMP WITH LOCAL TIME ZONE keeps the instant in a session with daylight saving time")
	void local_time_zone() {
		OffsetDateTime inTheGap = OffsetDateTime.parse("2025-03-09T07:30:15Z");

		inZone("America/New_York", pool -> assertThat(PlsqlPackages.create(Dates.class, pool).echoTsltz(inTheGap))
				.isAtSameInstantAs(inTheGap));
	}
}
