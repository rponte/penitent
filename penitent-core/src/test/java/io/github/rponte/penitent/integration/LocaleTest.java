package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZonedDateTime;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.zaxxer.hikari.HikariDataSource;

import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.PlsqlRoutine;

/**
 * The driver derives the session {@code NLS_LANGUAGE} and {@code NLS_TERRITORY} from the JVM locale,
 * and they decide how a number or a date becomes text: the decimal separator, the date format, the
 * month names. The library cannot depend on that. Here each pool pins its own, so the result does not
 * depend on the machine running the test, and the build runs in English: this class is where a
 * hostile locale is covered.
 *
 * <p>Whatever can cross the driver as text belongs here, in both pools.
 */
class LocaleTest extends OracleIntegrationTest {

	private static final HikariDataSource AMERICA = withTerritory("AMERICA");
	private static final HikariDataSource BRAZIL = withSession(
			"ALTER SESSION SET NLS_LANGUAGE = 'BRAZILIAN PORTUGUESE' NLS_TERRITORY = 'BRAZIL'");

	private static final LocalDate DATE = LocalDate.parse("2018-01-15");
	private static final LocalDateTime DATE_TIME = LocalDateTime.parse("2018-01-15T10:30:45");
	private static final LocalDateTime FRACTION = LocalDateTime.parse("2018-01-15T10:30:45.123456");
	private static final ZonedDateTime WITH_REGION = ZonedDateTime.parse("2018-01-15T10:30:45.5+09:00[Asia/Tokyo]");
	private static final OffsetDateTime WITH_OFFSET = OffsetDateTime.parse("2018-01-15T10:30:45.5-03:00");

	@PlsqlPackage("FIXTURE_TYPES")
	interface Types {
		BigDecimal echoFloat(BigDecimal value);

		Double echoBinaryDouble(Double value);

		Float echoBinaryFloat(Float value);
	}

	@PlsqlPackage("FIXTURE_CURSOR_LOB")
	interface SpecialChars {
		Double notANumber();
	}

	record DatesRow(OffsetDateTime withTimeZone, Instant sessionLocal, LocalDateTime withoutTimeZone) {
	}

	record TimestampAttribute(LocalDateTime v) {
	}

	record TstzAttribute(ZonedDateTime v) {
	}

	record TsltzAttribute(OffsetDateTime v) {
	}

	@PlsqlPackage("FIXTURE_TIME_ZONE")
	interface Dates {
		LocalDate echoDate(LocalDate value);

		@PlsqlRoutine("ECHO_DATE")
		LocalDateTime echoDateTime(LocalDateTime value);

		LocalDateTime echoTimestamp(LocalDateTime value);

		ZonedDateTime echoTstz(ZonedDateTime value);

		OffsetDateTime echoTsltz(OffsetDateTime value);

		TsltzAttribute echoAttributeTsltz(TsltzAttribute value);

		List<DatesRow> datesCursor();
	}

	@PlsqlPackage("FIXTURE_ATTRIBUTES")
	interface DateAttributes {
		TimestampAttribute echoTimestamp(TimestampAttribute value);

		TstzAttribute echoTstz(TstzAttribute value);
	}

	@PlsqlPackage("FIXTURE_TIME_ZONE_LISTS")
	interface DateLists {
		List<LocalDateTime> echoTimestamp(List<LocalDateTime> value);
	}

	@PlsqlPackage("FIXTURE_TYPES")
	interface DayToSecond {
		Duration echoInterval(Duration value);
	}

	@PlsqlPackage("FIXTURE_LOBS")
	interface YearToMonth {
		Period echoIntervalYm(Period value);
	}

	private final Types inAmerica = PlsqlPackages.create(Types.class, AMERICA);
	private final Types inBrazil = PlsqlPackages.create(Types.class, BRAZIL);

	private static HikariDataSource withTerritory(String territory) {
		return withSession("ALTER SESSION SET NLS_TERRITORY = '" + territory + "'");
	}

	private static HikariDataSource withSession(String initSql) {
		HikariDataSource pool = newPool();
		pool.setConnectionInitSql(initSql);
		return pool;
	}

	private static DataSource pool(String territory) {
		return territory.equals("AMERICA") ? AMERICA : BRAZIL;
	}

	private static String sessionParameter(DataSource pool, String parameter) throws SQLException {
		try (Connection connection = pool.getConnection(); Statement statement = connection.createStatement();
				ResultSet rs = statement.executeQuery(
						"SELECT value FROM nls_session_parameters WHERE parameter = '" + parameter + "'")) {
			rs.next();
			return rs.getString(1);
		}
	}

	@AfterAll
	static void closePools() {
		AMERICA.close();
		BRAZIL.close();
	}

	@Test
	@DisplayName("FLOAT and BINARY_DOUBLE, with a period as the decimal separator")
	void decimal_point() {
		assertThat(inAmerica.echoFloat(new BigDecimal("1.5"))).isEqualByComparingTo("1.5");
		assertThat(inAmerica.echoBinaryDouble(1.5d)).isEqualTo(1.5d);
	}

	@Test
	@DisplayName("FLOAT with a comma as the decimal separator")
	void float_with_decimal_comma() {
		assertThat(inBrazil.echoFloat(new BigDecimal("1.5"))).isEqualByComparingTo("1.5");
	}

	@Test
	@DisplayName("BINARY_DOUBLE with a comma as the decimal separator")
	void binary_double_with_decimal_comma() {
		assertThat(inBrazil.echoBinaryDouble(1.5d)).isEqualTo(1.5d);
	}

	@Test
	@DisplayName("BINARY_FLOAT becomes Float, with a period as the decimal separator")
	void binary_float_with_decimal_point() {
		assertThat(inAmerica.echoBinaryFloat(1.5f)).isEqualTo(1.5f);
	}

	@Test
	@DisplayName("BINARY_FLOAT becomes Float, with a comma as the decimal separator")
	void binary_float_with_decimal_comma() {
		assertThat(inBrazil.echoBinaryFloat(1.5f)).isEqualTo(1.5f);
	}

	@Test
	@DisplayName("BINARY_DOUBLE_NAN does not go through BigDecimal, which cannot represent it")
	void not_a_number() {
		assertThat(PlsqlPackages.create(SpecialChars.class, BRAZIL).notANumber()).isNaN();
	}

	@Test
	@DisplayName("the pools are what they claim: the Brazilian one writes numbers and dates differently")
	void sessions_differ() throws SQLException {
		assertThat(sessionParameter(AMERICA, "NLS_NUMERIC_CHARACTERS")).isEqualTo(".,");
		assertThat(sessionParameter(BRAZIL, "NLS_NUMERIC_CHARACTERS")).isEqualTo(",.");
		assertThat(sessionParameter(BRAZIL, "NLS_DATE_LANGUAGE")).isEqualTo("BRAZILIAN PORTUGUESE");
		assertThat(sessionParameter(BRAZIL, "NLS_DATE_FORMAT"))
				.isNotEqualTo(sessionParameter(AMERICA, "NLS_DATE_FORMAT"));
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = { "AMERICA", "BRAZIL" })
	@DisplayName("DATE in LocalDate and LocalDateTime, keeping the time")
	void date(String territory) {
		Dates dates = PlsqlPackages.create(Dates.class, pool(territory));

		assertThat(dates.echoDate(DATE)).isEqualTo(DATE);
		assertThat(dates.echoDateTime(DATE_TIME)).isEqualTo(DATE_TIME);
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = { "AMERICA", "BRAZIL" })
	@DisplayName("TIMESTAMP, with fractional seconds")
	void timestamp(String territory) {
		Dates dates = PlsqlPackages.create(Dates.class, pool(territory));

		assertThat(dates.echoTimestamp(FRACTION)).isEqualTo(FRACTION);
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = { "AMERICA", "BRAZIL" })
	@DisplayName("TIMESTAMP WITH TIME ZONE keeps the region, and WITH LOCAL TIME ZONE the instant")
	void timestamps_with_time_zone(String territory) {
		Dates dates = PlsqlPackages.create(Dates.class, pool(territory));

		assertExactly(dates.echoTstz(WITH_REGION), WITH_REGION);
		assertThat(dates.echoTsltz(WITH_OFFSET)).isAtSameInstantAs(WITH_OFFSET);
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = { "AMERICA", "BRAZIL" })
	@DisplayName("INTERVAL DAY TO SECOND and YEAR TO MONTH")
	void intervals(String territory) {
		Duration duration = Duration.parse("P3DT4H5M6.789S");
		Period period = Period.of(2, 7, 0);

		assertThat(PlsqlPackages.create(DayToSecond.class, pool(territory)).echoInterval(duration)).isEqualTo(duration);
		assertThat(PlsqlPackages.create(YearToMonth.class, pool(territory)).echoIntervalYm(period)).isEqualTo(period);
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = { "AMERICA", "BRAZIL" })
	@DisplayName("dates as OBJECT attributes and collection elements")
	void dates_in_composites(String territory) {
		DateAttributes attributes = PlsqlPackages.create(DateAttributes.class, pool(territory));
		Dates dates = PlsqlPackages.create(Dates.class, pool(territory));

		assertThat(attributes.echoTimestamp(new TimestampAttribute(FRACTION))).isEqualTo(new TimestampAttribute(FRACTION));
		assertThat(attributes.echoTstz(new TstzAttribute(WITH_REGION))).isEqualTo(new TstzAttribute(WITH_REGION));
		assertThat(dates.echoAttributeTsltz(new TsltzAttribute(WITH_OFFSET)).v()).isAtSameInstantAs(WITH_OFFSET);
		assertThat(PlsqlPackages.create(DateLists.class, pool(territory)).echoTimestamp(List.of(FRACTION, DATE_TIME)))
				.containsExactly(FRACTION, DATE_TIME);
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = { "AMERICA", "BRAZIL" })
	@DisplayName("dates as cursor columns")
	void dates_in_cursor(String territory) {
		ZonedDateTime tokyo = ZonedDateTime.parse("2018-01-15T10:30:00+09:00[Asia/Tokyo]");

		DatesRow row = PlsqlPackages.create(Dates.class, pool(territory)).datesCursor().get(0);

		assertExactly(row.withTimeZone(), tokyo.toOffsetDateTime());
		assertThat(row.sessionLocal()).isEqualTo(tokyo.toInstant());
		assertThat(row.withoutTimeZone()).isEqualTo(tokyo.toLocalDateTime());
	}

	/**
	 * AssertJ compares a {@code ZonedDateTime} or {@code OffsetDateTime} by its instant, so a lost region
	 * or offset would pass; this compares with {@code equals}.
	 */
	private static void assertExactly(Object actual, Object expected) {
		assertThat(actual).isEqualTo(expected);
	}
}
