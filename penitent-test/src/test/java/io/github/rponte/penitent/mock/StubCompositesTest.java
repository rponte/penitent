package io.github.rponte.penitent.mock;

import static io.github.rponte.penitent.mock.PlsqlMocks.eq;
import static io.github.rponte.penitent.mock.PlsqlMocks.out;
import static io.github.rponte.penitent.mock.PlsqlMocks.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlOut;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.exception.PlsqlMappingException;

/**
 * A stub answers with an object type or a collection: the value is written as a PL/SQL constructor, from the same
 * mapping the core binds the Java type with, so Java and PL/SQL calling the routine get it.
 */
class StubCompositesTest {

	@PlsqlPackage("FREIGHTS")
	interface Freights {
		FreightItem itemOf(BigDecimal pId);

		List<FreightItem> itemsOf(String pUf);

		Shipment shipmentOf(String pCode);

		List<String> tagsOf(String pUf);

		void loadItem(BigDecimal pId, PlsqlOut<FreightItem> pItem);

		BigDecimal countItemsOf(String pUf);

		FreightItem find(BigDecimal pId);

		List<BigDecimal> idsOf(String pUf);

		FreightEvent eventOf(BigDecimal pId);
	}

	/** FREIGHT_EVENT_T, with the DATE read with its time. */
	@PlsqlPackage("FREIGHTS")
	interface Events {
		FreightEvent eventOf(BigDecimal pId);
	}

	/** FREIGHT_EVENT_T, with the DATE read as a day. */
	@PlsqlPackage("FREIGHTS")
	interface Days {
		FreightDay eventOf(BigDecimal pId);
	}

	record FreightEvent(Long id, LocalDateTime happened, LocalDateTime stampedAt) {
	}

	record FreightDay(Long id, LocalDate happened, LocalDateTime stampedAt) {
	}

	/** FREIGHT_ITEM_T, through a Java type that does not have its attribute UF. */
	@PlsqlPackage("FREIGHTS")
	interface MisfitFreights {
		Misfit itemOf(BigDecimal pId);
	}

	record Misfit(Long id, String state) {
	}

	/** FREIGHT_ITEM_T. */
	record FreightItem(Long id, String uf) {
	}

	/** FREIGHT_SHIPMENT_T: an object type, a collection of them, a collection of text and a VARRAY of numbers. */
	record Shipment(String code, FreightItem item, List<FreightItem> items, List<String> tags, List<BigDecimal> codes) {
	}

	private static DataSource dataSource;

	private PlsqlMocks mocks;
	private Freights freights;

	@BeforeAll
	static void startDatabase() {
		dataSource = PlsqlMocks.wrap(TestDatabase.pool());
	}

	@BeforeEach
	void begin() {
		mocks = PlsqlMocks.on(dataSource);
		mocks.begin();
		freights = PlsqlPackages.create(Freights.class, dataSource);
	}

	@AfterEach
	void end() {
		mocks.end();
	}

	@Test
	@DisplayName("a stub returns an object type")
	void object() {
		Freights mock = mocks.mock(Freights.class);

		when(mock.itemOf(new BigDecimal("1"))).thenReturn(new FreightItem(1L, "SP"));

		assertThat(freights.itemOf(new BigDecimal("1"))).isEqualTo(new FreightItem(1L, "SP"));
	}

	@Test
	@DisplayName("a stub returns a collection of object types, which PL/SQL calling the routine gets too")
	void collection_of_objects() {
		Freights mock = mocks.mock(Freights.class);

		when(mock.itemsOf("SP")).thenReturn(List.of(new FreightItem(1L, "SP"), new FreightItem(2L, "CE")));

		assertThat(freights.itemsOf("SP")).containsExactly(new FreightItem(1L, "SP"), new FreightItem(2L, "CE"));
		assertThat(freights.countItemsOf("SP")).isEqualByComparingTo("2");
	}

	@Test
	@DisplayName("a stub returns an empty collection")
	void empty_collection() {
		Freights mock = mocks.mock(Freights.class);

		when(mock.itemsOf("SP")).thenReturn(List.of());

		assertThat(freights.itemsOf("SP")).isEmpty();
		assertThat(freights.countItemsOf("SP")).isEqualByComparingTo("0");
	}

	@Test
	@DisplayName("a stub returns a collection of text, quotes included")
	void collection_of_scalars() {
		Freights mock = mocks.mock(Freights.class);

		when(mock.tagsOf("SP")).thenReturn(List.of("fragile", "d'agua"));

		assertThat(freights.tagsOf("SP")).containsExactly("fragile", "d'agua");
	}

	@Test
	@DisplayName("a stub returns an object type holding an object type, collections and a VARRAY")
	void nested() {
		Freights mock = mocks.mock(Freights.class);
		Shipment shipment = new Shipment("S1", new FreightItem(1L, "SP"),
				List.of(new FreightItem(2L, "CE"), new FreightItem(3L, "RJ")), List.of("fragile"),
				List.of(new BigDecimal("10"), new BigDecimal("20")));

		when(mock.shipmentOf("S1")).thenReturn(shipment);

		Shipment returned = freights.shipmentOf("S1");
		assertThat(returned.code()).isEqualTo("S1");
		assertThat(returned.item()).isEqualTo(new FreightItem(1L, "SP"));
		assertThat(returned.items()).containsExactly(new FreightItem(2L, "CE"), new FreightItem(3L, "RJ"));
		assertThat(returned.tags()).containsExactly("fragile");
		assertThat(returned.codes()).usingElementComparator(BigDecimal::compareTo)
				.containsExactly(new BigDecimal("10"), new BigDecimal("20"));
	}

	@Test
	@DisplayName("null attributes and null elements come back as null")
	void nulls() {
		Freights mock = mocks.mock(Freights.class);

		when(mock.shipmentOf("S2")).thenReturn(new Shipment("S2", null, null, null, null));
		when(mock.itemsOf("SP")).thenReturn(Arrays.asList(new FreightItem(1L, null), null));

		assertThat(freights.shipmentOf("S2")).isEqualTo(new Shipment("S2", null, null, null, null));
		assertThat(freights.itemsOf("SP")).containsExactly(new FreightItem(1L, null), null);
	}

	@Test
	@DisplayName("a stub returns an object type with a DATE, with its time, and a TIMESTAMP, with its fraction")
	void dates() {
		Events mock = mocks.mock(Events.class);
		FreightEvent event = new FreightEvent(1L, LocalDateTime.of(2017, 4, 5, 10, 20, 30),
				LocalDateTime.of(2017, 4, 5, 10, 20, 30, 123_456_000));

		when(mock.eventOf(BigDecimal.ONE)).thenReturn(event);

		assertThat(PlsqlPackages.create(Events.class, dataSource).eventOf(BigDecimal.ONE)).isEqualTo(event);
	}

	@Test
	@DisplayName("a stub returns an object type with a DATE from a LocalDate")
	void day() {
		Days mock = mocks.mock(Days.class);
		FreightDay day = new FreightDay(1L, LocalDate.of(2017, 4, 5), LocalDateTime.of(2017, 4, 5, 0, 0));

		when(mock.eventOf(BigDecimal.ONE)).thenReturn(day);

		assertThat(PlsqlPackages.create(Days.class, dataSource).eventOf(BigDecimal.ONE)).isEqualTo(day);
	}

	@Test
	@DisplayName("a RECORD or %ROWTYPE returned is refused before compiling: PL/SQL has no constructor for it")
	void record_refused() {
		Freights mock = mocks.mock(Freights.class);

		assertThatThrownBy(() -> when(mock.find(BigDecimal.ONE)).thenReturn(new FreightItem(1L, "SP")))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("PLSQL_TEST.FREIGHTS.FIND")
				.hasMessageContaining("RECORD or a %ROWTYPE");
	}

	@Test
	@DisplayName("an INDEX BY returned is refused before compiling: it has no constructor")
	void index_by_refused() {
		Freights mock = mocks.mock(Freights.class);

		assertThatThrownBy(() -> when(mock.idsOf("SP")).thenReturn(List.of(BigDecimal.ONE)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("PLSQL_TEST.FREIGHTS.IDS_OF")
				.hasMessageContaining("INDEX BY");
	}

	@Test
	@DisplayName("a Java type that does not fit the object type is refused before compiling, naming the attribute")
	void misfit_refused() {
		MisfitFreights mock = mocks.mock(MisfitFreights.class);

		assertThatThrownBy(() -> when(mock.itemOf(BigDecimal.ONE)).thenReturn(new Misfit(1L, "SP")))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("does not cover attribute(s) of FREIGHT_ITEM_T: [UF VARCHAR2]");
	}

	@Test
	@DisplayName("a stub of a procedure sets an OUT of an object type")
	void out_object() {
		Freights mock = mocks.mock(Freights.class);

		when(() -> mock.loadItem(eq(new BigDecimal("5")), out(new FreightItem(5L, "CE"))));

		PlsqlOut<FreightItem> item = new PlsqlOut<>();
		freights.loadItem(new BigDecimal("5"), item);
		assertThat(item.get()).isEqualTo(new FreightItem(5L, "CE"));
	}
}
