package io.github.rponte.penitent.internal.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Blob;

import javax.sql.rowset.serial.SerialBlob;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.internal.convert.ScalarCodec.ValueConversion;

class BlobConverterTest {

	private final BlobConverter converter = new BlobConverter();

	@Test
	@DisplayName("reads a copy in memory, from a Blob or the bytes of a RAW")
	void read() throws Exception {
		Blob source = new SerialBlob(new byte[] { 1, 2 });

		Blob copy = converter.fromDatabase(source);

		assertThat(copy).isNotSameAs(source);
		assertThat(copy.getBytes(1, 2)).containsExactly(1, 2);
		assertThat(converter.fromDatabase(new byte[] { 3 }).getBytes(1, 1)).containsExactly(3);
	}

	@Test
	@DisplayName("another type is an error, and null stays null")
	void other_type_and_null() {
		assertThatThrownBy(() -> converter.fromDatabase("x"))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessage("cannot convert java.lang.String to byte[]");
		assertThat(ScalarCodec.decode(null, Blob.class, ValueConversion.DEFAULT, null)).isNull();
	}
}
