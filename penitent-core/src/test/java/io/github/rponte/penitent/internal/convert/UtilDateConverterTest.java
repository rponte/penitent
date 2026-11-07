package io.github.rponte.penitent.internal.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.Date;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.internal.convert.ScalarCodec.ValueConversion;

class UtilDateConverterTest {

	private static final Date VALUE = new Date(1_516_012_200_000L);

	private final UtilDateConverter converter = new UtilDateConverter();

	@Test
	@DisplayName("writes as a Timestamp, keeping the time")
	void write() {
		assertThat(converter.toDatabase(VALUE)).isEqualTo(new Timestamp(VALUE.getTime()));
	}

	@Test
	@DisplayName("reads from Timestamp and java.sql.Date, and always returns a java.util.Date")
	void read() {
		Date fromTimestamp = converter.fromDatabase(new Timestamp(VALUE.getTime()));
		Date fromSqlDate = converter.fromDatabase(new java.sql.Date(VALUE.getTime()));

		assertThat(fromTimestamp).isEqualTo(VALUE).hasSameClassAs(VALUE);
		assertThat(fromSqlDate).isEqualTo(VALUE).hasSameClassAs(VALUE);
	}

	@Test
	@DisplayName("a value with a time zone and another type are errors")
	void rejected() {
		assertThatThrownBy(() -> converter.fromDatabase(OffsetDateTime.now()))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("Date has no time zone");
		assertThatThrownBy(() -> converter.fromDatabase("2018-01-15"))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("cannot convert java.lang.String to java.util.Date");
	}

	@Test
	@DisplayName("through the codec: null stays null")
	void null_value() {
		assertThat(ScalarCodec.decode(null, Date.class, ValueConversion.DEFAULT, null)).isNull();
	}
}
