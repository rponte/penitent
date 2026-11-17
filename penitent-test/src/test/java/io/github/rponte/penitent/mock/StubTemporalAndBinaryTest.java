package io.github.rponte.penitent.mock;

import static io.github.rponte.penitent.mock.PlsqlMocks.doNothing;
import static io.github.rponte.penitent.mock.PlsqlMocks.eq;
import static io.github.rponte.penitent.mock.PlsqlMocks.out;
import static io.github.rponte.penitent.mock.PlsqlMocks.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Clob;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import javax.sql.DataSource;
import javax.sql.rowset.serial.SerialClob;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlOut;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;

/**
 * A stub returns, and sets as an {@code OUT}, the types PL/SQL writes with functions or constructors rather than plain
 * literals: time zones, intervals, binaries, and a {@code CLOB} above the 32 KB of a PL/SQL literal. The real routines
 * fail.
 */
class StubTemporalAndBinaryTest {

	@PlsqlPackage("FREIGHT_EXTRAS")
	interface Extras {
		OffsetDateTime departsAt(BigDecimal pId);

		ZonedDateTime arrivesAt(BigDecimal pId);

		Instant loggedAt(BigDecimal pId);

		Duration transitOf(BigDecimal pId);

		Period warrantyOf(BigDecimal pId);

		byte[] digestOf(BigDecimal pId);

		byte[] labelOf(BigDecimal pId);

		String manifestOf(BigDecimal pId);

		void track(BigDecimal pId, PlsqlOut<OffsetDateTime> pDeparts, PlsqlOut<Duration> pTransit,
				PlsqlOut<byte[]> pDigest, PlsqlOut<String> pManifest);

		BigDecimal manifestLength(BigDecimal pId);
	}

	/** MANIFEST_OF through a java.sql.Clob, the other Java type the core maps a CLOB to. */
	@PlsqlPackage("FREIGHT_EXTRAS")
	interface ClobExtras {
		Clob manifestOf(BigDecimal pId);
	}

	private static final OffsetDateTime DEPARTS = OffsetDateTime.of(2026, 11, 17, 10, 30, 0, 123_456_000,
			ZoneOffset.ofHours(-3));

	/** 40,000 characters, beyond the 32,767 bytes of a PL/SQL literal, with a quote and accents among them. */
	private static final String MANIFEST = ("caixa d'água, " + "é".repeat(6)).repeat(2000);

	private static DataSource dataSource;

	private PlsqlMocks mocks;
	private Extras extras;

	@BeforeAll
	static void startDatabase() {
		dataSource = PlsqlMocks.wrap(TestDatabase.pool());
	}

	@BeforeEach
	void begin() {
		mocks = PlsqlMocks.on(dataSource);
		mocks.begin();
		extras = PlsqlPackages.create(Extras.class, dataSource);
	}

	@AfterEach
	void end() {
		mocks.end();
	}

	@Test
	@DisplayName("a stub returns a TIMESTAMP WITH TIME ZONE with an offset and with a region, and a WITH LOCAL TIME"
			+ " ZONE from an Instant")
	void time_zones() {
		Extras mock = mocks.mock(Extras.class);
		ZonedDateTime arrives = ZonedDateTime.of(2026, 11, 18, 8, 0, 0, 0, ZoneId.of("America/Sao_Paulo"));
		Instant logged = Instant.parse("2026-11-17T13:30:15.250Z");
		when(mock.departsAt(BigDecimal.ONE)).thenReturn(DEPARTS);
		when(mock.arrivesAt(BigDecimal.ONE)).thenReturn(arrives);
		when(mock.loggedAt(BigDecimal.ONE)).thenReturn(logged);

		assertThat(extras.departsAt(BigDecimal.ONE)).isEqualTo(DEPARTS);
		assertThat(extras.arrivesAt(BigDecimal.ONE)).isEqualTo(arrives);
		assertThat(extras.loggedAt(BigDecimal.ONE)).isEqualTo(logged);
	}

	@Test
	@DisplayName("a stub returns intervals, negative and with a fraction")
	void intervals() {
		Extras mock = mocks.mock(Extras.class);
		Duration transit = Duration.ofDays(-1).minusHours(2).minusMillis(5);
		when(mock.transitOf(BigDecimal.ONE)).thenReturn(transit);
		when(mock.warrantyOf(BigDecimal.ONE)).thenReturn(Period.of(-2, -3, 0));

		assertThat(extras.transitOf(BigDecimal.ONE)).isEqualTo(transit);
		assertThat(extras.warrantyOf(BigDecimal.ONE)).isEqualTo(Period.of(-2, -3, 0));
	}

	@Test
	@DisplayName("a stub returns a RAW and a BLOB from bytes")
	void binaries() {
		Extras mock = mocks.mock(Extras.class);
		when(mock.digestOf(BigDecimal.ONE)).thenReturn(new byte[] { 1, 2, (byte) 255 });
		when(mock.labelOf(BigDecimal.ONE)).thenReturn(new byte[] { 9, 8, 0 });

		assertThat(extras.digestOf(BigDecimal.ONE)).containsExactly(1, 2, 255);
		assertThat(extras.labelOf(BigDecimal.ONE)).containsExactly(9, 8, 0);
	}

	@Test
	@DisplayName("bytes above 16 KB are refused before compiling, naming the limit")
	void large_binary_refused() {
		Extras mock = mocks.mock(Extras.class);

		assertThatThrownBy(() -> when(mock.labelOf(BigDecimal.ONE)).thenReturn(new byte[20_000]))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("20000 bytes")
				.hasMessageContaining("16383");
	}

	@Test
	@DisplayName("a stub returns a CLOB above 32 KB, from a String and from a java.sql.Clob, which PL/SQL reads whole")
	void large_clob() throws Exception {
		Extras mock = mocks.mock(Extras.class);
		when(mock.manifestOf(BigDecimal.ONE)).thenReturn(MANIFEST);

		assertThat(extras.manifestOf(BigDecimal.ONE)).isEqualTo(MANIFEST);
		assertThat(extras.manifestLength(BigDecimal.ONE)).isEqualByComparingTo(String.valueOf(MANIFEST.length()));

		mocks.end();
		mocks.begin();
		when(mocks.mock(ClobExtras.class).manifestOf(BigDecimal.TEN)).thenReturn(new SerialClob(MANIFEST.toCharArray()));
		Clob read = PlsqlPackages.create(ClobExtras.class, dataSource).manifestOf(BigDecimal.TEN);
		assertThat(read.getSubString(1, (int) read.length())).isEqualTo(MANIFEST);
	}

	@Test
	@DisplayName("a procedure's stub sets OUT values of a time zone, an interval, a RAW and a large CLOB")
	void outs() {
		doNothing().when(mocks.mock(Extras.class)).track(eq(BigDecimal.ONE), out(DEPARTS), out(Duration.ofSeconds(90)),
				out(new byte[] { 7 }), out(MANIFEST));

		PlsqlOut<OffsetDateTime> departs = new PlsqlOut<>();
		PlsqlOut<Duration> transit = new PlsqlOut<>();
		PlsqlOut<byte[]> digest = new PlsqlOut<>();
		PlsqlOut<String> manifest = new PlsqlOut<>();
		extras.track(BigDecimal.ONE, departs, transit, digest, manifest);
		assertThat(departs.get()).isEqualTo(DEPARTS);
		assertThat(transit.get()).isEqualTo(Duration.ofSeconds(90));
		assertThat(digest.get()).containsExactly(7);
		assertThat(manifest.get()).isEqualTo(MANIFEST);
	}
}
