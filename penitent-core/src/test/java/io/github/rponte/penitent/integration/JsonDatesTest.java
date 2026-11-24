package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlBlock;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.exception.PlsqlRoutineRaisedException;

/**
 * A date inside a JSON document, which an application writes as a {@code String} itself: a {@code Map} takes no
 * {@code java.time} value (#153). What the types page advises, pinned on 23ai and 21c: the seconds written, which
 * {@code toString()} leaves out at a whole minute and 21c then refuses, and how PL/SQL reads the text back.
 */
class JsonDatesTest extends OracleIntegrationTest {

	private static final LocalDateTime WHEN = LocalDateTime.of(2026, 11, 24, 10, 15);

	interface Reads {
		@PlsqlBlock("begin select JSON_VALUE(JSON(:doc), '$.when' RETURNING DATE PRESERVE TIME ERROR ON ERROR)"
				+ " into :result from dual; end;")
		LocalDateTime date(String doc);

		@PlsqlBlock("begin select TO_CHAR(JSON_VALUE(JSON(:doc), '$.when' RETURNING DATE PRESERVE TIME ERROR ON ERROR),"
				+ " 'SYYYY-MM-DD') into :result from dual; end;")
		String signedYear(String doc);

		@PlsqlBlock("begin select JSON_VALUE(JSON(:doc), '$.when' RETURNING DATE ERROR ON ERROR) into :result from dual;"
				+ " end;")
		LocalDateTime dateTruncated(String doc);

		@PlsqlBlock("begin select JSON_VALUE(JSON(:doc), '$.when' RETURNING DATE PRESERVE TIME) into :result from dual;"
				+ " end;")
		LocalDateTime dateNullOnError(String doc);

		@PlsqlBlock("begin select JSON_VALUE(JSON(:doc), '$.when' RETURNING TIMESTAMP ERROR ON ERROR) into :result"
				+ " from dual; end;")
		LocalDateTime timestamp(String doc);

		@PlsqlBlock("begin select JSON_VALUE(JSON(:doc), '$.when' RETURNING TIMESTAMP WITH TIME ZONE ERROR ON ERROR)"
				+ " into :result from dual; end;")
		OffsetDateTime withTimeZone(String doc);
	}

	@PlsqlPackage("FIXTURE_JSON_DATES")
	interface Stored {
		Map<String, Object> stored();
	}

	@BeforeEach
	void needsJson() {
		assumeTrue(DATABASE_MAJOR_VERSION >= 21, "the JSON type exists only from Oracle 21c on");
	}

	/** The document's text, as Penitent writes the Map: through the fixture's echo. */
	private static String text(Map<String, Object> order) {
		return pkg(JsonMapTest.Documents.class).text(order);
	}

	private static String doc(String when) {
		Map<String, Object> order = new LinkedHashMap<>();
		order.put("when", when);
		return text(order);
	}

	@Test
	@DisplayName("a date written with its seconds is read back the same on 23ai and 21c, with RETURNING DATE PRESERVE"
			+ " TIME")
	void with_seconds() {
		Map<String, Object> order = new LinkedHashMap<>();
		LocalDateTime when = WHEN;
		// snippet: types/json-date
		// with the seconds, which toString() leaves out at a whole minute, and Oracle 21c then refuses
		order.put("when", when.format(DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss")));
		// end-snippet
		assertThat(order.get("when")).isEqualTo("2026-11-24T10:15:00");

		assertThat(pkg(Reads.class).date(text(order))).isEqualTo(WHEN);
	}

	@Test
	@DisplayName("toString() leaves the seconds out at a whole minute: 23ai reads it, 21c refuses it with ORA-40688")
	void to_string_at_a_whole_minute() {
		assertThat(WHEN.toString()).isEqualTo("2026-11-24T10:15");
		if (DATABASE_MAJOR_VERSION >= 23) {
			assertThat(pkg(Reads.class).date(doc(WHEN.toString()))).isEqualTo(WHEN);
		} else {
			assertThatThrownBy(() -> pkg(Reads.class).date(doc(WHEN.toString())))
					.isInstanceOf(PlsqlRoutineRaisedException.class)
					.hasMessageContaining("ORA-40688");
		}
	}

	@Test
	@DisplayName("RETURNING DATE without PRESERVE TIME drops the time")
	void returning_date_drops_the_time() {
		assertThat(pkg(Reads.class).dateTruncated(doc("2026-11-24T10:15:30"))).isEqualTo(LocalDateTime.of(2026, 11, 24,
				0, 0));
	}

	@Test
	@DisplayName("without ERROR ON ERROR, a text Oracle cannot read becomes NULL, with no error")
	void null_on_error() {
		assertThat(pkg(Reads.class).dateNullOnError(doc("24/11/2026"))).isNull();
		assertThatThrownBy(() -> pkg(Reads.class).date(doc("24/11/2026")))
				.isInstanceOf(PlsqlRoutineRaisedException.class);
	}

	@Test
	@DisplayName("an offset is kept by TIMESTAMP WITH TIME ZONE, and converted to UTC by TIMESTAMP")
	void an_offset() {
		String doc = doc("2026-11-24T10:15:30-03:00");
		assertThat(pkg(Reads.class).withTimeZone(doc))
				.isEqualTo(OffsetDateTime.of(2026, 11, 24, 10, 15, 30, 0, ZoneOffset.ofHours(-3)));
		assertThat(pkg(Reads.class).timestamp(doc)).isEqualTo(LocalDateTime.of(2026, 11, 24, 13, 15, 30));
	}

	@Test
	@DisplayName("a year before 1 is read a year later, with no error: -0001 is 2 BC in Java, whose ISO numbering has a"
			+ " year 0, and 1 BC in Oracle's, which has none, as SYYYY shows")
	void a_year_before_christ() {
		String when = LocalDateTime.of(-1, 1, 1, 0, 0).format(DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss"));
		assertThat(when).isEqualTo("-0001-01-01T00:00:00");
		assertThat(pkg(Reads.class).signedYear(doc(when))).isEqualTo("-0001-01-01");
		// read back as Penitent reads a DATE, in Java's numbering: year 0, 1 BC, a year after the 2 BC sent
		assertThat(pkg(Reads.class).date(doc(when))).isEqualTo(LocalDateTime.of(0, 1, 1, 0, 0));
	}

	@Test
	@DisplayName("a date the database stored in the document, as a date of OSON's own, comes back in a Map as a String,"
			+ " the text Oracle writes: with a time, even for a DATE without one")
	void stored_dates_come_back_as_strings() {
		assertThat(pkg(Stored.class).stored())
				.containsExactly(Map.entry("day", "2026-11-24T00:00:00"), Map.entry("at", "2026-11-24T10:15:30.123456"));
	}
}
