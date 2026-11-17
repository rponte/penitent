package io.github.rponte.penitent.mock;

import static io.github.rponte.penitent.mock.PlsqlMocks.doNothing;
import static io.github.rponte.penitent.mock.PlsqlMocks.eq;
import static io.github.rponte.penitent.mock.PlsqlMocks.out;
import static io.github.rponte.penitent.mock.PlsqlMocks.when;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlBoolean;
import io.github.rponte.penitent.PlsqlOut;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.convert.PlsqlConvert;
import io.github.rponte.penitent.convert.PlsqlConverter;

/**
 * A stub's scalar value goes through the application's mapping, as a call would send it: an enum by its name, a UUID
 * as text, a boolean by the literals of {@code @PlsqlBoolean}, a converter, and the rest. The real routines fail.
 */
class StubScalarMappingTest {

	enum Uf {
		SP, CE
	}

	record Code(String value) {
	}

	/** A code is kept with a prefix in the database. */
	public static class CodeConverter implements PlsqlConverter<Code, String> {
		@Override
		public String toDatabase(Code value) {
			return value == null ? null : "C-" + value.value();
		}

		@Override
		public Code fromDatabase(String value) {
			return value == null ? null : new Code(value.substring(2));
		}
	}

	@PlsqlPackage("FREIGHT_TYPES")
	interface Types {
		Uf ufOf(BigDecimal pId);

		UUID tokenOf(BigDecimal pId);

		@PlsqlBoolean(trueValue = "Y", falseValue = "N")
		Boolean activeYn(BigDecimal pId);

		Boolean activeNum(BigDecimal pId);

		Date createdOn(BigDecimal pId);

		Timestamp stampedAt(BigDecimal pId);

		Optional<String> noteOf(BigDecimal pId);

		Double ratioOf(BigDecimal pId);

		Float ratioF(BigDecimal pId);

		@PlsqlConvert(CodeConverter.class)
		Code codeOf(BigDecimal pId);

		void load(BigDecimal pId, PlsqlOut<Uf> pUf,
				@PlsqlBoolean(trueValue = "Y", falseValue = "N") PlsqlOut<Boolean> pActive, PlsqlOut<Date> pCreated,
				@PlsqlConvert(CodeConverter.class) PlsqlOut<Code> pCode);

		String describe(BigDecimal pId);
	}

	private static final UUID TOKEN = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

	private static final Date CREATED = Date.from(Instant.parse("2026-11-17T13:30:15Z"));

	private static DataSource dataSource;

	private PlsqlMocks mocks;
	private Types types;

	@BeforeAll
	static void startDatabase() {
		dataSource = PlsqlMocks.wrap(TestDatabase.pool());
	}

	@BeforeEach
	void begin() {
		mocks = PlsqlMocks.on(dataSource);
		mocks.begin();
		types = PlsqlPackages.create(Types.class, dataSource);
	}

	@AfterEach
	void end() {
		mocks.end();
	}

	@Test
	@DisplayName("a stub returns an enum, by its name, and a UUID, as text")
	void enum_and_uuid() {
		Types mock = mocks.mock(Types.class);
		when(mock.ufOf(BigDecimal.ONE)).thenReturn(Uf.CE);
		when(mock.tokenOf(BigDecimal.ONE)).thenReturn(TOKEN);

		assertThat(types.ufOf(BigDecimal.ONE)).isEqualTo(Uf.CE);
		assertThat(types.tokenOf(BigDecimal.ONE)).isEqualTo(TOKEN);
	}

	@Test
	@DisplayName("a stub returns a boolean by the literals of @PlsqlBoolean on a VARCHAR2, and as 1 or 0 on a NUMBER")
	void booleans_as_text_and_numbers() {
		Types mock = mocks.mock(Types.class);
		when(mock.activeYn(BigDecimal.ONE)).thenReturn(true);
		when(mock.activeYn(BigDecimal.TEN)).thenReturn(false);
		when(mock.activeNum(BigDecimal.ONE)).thenReturn(false);

		assertThat(types.activeYn(BigDecimal.ONE)).isTrue();
		assertThat(types.activeYn(BigDecimal.TEN)).isFalse();
		assertThat(types.activeNum(BigDecimal.ONE)).isFalse();
	}

	@Test
	@DisplayName("a stub returns a java.util.Date to a DATE and a java.sql.Timestamp to a TIMESTAMP, with their time")
	void legacy_dates() {
		Types mock = mocks.mock(Types.class);
		Timestamp stamped = Timestamp.valueOf(LocalDateTime.of(2026, 11, 17, 10, 30, 0, 123_456_000));
		when(mock.createdOn(BigDecimal.ONE)).thenReturn(CREATED);
		when(mock.stampedAt(BigDecimal.ONE)).thenReturn(stamped);

		assertThat(types.createdOn(BigDecimal.ONE)).isEqualTo(CREATED);
		assertThat(types.stampedAt(BigDecimal.ONE)).isEqualTo(stamped);
	}

	@Test
	@DisplayName("a stub returns an Optional: its value, or NULL when empty")
	void optional() {
		Types mock = mocks.mock(Types.class);
		when(mock.noteOf(BigDecimal.ONE)).thenReturn(Optional.of("fragile"));
		when(mock.noteOf(BigDecimal.TEN)).thenReturn(Optional.empty());

		assertThat(types.noteOf(BigDecimal.ONE)).contains("fragile");
		assertThat(types.noteOf(BigDecimal.TEN)).isEmpty();
	}

	@Test
	@DisplayName("a stub returns NaN and infinities of a BINARY_DOUBLE and a BINARY_FLOAT")
	void nan_and_infinities() {
		Types mock = mocks.mock(Types.class);
		when(mock.ratioOf(BigDecimal.ONE)).thenReturn(Double.NaN);
		when(mock.ratioOf(BigDecimal.TEN)).thenReturn(Double.NEGATIVE_INFINITY);
		when(mock.ratioF(BigDecimal.ONE)).thenReturn(Float.POSITIVE_INFINITY);

		assertThat(types.ratioOf(BigDecimal.ONE)).isNaN();
		assertThat(types.ratioOf(BigDecimal.TEN)).isEqualTo(Double.NEGATIVE_INFINITY);
		assertThat(types.ratioF(BigDecimal.ONE)).isEqualTo(Float.POSITIVE_INFINITY);
	}

	@Test
	@DisplayName("a stub returns a value through the converter declared on the method")
	void method_converter() {
		when(mocks.mock(Types.class).codeOf(BigDecimal.ONE)).thenReturn(new Code("42"));

		assertThat(types.codeOf(BigDecimal.ONE)).isEqualTo(new Code("42"));
	}

	@Test
	@DisplayName("a procedure's stub sets OUT values through the parameters' mapping: an enum, @PlsqlBoolean, a"
			+ " java.util.Date and a converter")
	void outs() {
		doNothing().when(mocks.mock(Types.class)).load(eq(BigDecimal.ONE), out(Uf.SP), out(true), out(CREATED),
				out(new Code("7")));

		PlsqlOut<Uf> uf = new PlsqlOut<>();
		PlsqlOut<Boolean> active = new PlsqlOut<>();
		PlsqlOut<Date> created = new PlsqlOut<>();
		PlsqlOut<Code> code = new PlsqlOut<>();
		types.load(BigDecimal.ONE, uf, active, created, code);
		assertThat(uf.get()).isEqualTo(Uf.SP);
		assertThat(active.get()).isTrue();
		assertThat(created.get()).isEqualTo(CREATED);
		assertThat(code.get()).isEqualTo(new Code("7"));
	}

	@Test
	@DisplayName("PL/SQL calling the routines gets the values as the application maps them")
	void called_from_plsql() {
		Types mock = mocks.mock(Types.class);
		when(mock.ufOf(BigDecimal.ONE)).thenReturn(Uf.CE);
		when(mock.activeYn(BigDecimal.ONE)).thenReturn(true);

		assertThat(types.describe(BigDecimal.ONE)).isEqualTo("CE/Y");
	}
}
