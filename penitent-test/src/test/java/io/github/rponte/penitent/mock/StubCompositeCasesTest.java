package io.github.rponte.penitent.mock;

import static io.github.rponte.penitent.mock.PlsqlMocks.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;

/**
 * A stub returns object types and collections in the cases the first tests left out: attributes and elements of time
 * zones, binaries and LOBs, collections declared in a package, and a collection of collections. The real routines
 * fail, so a test passes only through the stub.
 */
class StubCompositeCasesTest {

	/** FREIGHT_STAMP_T. */
	record Stamp(Long id, OffsetDateTime departsAt, Instant loggedAt) {
	}

	/** FREIGHT_PAYLOAD_T: bytes are compared by content, below, since a record compares arrays by identity. */
	record Payload(Long id, byte[] digest, String doc, byte[] image) {
	}

	/** FREIGHT_ITEM_T. */
	record Item(Long id, String uf) {
	}

	/** FREIGHT_FLAG_T, on Oracle 23ai only. */
	record Flag(Long id, Boolean active) {
	}

	@PlsqlPackage("FREIGHT_COMPOSITES")
	interface Composites {
		Stamp stampOf(BigDecimal pId);

		Payload payloadOf(BigDecimal pId);

		List<OffsetDateTime> stampsOf(BigDecimal pId);

		List<byte[]> digestsOf(BigDecimal pId);

		List<List<String>> matrixOf(BigDecimal pId);

		List<String> ufsOf(BigDecimal pId);

		List<BigDecimal> codesOf(BigDecimal pId);

		List<Item> itemsOf(BigDecimal pId);
	}

	@PlsqlPackage("FREIGHT_FLAGS")
	interface Flags {
		Flag flagOf(BigDecimal pId);
	}

	private static final OffsetDateTime AT = OffsetDateTime.of(2026, 11, 18, 10, 30, 0, 123_456_000,
			ZoneOffset.ofHours(-3));

	private static DataSource dataSource;

	private PlsqlMocks mocks;
	private Composites composites;

	@BeforeAll
	static void startDatabase() {
		dataSource = PlsqlMocks.wrap(TestDatabase.pool());
	}

	@BeforeEach
	void begin() {
		mocks = PlsqlMocks.on(dataSource);
		mocks.begin();
		composites = PlsqlPackages.create(Composites.class, dataSource);
	}

	@AfterEach
	void end() {
		mocks.end();
	}

	@Test
	@DisplayName("an object type with TIMESTAMP WITH TIME ZONE and WITH LOCAL TIME ZONE attributes")
	void time_zone_attributes() {
		Stamp stamp = new Stamp(1L, AT, Instant.parse("2026-11-18T13:30:15Z"));
		when(mocks.mock(Composites.class).stampOf(BigDecimal.ONE)).thenReturn(stamp);

		assertThat(composites.stampOf(BigDecimal.ONE)).isEqualTo(stamp);
	}

	@Test
	@DisplayName("an object type with RAW, CLOB and BLOB attributes, a CLOB above 32 KB among them")
	void binary_and_lob_attributes() {
		String doc = ("caixa d'água, " + "é".repeat(6)).repeat(2_000);
		when(mocks.mock(Composites.class).payloadOf(BigDecimal.ONE))
				.thenReturn(new Payload(1L, new byte[] { 1, 2, (byte) 255 }, doc, new byte[] { 9, 8, 0 }));

		Payload read = composites.payloadOf(BigDecimal.ONE);
		assertThat(read.id()).isEqualTo(1L);
		assertThat(read.digest()).containsExactly(1, 2, 255);
		assertThat(read.doc()).isEqualTo(doc);
		assertThat(read.image()).containsExactly(9, 8, 0);
	}

	@Test
	@DisplayName("collections of TIMESTAMP WITH TIME ZONE and of RAW elements")
	void time_zone_and_binary_elements() {
		Composites mock = mocks.mock(Composites.class);
		when(mock.stampsOf(BigDecimal.ONE)).thenReturn(List.of(AT, AT.plusDays(1)));
		when(mock.digestsOf(BigDecimal.ONE)).thenReturn(List.of(new byte[] { 1 }, new byte[] { 2, 3 }));

		assertThat(composites.stampsOf(BigDecimal.ONE)).containsExactly(AT, AT.plusDays(1));
		assertThat(composites.digestsOf(BigDecimal.ONE)).satisfiesExactly(
				first -> assertThat(first).containsExactly(1), second -> assertThat(second).containsExactly(2, 3));
	}

	@Test
	@DisplayName("collections declared in a package: a nested table of text, a VARRAY of numbers and a table of objects")
	void package_collections() {
		Composites mock = mocks.mock(Composites.class);
		when(mock.ufsOf(BigDecimal.ONE)).thenReturn(List.of("SP", "CE"));
		when(mock.codesOf(BigDecimal.ONE)).thenReturn(List.of(BigDecimal.ONE, BigDecimal.TEN));
		when(mock.itemsOf(BigDecimal.ONE)).thenReturn(List.of(new Item(1L, "SP"), new Item(2L, "CE")));

		assertThat(composites.ufsOf(BigDecimal.ONE)).containsExactly("SP", "CE");
		assertThat(composites.codesOf(BigDecimal.ONE)).containsExactly(BigDecimal.ONE, BigDecimal.TEN);
		assertThat(composites.itemsOf(BigDecimal.ONE)).containsExactly(new Item(1L, "SP"), new Item(2L, "CE"));
	}

	@Test
	@DisplayName("a collection of collections, an inner one empty")
	void collection_of_collections() {
		List<List<String>> matrix = List.of(List.of("a", "b"), List.of(), List.of("c"));
		when(mocks.mock(Composites.class).matrixOf(BigDecimal.ONE)).thenReturn(matrix);

		assertThat(composites.matrixOf(BigDecimal.ONE)).isEqualTo(matrix);
	}

	@Test
	@DisplayName("an object type with a BOOLEAN attribute, on Oracle 23ai, where SQL types take one")
	void boolean_attribute() throws Exception {
		try (Connection connection = TestDatabase.pool().getConnection()) {
			assumeTrue(connection.getMetaData().getDatabaseMajorVersion() >= 23, "a BOOLEAN attribute needs 23ai");
		}
		Flags mock = mocks.mock(Flags.class);
		when(mock.flagOf(BigDecimal.ONE)).thenReturn(new Flag(1L, true));
		when(mock.flagOf(BigDecimal.TEN)).thenReturn(new Flag(10L, null));

		Flags flags = PlsqlPackages.create(Flags.class, dataSource);
		assertThat(flags.flagOf(BigDecimal.ONE)).isEqualTo(new Flag(1L, true));
		assertThat(flags.flagOf(BigDecimal.TEN)).isEqualTo(new Flag(10L, null));
	}
}
