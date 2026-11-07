package io.github.rponte.penitent.internal.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.internal.convert.ScalarCodec.ValueConversion;

class LocalDateTimeConverterTest {

	private static final LocalDateTime MOMENT = LocalDateTime.parse("2018-01-15T10:30:45.123");

	private final LocalDateTimeConverter converter = new LocalDateTimeConverter();

	@Test
	@DisplayName("writes as itself, with the fraction: a Timestamp would go through the JVM's time zone")
	void writes() {
		assertThat(converter.toDatabase(MOMENT)).isEqualTo(MOMENT);
	}

	@Test
	@DisplayName("reads from LocalDateTime, which the driver delivers without the JVM's time zone")
	void reads_java_time() {
		assertThat(converter.fromDatabase(MOMENT)).isEqualTo(MOMENT);
	}

	@Test
	@DisplayName("reads from Timestamp and from java.util.Date")
	void reads() {
		assertThat(converter.fromDatabase(Timestamp.valueOf(MOMENT))).isEqualTo(MOMENT);
		assertThat(converter.fromDatabase(new java.util.Date(Timestamp.valueOf(MOMENT).getTime()))).isEqualTo(MOMENT);
	}

	@Test
	@DisplayName("a java.sql.Date becomes the start of the day")
	void reads_sql_date() {
		assertThat(converter.fromDatabase(Date.valueOf(LocalDate.parse("2018-01-15"))))
				.isEqualTo(LocalDateTime.parse("2018-01-15T00:00:00"));
	}

	@Test
	@DisplayName("a value with a time zone is refused")
	void with_time_zone() {
		assertThatThrownBy(() -> converter.fromDatabase(OffsetDateTime.now()))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("LocalDateTime has no time zone");
		assertThatThrownBy(() -> converter.fromDatabase(ZonedDateTime.now()))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("LocalDateTime has no time zone");
	}

	@Test
	@DisplayName("any other type is an error")
	void other_type() {
		assertThatThrownBy(() -> converter.fromDatabase("2018-01-15"))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("cannot convert java.lang.String to LocalDateTime");
	}

	@Test
	@DisplayName("through the codec: null stays null")
	void null_value() {
		assertThat(ScalarCodec.decode(null, LocalDateTime.class, ValueConversion.DEFAULT, null)).isNull();
	}
}
