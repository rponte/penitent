package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlAttr;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlRoutine;
import io.github.rponte.penitent.exception.PlsqlMappingException;

class CursorTest extends OracleIntegrationTest {

	/** {@code WORK_COLUMN} has no property, and that is allowed in a cursor. */
	record Item(Long id, @PlsqlAttr({ "DESCRIPTION", "OTHER_NAME" }) String text, BigDecimal quantity,
			LocalDate registeredAt) {
	}

	public static class ItemBean {
		private Long id;

		public Long getId() {
			return id;
		}

		public void setId(Long id) {
			this.id = id;
		}
	}

	record NoColumnInCommon(String unrelated) {
	}

	@PlsqlPackage("FIXTURE_COMPOSITES")
	interface Composites {
		List<Item> items(int count);

		@PlsqlRoutine("ITEMS")
		List<ItemBean> itemsAsBean(int count);

		@PlsqlRoutine("ITEMS")
		List<NoColumnInCommon> noColumnInCommon(int count);

		@PlsqlRoutine("ITEMS")
		Item notAList(int count);

		@PlsqlRoutine("ITEMS")
		List<String> moreThanOneColumn(int count);
	}

	record IdAsFlag(Boolean id) {
	}

	@PlsqlPackage("FIXTURE_COMPOSITES")
	interface NumericColumn {
		@PlsqlRoutine("ITEMS")
		List<IdAsFlag> items(int count);
	}

	record Address(String street, Integer num) {
	}

	record WithAddress(Integer id, Address address) {
	}

	public static class AddressBean {
		private String street;
		private Integer num;

		public String getStreet() {
			return street;
		}

		public void setStreet(String street) {
			this.street = street;
		}

		public Integer getNum() {
			return num;
		}

		public void setNum(Integer num) {
			this.num = num;
		}
	}

	record WithAddressBean(Integer id, AddressBean address) {
	}

	record WithNicknames(Integer id, List<String> nicknames) {
	}

	@PlsqlPackage("FIXTURE_NESTED")
	interface Nested {
		List<WithAddress> cursorWithNullObject();

		List<WithNicknames> cursorWithCollection();

		List<BigDecimal> numbersCursor();

		@PlsqlRoutine("NUMBERS_CURSOR")
		List<Double> numbersAsDouble();
	}

	@PlsqlPackage("FIXTURE_SHAPES")
	interface Shapes {
		List<Item> openStrongCursor();

		List<WithAddress> cursorWithObject();

		@PlsqlRoutine("CURSOR_WITH_OBJECT")
		List<WithAddressBean> cursorWithObjectAsBean();

		List<String> scalarCursor();

		@PlsqlRoutine("SCALAR_CURSOR")
		Set<String> cursorAsSet();

		@PlsqlRoutine("SCALAR_CURSOR")
		Collection<String> cursorAsCollection();

		void cursorAsOut(Object cursor);
	}

	private final Composites composites = pkg(Composites.class);
	private final Shapes shapes = pkg(Shapes.class);

	@Test
	@DisplayName("cursor becomes List<T>, matched by column name, in cursor order")
	void cursor_to_record() {
		assertThat(composites.items(2)).containsExactly(
				new Item(1L, "item-1", new BigDecimal("1.5"), LocalDate.parse("2018-01-15")),
				new Item(2L, "item-2", new BigDecimal("3"), LocalDate.parse("2018-01-15")));
	}

	@Test
	@DisplayName("cursor to JavaBean")
	void cursor_to_bean() {
		assertThat(composites.itemsAsBean(2)).extracting(ItemBean::getId).containsExactly(1L, 2L);
	}

	@Test
	@DisplayName("cursor with no rows becomes an empty list")
	void empty_cursor() {
		assertThat(composites.items(0)).isEmpty();
	}

	@Test
	@DisplayName("no matching column is an error, so as not to return empty objects")
	void no_column_matches() {
		assertThatThrownBy(() -> composites.noColumnInCommon(1))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("matches no cursor column");
	}

	@Test
	@DisplayName("cursor mapped to something that is not a List says what to declare")
	void return_that_is_not_a_list() {
		assertThatThrownBy(() -> composites.notAList(1))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("declare List<T>");
	}

	@Test
	@DisplayName("strongly typed REF CURSOR")
	void strong_cursor() {
		assertThat(shapes.openStrongCursor())
				.containsExactly(new Item(1L, "strong", new BigDecimal("2.5"), LocalDate.parse("2018-01-15")));
	}

	@Test
	@DisplayName("cursor mapped to Set or Collection says what to declare")
	void set_and_collection() {
		assertThatThrownBy(() -> shapes.cursorAsSet())
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("declare List<T>");
		assertThatThrownBy(() -> shapes.cursorAsCollection())
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("declare List<T>");
	}

	@Test
	@DisplayName("cursor in an OUT parameter needs PlsqlOut")
	void cursor_in_out() {
		assertThatThrownBy(() -> shapes.cursorAsOut(null))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("declare PlsqlOut<T>");
	}

	@Test
	@DisplayName("cursor with an OBJECT column, in a record or JavaBean property")
	void column_object() {
		assertThat(shapes.cursorWithObject().get(0).address()).isEqualTo(new Address("Street A", 1));
		assertThat(shapes.cursorWithObjectAsBean().get(0).address().getStreet()).isEqualTo("Street A");
	}

	@Test
	@DisplayName("NULL OBJECT column becomes null")
	void column_null_object() {
		assertThat(pkg(Nested.class).cursorWithNullObject()).containsExactly(new WithAddress(2, null));
	}

	@Test
	@DisplayName("collection column in a List property says what to do instead")
	void column_collection() {
		assertThatThrownBy(() -> pkg(Nested.class).cursorWithCollection())
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("cursor column NICKNAMES is a collection")
				.hasMessageContaining("TABLE()");
	}

	@Test
	@DisplayName("cursor of a scalar is read from its only column")
	void scalar_cursor() {
		Nested nested = pkg(Nested.class);

		assertThat(shapes.scalarCursor()).containsExactly("a");
		assertThat(nested.numbersCursor()).containsExactly(new BigDecimal("1.5"), new BigDecimal("3"));
		assertThat(nested.numbersAsDouble()).containsExactly(1.5, 3.0);
	}

	@Test
	@DisplayName("cursor of a scalar with more than one column names the columns")
	void scalar_cursor_with_more_than_one_column() {
		assertThatThrownBy(() -> composites.moreThanOneColumn(1))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("a cursor of String is read from its only column")
				.hasMessageContaining("[ID, DESCRIPTION, QUANTITY, REGISTERED_AT, WORK_COLUMN]");
	}

	@Test
	@DisplayName("numeric column as Boolean: 1 is true, and any other number is an error")
	void number_column_as_boolean() {
		NumericColumn column = pkg(NumericColumn.class);

		assertThat(column.items(1)).containsExactly(new IdAsFlag(true));
		assertThatThrownBy(() -> column.items(2))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("column ID")
				.hasMessageContaining("'2'");
	}
}
