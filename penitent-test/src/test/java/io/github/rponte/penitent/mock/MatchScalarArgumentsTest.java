package io.github.rponte.penitent.mock;

import static io.github.rponte.penitent.mock.PlsqlMocks.verify;
import static io.github.rponte.penitent.mock.PlsqlMocks.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.UUID;
import java.util.function.BiFunction;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlBoolean;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.convert.PlsqlConvert;
import io.github.rponte.penitent.exception.PlsqlRoutineRaisedException;

/**
 * A stub matches an argument by value, and {@code verify} counts the calls by value, for each scalar type the core
 * maps, through the parameter's mapping: a large text and LOBs, binaries, time zones, intervals, a BINARY_DOUBLE, an
 * enum, a UUID, a boolean by its literals, a java.util.Date and a converter. Each test stubs one value, calls the
 * routine with it, checks that another value has no stub, and verifies both counts.
 */
class MatchScalarArgumentsTest {

	enum Uf {
		SP, CE
	}

	@PlsqlPackage("FREIGHT_ARGS")
	interface Args {
		BigDecimal takesText(String pValue);

		BigDecimal takesClob(String pValue);

		BigDecimal takesBlob(byte[] pValue);

		BigDecimal takesRaw(byte[] pValue);

		BigDecimal takesTstz(OffsetDateTime pValue);

		BigDecimal takesTsltz(Instant pValue);

		BigDecimal takesDays(Duration pValue);

		BigDecimal takesMonths(Period pValue);

		BigDecimal takesDouble(Double pValue);

		BigDecimal takesUf(Uf pValue);

		BigDecimal takesToken(UUID pValue);

		BigDecimal takesFlag(@PlsqlBoolean(trueValue = "Y", falseValue = "N") Boolean pValue);

		BigDecimal takesDate(Date pValue);

		BigDecimal takesCode(@PlsqlConvert(StubScalarMappingTest.CodeConverter.class) StubScalarMappingTest.Code pValue);
	}

	private static DataSource dataSource;

	private PlsqlMocks mocks;
	private Args args;

	@BeforeAll
	static void startDatabase() {
		dataSource = PlsqlMocks.wrap(TestDatabase.pool());
	}

	@BeforeEach
	void begin() {
		mocks = PlsqlMocks.on(dataSource);
		mocks.begin();
		args = PlsqlPackages.create(Args.class, dataSource);
	}

	@AfterEach
	void end() {
		mocks.end();
	}

	/** Stubs {@code value}, calls with it, finds no stub for {@code other}, and verifies both by value. */
	private <T> void matches(BiFunction<Args, T, BigDecimal> routine, T value, T other) {
		Args mock = mocks.mock(Args.class);
		when(routine.apply(mock, value)).thenReturn(BigDecimal.ONE);

		assertThat(routine.apply(args, value)).isEqualByComparingTo("1");
		assertThatThrownBy(() -> routine.apply(args, other))
				.isInstanceOfSatisfying(PlsqlRoutineRaisedException.class, e -> assertThat(e.getCode()).isEqualTo(20999));
		routine.apply(verify(mock), value);
		routine.apply(verify(mock), other);
		mocks.end();
		mocks.begin();
	}

	@Test
	@DisplayName("a VARCHAR2 of 5,000 characters, above the 4,000 bytes a call was recorded in")
	void long_text() {
		matches(Args::takesText, "x".repeat(5_000), "y".repeat(5_000));
	}

	@Test
	@DisplayName("a CLOB of 40,000 characters, with quotes and accents")
	void large_clob() {
		String text = ("caixa d'água, " + "é".repeat(6)).repeat(2_000);
		matches(Args::takesClob, text, text + "!");
	}

	@Test
	@DisplayName("a BLOB and a RAW, by their bytes")
	void binaries() {
		matches(Args::takesBlob, new byte[] { 9, 8, 0 }, new byte[] { 9, 8 });
		matches(Args::takesRaw, new byte[] { 1, 2, (byte) 255 }, new byte[] { 1, 2 });
	}

	@Test
	@DisplayName("a TIMESTAMP WITH TIME ZONE, as an instant, and a WITH LOCAL TIME ZONE from an Instant")
	void time_zones() {
		OffsetDateTime departs = OffsetDateTime.of(2026, 11, 17, 10, 30, 0, 123_456_000, ZoneOffset.ofHours(-3));
		matches(Args::takesTstz, departs, departs.plusSeconds(1));
		matches(Args::takesTsltz, Instant.parse("2026-11-17T13:30:15.250Z"), Instant.parse("2026-11-17T13:30:16Z"));
	}

	@Test
	@DisplayName("intervals, negative and with a fraction")
	void intervals() {
		matches(Args::takesDays, Duration.ofDays(-1).minusMillis(5), Duration.ofDays(-1));
		matches(Args::takesMonths, Period.of(-2, -3, 0), Period.of(2, 3, 0));
	}

	@Test
	@DisplayName("a BINARY_DOUBLE")
	void binary_double() {
		matches(Args::takesDouble, 1.5d, 2.5d);
	}

	@Test
	@DisplayName("through the parameter's mapping: an enum, a UUID, a boolean by its literals, a java.util.Date and a"
			+ " converter")
	void through_the_mapping() {
		matches(Args::takesUf, Uf.CE, Uf.SP);
		matches(Args::takesToken, UUID.fromString("123e4567-e89b-12d3-a456-426614174000"), UUID.randomUUID());
		matches(Args::takesFlag, true, false);
		matches(Args::takesDate, Date.from(Instant.parse("2026-11-17T13:30:15Z")), new Date(0));
		matches(Args::takesCode, new StubScalarMappingTest.Code("7"), new StubScalarMappingTest.Code("8"));
	}
}
