package io.github.rponte.penitent.internal.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.internal.convert.ScalarCodec.ValueConversion;

class LocalDateConverterTest {

	private static final LocalDate DAY = LocalDate.parse("2018-01-15");

	private final LocalDateConverter converter = new LocalDateConverter();

	@Test
	@DisplayName("writes as itself: a java.sql.Date would go through the JVM's time zone")
	void writes() {
		assertThat(converter.toDatabase(DAY)).isEqualTo(DAY);
	}

	@Test
	@DisplayName("reads from LocalDate and LocalDateTime, which the driver delivers without the JVM's time zone")
	void reads_java_time() {
		assertThat(converter.fromDatabase(DAY)).isEqualTo(DAY);
		assertThat(converter.fromDatabase(DAY.atTime(10, 30))).isEqualTo(DAY);
	}

	@Test
	@DisplayName("reads from Date, from Timestamp and from java.util.Date")
	void reads() {
		assertThat(converter.fromDatabase(Date.valueOf(DAY))).isEqualTo(DAY);
		assertThat(converter.fromDatabase(Timestamp.valueOf(DAY.atTime(10, 30)))).isEqualTo(DAY);
		assertThat(converter.fromDatabase(new java.util.Date(Date.valueOf(DAY).getTime()))).isEqualTo(DAY);
	}

	@Test
	@DisplayName("a value with a time zone is refused")
	void with_time_zone() {
		assertThatThrownBy(() -> converter.fromDatabase(OffsetDateTime.now()))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("LocalDate has no time zone");
		assertThatThrownBy(() -> converter.fromDatabase(ZonedDateTime.now()))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("LocalDate has no time zone");
	}

	@Test
	@DisplayName("any other type is an error")
	void other_type() {
		assertThatThrownBy(() -> converter.fromDatabase("2018-01-15"))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("cannot convert java.lang.String to LocalDate");
	}

	@Test
	@DisplayName("through the codec: null stays null")
	void null_value() {
		assertThat(ScalarCodec.decode(null, LocalDate.class, ValueConversion.DEFAULT, null)).isNull();
	}
}
