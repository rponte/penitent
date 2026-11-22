package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlBlock;
import io.github.rponte.penitent.PlsqlOut;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlRoutine;
import io.github.rponte.penitent.PlsqlType;
import io.github.rponte.penitent.exception.PlsqlMappingException;

/**
 * Oracle's range of dates, January 1, 4712 BC ({@code java.time}'s {@code -4711-01-01}) to December 31, 9999. A value
 * at an edge arrives exactly; a value beyond is refused before the call, on every path a date goes in, and with every
 * Java type. Before #142, it went in: the library builds a {@code DATE}'s bytes itself, and Oracle stores whatever
 * bytes come, so the year 10000 was stored invalid, and the year 25620, whose century wraps around a byte, as the
 * year 20, a valid and wrong date. Driver 23's own {@code DATE.of} does the same; drivers 21 and later store a time
 * zone type beyond the range invalid too.
 */
class DateLimitsTest extends OracleIntegrationTest {

	private static final LocalDate FIRST_DAY = LocalDate.of(-4711, 1, 1);
	private static final LocalDate LAST_DAY = LocalDate.of(9999, 12, 31);
	private static final String BEYOND = "is beyond the range of Oracle's dates, from January 1, 4712 BC (java.time's"
			+ " -4711-01-01) to December 31, 9999: Oracle would store it as an invalid date, or as another date";

	@PlsqlType("DATE_ATTR_T")
	public record DateAttr(LocalDate d) {
	}

	@PlsqlPackage("FIXTURE_DATES")
	interface Dates {
		String dateText(LocalDate pValue);

		@PlsqlRoutine("DATE_TEXT")
		String dateTimeText(LocalDateTime pValue);

		@PlsqlRoutine("DATE_TEXT")
		String utilDateText(java.util.Date pValue);

		@PlsqlRoutine("TIMESTAMP_TEXT")
		String timestampText(LocalDateTime pValue);

		@PlsqlRoutine("TIMESTAMP_TEXT")
		String sqlTimestampText(Timestamp pValue);

		String tstzText(OffsetDateTime pValue);

		@PlsqlRoutine("TSTZ_TEXT")
		String zonedText(ZonedDateTime pValue);

		@PlsqlRoutine("TSTZ_TEXT")
		String instantText(Instant pValue);

		LocalDate echoDate(LocalDate pValue);

		void keep(PlsqlOut<LocalDate> pValue);

		String attrText(DateAttr pValue);

		String listText(List<LocalDate> pValue);
	}

	interface Blocks {
		@PlsqlBlock("begin :result := fixture_dates.date_text(:day); end;")
		String dateText(LocalDate day);
	}

	private Dates dates() {
		return pkg(Dates.class);
	}

	@Test
	@DisplayName("the first and the last day arrive exactly, and come back")
	void edges_arrive_exactly() {
		assertThat(dates().dateText(FIRST_DAY)).isEqualTo("-4712-01-01 00:00:00");
		assertThat(dates().dateText(LAST_DAY)).isEqualTo(" 9999-12-31 00:00:00");
		assertThat(dates().dateTimeText(LAST_DAY.atTime(23, 59, 59))).isEqualTo(" 9999-12-31 23:59:59");
		assertThat(dates().timestampText(LAST_DAY.atTime(23, 59, 59, 999_999_000)))
				.isEqualTo(" 9999-12-31 23:59:59.999999");
		assertThat(dates().tstzText(OffsetDateTime.of(9999, 12, 31, 23, 59, 59, 0, ZoneOffset.UTC)))
				.isEqualTo(" 9999-12-31 23:59:59 +00:00");
		assertThat(dates().echoDate(FIRST_DAY)).isEqualTo(FIRST_DAY);
		assertThat(dates().echoDate(LAST_DAY)).isEqualTo(LAST_DAY);
	}

	@Test
	@DisplayName("a LocalDate or a LocalDateTime beyond is refused before the call, naming the parameter and the value")
	void local_types_beyond() {
		assertThatThrownBy(() -> dates().dateText(LocalDate.of(10000, 1, 1)))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("P_VALUE is DATE: the value +10000-01-01 " + BEYOND);
		assertThatThrownBy(() -> dates().dateText(FIRST_DAY.minusDays(1)))
				.hasMessageContaining("P_VALUE is DATE: the value -4712-12-31 " + BEYOND);
		assertThatThrownBy(() -> dates().dateTimeText(LocalDateTime.of(10000, 1, 1, 0, 0)))
				.hasMessageContaining("the value +10000-01-01T00:00 " + BEYOND);
		assertThatThrownBy(() -> dates().timestampText(LocalDateTime.of(10000, 1, 1, 0, 0)))
				.hasMessageContaining("P_VALUE is TIMESTAMP: the value +10000-01-01T00:00 " + BEYOND);
	}

	@Test
	@DisplayName("a year whose century wraps around a byte, which Oracle stored as another valid date, is refused")
	void wrapping_year() {
		assertThatThrownBy(() -> dates().echoDate(LocalDate.of(25620, 6, 15)))
				.as("it came back as 0020-06-15").hasMessageContaining("the value +25620-06-15 " + BEYOND);
		assertThatThrownBy(() -> dates().dateText(LocalDate.of(-30000, 1, 1)))
				.as("it arrived as 4402 BC").hasMessageContaining("the value -30000-01-01 " + BEYOND);
	}

	@Test
	@DisplayName("a time zone type beyond, by its local day or by its instant in UTC, is refused before the call; drivers"
			+ " 21 and later stored it invalid")
	void time_zone_types_beyond() {
		assertThatThrownBy(() -> dates().tstzText(OffsetDateTime.of(9999, 12, 31, 23, 0, 0, 0, ZoneOffset.ofHours(-3))))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("the value 9999-12-31T23:00-03:00, which is +10000-01-01T02:00 in UTC, " + BEYOND);
		assertThatThrownBy(() -> dates().zonedText(ZonedDateTime.of(10000, 1, 1, 0, 0, 0, 0, ZoneId.of("America/Sao_Paulo"))))
				.hasMessageContaining(BEYOND);
		assertThatThrownBy(() -> dates().instantText(LocalDateTime.of(10000, 1, 1, 0, 0).toInstant(ZoneOffset.UTC)))
				.hasMessageContaining(BEYOND);
	}

	@Test
	@DisplayName("a java.util.Date or a java.sql.Timestamp beyond is refused by the library, with the same message on"
			+ " every driver; the drivers' own messages differ")
	void legacy_types_beyond() {
		Timestamp beyond = Timestamp.valueOf(LocalDateTime.of(10000, 1, 1, 12, 0));
		assertThatThrownBy(() -> dates().sqlTimestampText(beyond))
				.isInstanceOf(PlsqlMappingException.class).hasMessageContaining(BEYOND);
		assertThatThrownBy(() -> dates().utilDateText(new java.util.Date(beyond.getTime())))
				.isInstanceOf(PlsqlMappingException.class).hasMessageContaining(BEYOND);
	}

	@Test
	@DisplayName("every path a date goes in: an IN OUT, an attribute of an OBJECT, an element of a collection, a block's"
			+ " bind, each naming where the value is")
	void every_path() {
		LocalDate beyond = LocalDate.of(10000, 1, 1);
		assertThatThrownBy(() -> dates().keep(PlsqlOut.of(beyond)))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("P_VALUE is DATE: the value +10000-01-01 " + BEYOND);
		assertThatThrownBy(() -> dates().attrText(new DateAttr(beyond)))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("attribute D of PLSQL_TEST.DATE_ATTR_T: the value +10000-01-01 " + BEYOND);
		assertThatThrownBy(() -> dates().listText(List.of(LAST_DAY, beyond)))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("element 2 of PLSQL_TEST.DATE_LIST_T: the value +10000-01-01 " + BEYOND);
		assertThatThrownBy(() -> pkg(Blocks.class).dateText(beyond))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("parameter day: the value +10000-01-01 " + BEYOND);
	}

	@Test
	@DisplayName("within the range, every path keeps the value: the edges through an attribute, a collection and a block")
	void every_path_within() {
		assertThat(dates().attrText(new DateAttr(FIRST_DAY))).isEqualTo("-4712-01-01 00:00:00");
		assertThat(dates().listText(List.of(FIRST_DAY, LAST_DAY)))
				.isEqualTo("-4712-01-01 00:00:00,  9999-12-31 00:00:00");
		assertThat(pkg(Blocks.class).dateText(LAST_DAY)).isEqualTo(" 9999-12-31 00:00:00");
		PlsqlOut<LocalDate> kept = PlsqlOut.of(LAST_DAY);
		dates().keep(kept);
		assertThat(kept.get()).isEqualTo(LAST_DAY);
	}

	@Test
	@DisplayName("before 1582, a LocalDate keeps its fields both ways, though Oracle counts those days in the Julian"
			+ " calendar; a java.util.Date, Julian before 1582 too, crosses by its instant, so the same day arrives with"
			+ " other fields")
	void before_the_gregorian_change() {
		LocalDate day = LocalDate.of(1500, 3, 1);
		assertThat(dates().dateText(day)).isEqualTo(" 1500-03-01 00:00:00");
		assertThat(dates().echoDate(day)).isEqualTo(day);
		java.util.Date sameInstant = java.util.Date.from(day.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant());
		assertThat(dates().utilDateText(sameInstant)).isEqualTo(" 1500-02-20 12:00:00");
	}
}
