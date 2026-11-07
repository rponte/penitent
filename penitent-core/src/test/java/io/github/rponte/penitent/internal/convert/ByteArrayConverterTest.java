package io.github.rponte.penitent.internal.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.sql.rowset.serial.SerialBlob;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.internal.convert.ScalarCodec.ValueConversion;

class ByteArrayConverterTest {

	private final ByteArrayConverter converter = new ByteArrayConverter();

	@Test
	@DisplayName("writes as is, and reads a RAW as is and a BLOB whole")
	void round_trip() throws Exception {
		byte[] value = { 1, 2 };

		assertThat(converter.toDatabase(value)).isSameAs(value);
		assertThat(converter.fromDatabase(value)).isSameAs(value);
		assertThat(converter.fromDatabase(new SerialBlob(new byte[] { 3, 4 }))).containsExactly(3, 4);
	}

	@Test
	@DisplayName("another type is an error, instead of the bytes of its toString")
	void other_type() {
		assertThatThrownBy(() -> converter.fromDatabase("0102"))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessage("cannot convert java.lang.String to byte[]");
	}

	@Test
	@DisplayName("through the codec: null stays null")
	void null_value() {
		assertThat(ScalarCodec.decode(null, byte[].class, ValueConversion.DEFAULT, null)).isNull();
	}
}
