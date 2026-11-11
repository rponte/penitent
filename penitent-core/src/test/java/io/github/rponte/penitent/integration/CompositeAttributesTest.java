package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlBoolean;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlRoutine;

/** Attribute types beyond the ones {@link ObjectTypesTest} already exercises. */
class CompositeAttributesTest extends OracleIntegrationTest {

	record WithTimestamp(LocalDateTime v) {
	}

	record WithTstz(OffsetDateTime v) {
	}

	record WithBytes(byte[] v) {
	}

	record WithText(String v) {
	}

	record WithDouble(Double v) {
	}

	record WithBoolean(Boolean v) {
	}

	record WithVarray(List<Integer> numbers) {
	}

	record BooleanRecord(BigDecimal id, Boolean flag) {
	}

	record RecordBooleanWithLiterals(BigDecimal id, @PlsqlBoolean(trueValue = "1", falseValue = "0") Boolean flag) {
	}

	@PlsqlPackage("FIXTURE_ATTRIBUTES")
	interface Attributes {
		WithTimestamp echoTimestamp(WithTimestamp value);

		WithTstz echoTstz(WithTstz value);

		WithBytes echoRaw(WithBytes value);

		WithText echoChar(WithText value);

		WithDouble echoBinaryDouble(WithDouble value);

		WithText echoNvarchar2(WithText value);

		WithVarray echoVarray(WithVarray value);

		WithText echoClob(WithText value);

		WithText aClob();

		WithBytes echoBlob(WithBytes value);

		BooleanRecord echoBooleanRecord(BooleanRecord value);

		BooleanRecord aBooleanRecord(int value);

		@PlsqlRoutine("ECHO_BOOLEAN_RECORD")
		RecordBooleanWithLiterals echoRecordWithLiterals(RecordBooleanWithLiterals value);

		@PlsqlRoutine("A_BOOLEAN_RECORD")
		RecordBooleanWithLiterals aRecordWithLiterals(int value);
	}

	/** A package of its own because it exists only on Oracle 23ai: see {@link #assumeSqlBoolean()}. */
	@PlsqlPackage("FIXTURE_SQL_BOOLEAN")
	interface SqlBooleanAttributes {
		WithBoolean echoBoolean(WithBoolean value);
	}

	private final Attributes attributes = pkg(Attributes.class);

	@Test
	@DisplayName("TIMESTAMP becomes LocalDateTime")
	void timestamp() {
		LocalDateTime value = LocalDateTime.parse("2018-01-15T10:30:00");

		assertThat(attributes.echoTimestamp(new WithTimestamp(value)).v()).isEqualTo(value);
	}

	@Test
	@DisplayName("TIMESTAMP WITH TIME ZONE becomes OffsetDateTime")
	void timestamp_with_time_zone() {
		OffsetDateTime value = OffsetDateTime.parse("2018-01-15T10:30:00-03:00");

		assertThat(attributes.echoTstz(new WithTstz(value)).v()).isEqualTo(value);
	}

	@Test
	@DisplayName("RAW becomes byte[]")
	void raw() {
		assertThat(attributes.echoRaw(new WithBytes(new byte[] { 1 })).v()).containsExactly(1);
	}

	@Test
	@DisplayName("CHAR and NVARCHAR2 become String")
	void char_and_nvarchar2() {
		assertThat(attributes.echoChar(new WithText("abc")).v()).isEqualTo("abc");
		assertThat(attributes.echoNvarchar2(new WithText("n")).v()).isEqualTo("n");
	}

	@Test
	@DisplayName("BINARY_DOUBLE becomes Double")
	void binary_double() {
		assertThat(attributes.echoBinaryDouble(new WithDouble(1.5d)).v()).isEqualTo(1.5d);
	}

	@Test
	@DisplayName("VARRAY as an attribute")
	void varray() {
		assertThat(attributes.echoVarray(new WithVarray(List.of(1, 2))).numbers()).containsExactly(1, 2);
	}

	@Test
	@DisplayName("BOOLEAN in a RECORD goes across as 1/0, with @PlsqlBoolean")
	void boolean_in_record_with_literals() {
		assertThat(attributes.aRecordWithLiterals(1).flag()).isTrue();
		assertThat(attributes.aRecordWithLiterals(-1).flag()).isFalse();
		assertThat(attributes.echoRecordWithLiterals(new RecordBooleanWithLiterals(BigDecimal.ONE, true)).flag())
				.isTrue();
	}

	@Test
	@DisplayName("BOOLEAN in a RECORD, without @PlsqlBoolean, on read")
	void boolean_in_record_on_read() {
		assertThat(attributes.aBooleanRecord(1).flag()).isTrue();
		assertThat(attributes.aBooleanRecord(-1).flag()).isFalse();
	}

	@Test
	@DisplayName("BOOLEAN in a RECORD, without @PlsqlBoolean, on write")
	void boolean_in_record_on_write() {
		assertThat(attributes.echoBooleanRecord(new BooleanRecord(BigDecimal.ONE, true)).flag()).isTrue();
		assertThat(attributes.echoBooleanRecord(new BooleanRecord(BigDecimal.ONE, false)).flag()).isFalse();
	}

	@Test
	@DisplayName("BOOLEAN in an OBJECT, without @PlsqlBoolean, in both directions")
	void boolean_in_object() {
		assumeSqlBoolean();
		SqlBooleanAttributes sqlBoolean = pkg(SqlBooleanAttributes.class);

		assertThat(sqlBoolean.echoBoolean(new WithBoolean(true)).v()).isTrue();
		assertThat(sqlBoolean.echoBoolean(new WithBoolean(false)).v()).isFalse();
	}

	@Test
	@DisplayName("a null BOOLEAN in an OBJECT stays null")
	void null_boolean_attribute() {
		assumeSqlBoolean();

		assertThat(pkg(SqlBooleanAttributes.class).echoBoolean(new WithBoolean(null)).v()).isNull();
	}

	@Test
	@DisplayName("a null BOOLEAN in a RECORD stays null")
	void null_boolean_in_record() {
		assertThat(attributes.echoBooleanRecord(new BooleanRecord(BigDecimal.ONE, null)).flag()).isNull();
	}

	@Test
	@DisplayName("CLOB becomes String, on read")
	void clob_on_read() {
		assertThat(attributes.aClob().v()).isEqualTo("abc");
	}

	@Test
	@DisplayName("CLOB receives String, on write")
	void clob_on_write() {
		assertThat(attributes.echoClob(new WithText("c")).v()).isEqualTo("c");
	}

	@Test
	@DisplayName("BLOB becomes byte[]")
	void blob() {
		assertThat(attributes.echoBlob(new WithBytes(new byte[] { 1 })).v()).containsExactly(1);
	}
}
