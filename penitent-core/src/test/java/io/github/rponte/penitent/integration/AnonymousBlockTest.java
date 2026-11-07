package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlBlock;
import io.github.rponte.penitent.PlsqlParam;
import io.github.rponte.penitent.PlsqlType;
import io.github.rponte.penitent.exception.PlsqlMappingException;

/**
 * An object type member method has no {@code package.routine} form: it can only be invoked on an
 * instance, inside a block. That is the case that justifies {@link PlsqlBlock}.
 */
class AnonymousBlockTest extends OracleIntegrationTest {

	private static final LocalDate JAN_01 = LocalDate.parse("2018-01-01");
	private static final LocalDate JAN_15 = LocalDate.parse("2018-01-15");
	private static final LocalDate JAN_31 = LocalDate.parse("2018-01-31");
	private static final LocalDate FEB_15 = LocalDate.parse("2018-02-15");

	record Period(LocalDate starts, LocalDate ends) {
	}

	interface Periods {
		@PlsqlBlock("""
				declare
				  p PERIOD_T;
				begin
				  p := new PERIOD_T(:starts, :ends);
				  :result := p.is_valid();
				end;
				""")
		boolean isValid(LocalDate starts, LocalDate ends);

		@PlsqlBlock("""
				declare
				  p PERIOD_T;
				  o PERIOD_T;
				begin
				  p := new PERIOD_T(:starts, :ends);
				  o := new PERIOD_T(:otherStart, :otherEnd);
				  :result := p.conflicts_with(o);
				end;
				""")
		boolean conflictsWith(LocalDate starts, LocalDate ends, LocalDate otherStart, LocalDate otherEnd);

		@PlsqlBlock(value = """
				declare
				  p PERIOD_T;
				  o PERIOD_T;
				begin
				  p := new PERIOD_T(:starts, :ends);
				  o := new PERIOD_T(:otherStart, :otherEnd);
				  :result := p.intersection_with(o);
				end;
				""", resultType = "PERIOD_T")
		Period intersection(LocalDate starts, LocalDate ends, LocalDate otherStart, LocalDate otherEnd);

		@PlsqlBlock("begin :result := :value + :value; end;")
		int twice(int value);

		@PlsqlBlock("begin :result := :otherName; end;")
		String withOwnName(@PlsqlParam("otherName") String value);

		@PlsqlBlock("begin null; end;")
		void withoutResult();
	}

	private final Periods periods = pkg(Periods.class);

	@Test
	@DisplayName("PL/SQL BOOLEAN comes back as boolean, not as 'S'/'N'")
	void real_boolean() {
		assertThat(periods.isValid(JAN_01, JAN_31)).isTrue();
		assertThat(periods.isValid(JAN_31, JAN_01)).isFalse();
	}

	@Test
	@DisplayName("a block can have more than one parameter, matched by name")
	void parameters_by_name() {
		assertThat(periods.conflictsWith(JAN_01, JAN_31, JAN_15, FEB_15)).isTrue();
		assertThat(periods.conflictsWith(JAN_01, JAN_15, JAN_31, FEB_15)).isFalse();
	}

	@Test
	@DisplayName(":result can be a composite, with the type declared in resultType")
	void composite_result() {
		assertThat(periods.intersection(JAN_01, JAN_31, JAN_15, FEB_15)).isEqualTo(new Period(JAN_15, JAN_31));
	}

	@Test
	@DisplayName("a repeated name is bound at every occurrence, and @PlsqlParam points to another name")
	void names() {
		assertThat(periods.twice(21)).isEqualTo(42);
		assertThat(periods.withOwnName("x")).isEqualTo("x");
	}

	@Test
	@DisplayName("block without output, in an interface that needs no @PlsqlPackage")
	void block_without_output() {
		periods.withoutResult();
	}

	// --- what the library demands from whoever writes the block -------------------

	interface BlockWithPlaceholderWithoutParameter {
		@PlsqlBlock("begin :result := :doesNotExist; end;")
		String anything(String starts);
	}

	interface BlockWithoutResultButWithReturn {
		@PlsqlBlock("begin null; end;")
		String anything();
	}

	interface BlockWithResultButVoid {
		@PlsqlBlock("begin :result := 1; end;")
		void anything();
	}

	interface BlockWithTwoResults {
		@PlsqlBlock("begin :result := 1; :result := 2; end;")
		int anything();
	}

	@Test
	@DisplayName("a block inconsistent with the signature fails saying what the problem is")
	void inconsistent_block() {
		assertThatThrownBy(() -> pkg(BlockWithPlaceholderWithoutParameter.class).anything("x"))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("doesNotExist")
				.hasMessageContaining("matches no parameter");

		assertThatThrownBy(() -> pkg(BlockWithoutResultButWithReturn.class).anything())
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("the block has no :result");

		assertThatThrownBy(() -> pkg(BlockWithResultButVoid.class).anything())
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("is void, but the block has :result");

		assertThatThrownBy(() -> pkg(BlockWithTwoResults.class).anything())
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining(":result more than once");
	}

	// --- parameters and results beyond scalars -------------------------------------

	record Address(String street, Integer num) {
	}

	@PlsqlType("ADDRESS_T")
	record TypedAddress(String street, Integer num) {
	}

	interface Shapes {
		@PlsqlBlock("begin :result := :value; end;")
		String echoText(String value);

		@PlsqlBlock("begin :result := :value.street; end;")
		String withComposite(@PlsqlType("ADDRESS_T") Address value);

		@PlsqlBlock("begin :result := :value.street; end;")
		String withTypedComposite(TypedAddress value);

		@PlsqlBlock("declare l NAME_LIST_T := :value; begin :result := l.count; end;")
		int withCollection(@PlsqlType("NAME_LIST_T") List<String> value);

		@PlsqlBlock("declare l ADDRESS_LIST_T := :value; begin :result := l(2).street || l(2).num; end;")
		String withCollectionOfObjects(@PlsqlType("address_list_t") List<Address> value);

		@PlsqlBlock("declare m MATRIX_T := :value; begin :result := m.count || '/' || m(1).count || '/' || m(1)(2); end;")
		String withMatrix(@PlsqlType("MATRIX_T") List<List<String>> value);

		@PlsqlBlock("begin :result := case when :value is null then 'null' else 'not null' end; end;")
		String compositeIsNull(@PlsqlType("ADDRESS_T") Address value);

		@PlsqlBlock("begin :result := case when :value is null then 'null' else 'not null' end; end;")
		String collectionIsNull(@PlsqlType("NAME_LIST_T") List<String> value);

		@PlsqlBlock("declare l NAME_LIST_T := :value; begin :result := l.count; end;")
		int withUndeclaredCollection(List<String> value);

		@PlsqlBlock("begin :result := :value.street; end;")
		String withUndeclaredComposite(Address value);

		@PlsqlBlock("declare l NAME_LIST_T := :value; begin :result := l.count; end;")
		int withMismatchedMatrix(@PlsqlType("MATRIX_T") List<String> value);

		@PlsqlBlock("begin :result := :value; end;")
		boolean withBoolean(boolean value);

		@PlsqlBlock("begin :result := not :value; end;")
		boolean negate(boolean value);

		@PlsqlBlock("begin :result := :value; end;")
		Boolean echoBooleanOrNull(Boolean value);

		@PlsqlBlock("declare l_value boolean := :value; begin :result := case when l_value then 'yes' when not l_value then 'no' else 'none' end; end;")
		String describe(Boolean value);

		@PlsqlBlock("begin :result := :value || :flag; end;")
		String textAndBooleanTogether(String value, String flag);

		@PlsqlBlock("begin :result := NAME_LIST_T('a'); end;")
		List<String> returnsList();

		@PlsqlBlock(value = "begin :result := NAME_LIST_T('a'); end;", resultType = "NAME_LIST_T")
		List<String> returnsTypedList();

		@PlsqlBlock("begin open :result for select 'a' street, 1 num from dual; end;")
		List<Address> returnsCursor();

		@PlsqlBlock("BEGIN OPEN :RESULT\n  FOR SELECT 'a' FROM DUAL; END;")
		List<String> returnsScalarCursor();

		@PlsqlBlock(value = """
				declare
				  c sys_refcursor;
				begin
				  open c for select 'b' street, 2 num from dual;
				  :result := c;
				end;
				""", resultType = "sys_refcursor")
		List<Address> returnsDeclaredCursor();

		@PlsqlBlock(value = "begin :result := ADDRESS_LIST_T(ADDRESS_T('Street A', 1), null); end;",
				resultType = "ADDRESS_LIST_T")
		List<Address> returnsAddresses();

		@PlsqlBlock(value = "begin :result := MATRIX_T(NAME_LIST_T('a', 'b'), NAME_LIST_T()); end;",
				resultType = "MATRIX_T")
		List<List<String>> returnsMatrix();

		@PlsqlBlock(value = "begin :result := null; end;", resultType = "NAME_LIST_T")
		List<String> returnsNullList();

		@PlsqlBlock("""
				begin
				  -- open :result for select 1 from dual;
				  :result := NAME_LIST_T('a');
				end;
				""")
		List<String> opensInComment();

		@PlsqlBlock("declare s varchar2(30) := 'open :result for'; begin :result := NAME_LIST_T(s); end;")
		List<String> opensInLiteral();

		@PlsqlBlock("begin open :result for select 'a' from dual; end;")
		String cursorAsText();

		@PlsqlBlock(value = "begin :result := NAME_LIST_T('a'); end;", resultType = "NAME_LIST_T")
		String collectionAsText();

		@PlsqlBlock(value = "begin :result := ADDRESS_T('a', 1); end;", resultType = "ADDRESS_T")
		List<Address> compositeAsList();

		@PlsqlBlock("begin :result := 'a:b'; end;")
		String colonInLiteral();

		@PlsqlBlock("begin :result := 'it''s :x'; end;")
		String withQuotesInLiteral();

		@PlsqlBlock("begin :result := to_char(:value, 'YYYY-MM-DD HH24:MI:SS'); end;")
		String formattedDate(LocalDate value);

		@PlsqlBlock("begin :result := ':value=' || :value; end;")
		String nameInLiteral(String value);

		@PlsqlBlock("""
				begin
				  :result := q'[a:b]' || ' ' || Q'{c:d}' || ' ' || nq'(e:f)' || ' ' || q'<g:h>'
				          || ' ' || q'!i'j:k!' || ' ' || NQ'#l:m#' || ' ' || :value;
				end;
				""")
		String alternativeQuotes(String value);

		@PlsqlBlock("""
				begin
				  -- a comment with :doesNotExist and with a stray quote: it's
				  /* another one with :neitherThis
				     on two lines, and 'quote */
				  :result := :value; -- and :oneMore at the end
				end;
				""")
		String withComments(String value);

		@PlsqlBlock("declare \"a:b\" number := 1; begin :result := \"a:b\"; end;")
		int identifierWithColon();

		@PlsqlBlock("""
				begin
				  -- a question mark? here
				  :result := 'a?b ' || :value; /* and here? */
				end;
				""")
		String withQuestionMark(String value);

		@PlsqlBlock("declare seq varchar2(9) := 'a:b'; begin :result := seq||' '||:value; end;")
		String identifierEndingInQ(String value);
	}

	private final Shapes shapes = pkg(Shapes.class);

	@Test
	@DisplayName("null as a parameter")
	void null_parameter() {
		assertThat(shapes.echoText(null)).isNull();
	}

	@Test
	@DisplayName("composite parameter, with the type in @PlsqlType on the parameter or on its class")
	void composite_parameter() {
		assertThat(shapes.withComposite(new Address("Street A", 1))).isEqualTo("Street A");
		assertThat(shapes.withTypedComposite(new TypedAddress("Street B", 2))).isEqualTo("Street B");
	}

	@Test
	@DisplayName("collection parameter, with the type in @PlsqlType")
	void collection_parameter() {
		assertThat(shapes.withCollection(List.of("a"))).isEqualTo(1);
	}

	@Test
	@DisplayName("collection of OBJECT and collection of collections as parameters")
	void collection_of_objects_parameter() {
		assertThat(shapes.withCollectionOfObjects(List.of(new Address("Street A", 1), new Address("Street B", 2))))
				.isEqualTo("Street B2");
		assertThat(shapes.withMatrix(List.of(List.of("a", "b"), List.of()))).isEqualTo("2/2/b");
	}

	@Test
	@DisplayName("null composite and null collection go as NULL of their type")
	void null_composite_and_collection_parameters() {
		assertThat(shapes.compositeIsNull(null)).isEqualTo("null");
		assertThat(shapes.compositeIsNull(new Address("Street A", 1))).isEqualTo("not null");
		assertThat(shapes.collectionIsNull(null)).isEqualTo("null");
		assertThat(shapes.collectionIsNull(List.of())).isEqualTo("not null");
	}

	@Test
	@DisplayName("a composite or collection parameter without @PlsqlType fails before the call, saying what to declare")
	void parameter_without_declared_type() {
		assertThatThrownBy(() -> shapes.withUndeclaredCollection(List.of("a")))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessage("withUndeclaredCollection: parameter value is a List; in a block, declare its Oracle type"
						+ " with @PlsqlType, as in @PlsqlType(\"NAME_LIST_T\")");
		assertThatThrownBy(() -> shapes.withUndeclaredComposite(new Address("Street A", 1)))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("parameter value is Address")
				.hasMessageContaining("@PlsqlType");
		assertThatThrownBy(() -> shapes.withMismatchedMatrix(List.of("a")))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("MATRIX_T holds collections");
	}

	@Test
	@DisplayName("boolean parameter")
	void boolean_parameter() {
		assertThat(shapes.withBoolean(true)).isTrue();
		assertThat(shapes.withBoolean(false)).isFalse();
		assertThat(shapes.negate(true)).isFalse();
		assertThat(shapes.negate(false)).isTrue();
	}

	@Test
	@DisplayName("Boolean parameter: all three states reach PL/SQL")
	void boolean_parameter_with_null() {
		assertThat(shapes.describe(true)).isEqualTo("yes");
		assertThat(shapes.describe(false)).isEqualTo("no");
		assertThat(shapes.describe(null)).isEqualTo("none");
		assertThat(shapes.echoBooleanOrNull(null)).isNull();
	}

	@Test
	@DisplayName("to send a literal to a text column, the parameter is a String")
	void flag_as_text() {
		assertThat(shapes.textAndBooleanTogether("a", "S")).isEqualTo("aS");
	}

	@Test
	@DisplayName(":result collection without resultType fails before the call: nothing says which collection it is")
	void collection_result() {
		assertThatThrownBy(shapes::returnsList)
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessage("returnsList: :result is a List; declare resultType with the collection type, as in"
						+ " resultType = \"NAME_LIST_T\", or open it as a cursor with OPEN :result FOR");
	}

	@Test
	@DisplayName(":result collection, with the type declared in resultType")
	void typed_collection_result() {
		assertThat(shapes.returnsTypedList()).containsExactly("a");
	}

	@Test
	@DisplayName(":result collection of OBJECT and collection of collections")
	void collection_of_objects_result() {
		assertThat(shapes.returnsAddresses()).containsExactly(new Address("Street A", 1), null);
		assertThat(shapes.returnsMatrix()).containsExactly(List.of("a", "b"), List.of());
	}

	@Test
	@DisplayName("a NULL :result collection becomes an empty list, as for a routine")
	void null_collection_result() {
		assertThat(shapes.returnsNullList()).isEmpty();
	}

	@Test
	@DisplayName(":result cursor, opened with OPEN :result FOR, in any case and spacing")
	void cursor_result() {
		assertThat(shapes.returnsCursor()).containsExactly(new Address("a", 1));
		assertThat(shapes.returnsScalarCursor()).containsExactly("a");
	}

	@Test
	@DisplayName(":result cursor declared with resultType = SYS_REFCURSOR")
	void declared_cursor_result() {
		assertThat(shapes.returnsDeclaredCursor()).containsExactly(new Address("b", 2));
	}

	@Test
	@DisplayName("OPEN :result FOR in a comment or a literal does not make :result a cursor")
	void cursor_detection_ignores_comments_and_literals() {
		assertThatThrownBy(shapes::opensInComment)
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("declare resultType with the collection type");
		assertThatThrownBy(shapes::opensInLiteral)
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("declare resultType with the collection type");
	}

	@Test
	@DisplayName("a cursor or collection :result that is not a List, or a composite one that is, says what to declare")
	void result_that_does_not_fit() {
		assertThatThrownBy(shapes::cursorAsText)
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessage("cursorAsText: :result is a cursor; declare List<T>, not String");
		assertThatThrownBy(shapes::collectionAsText)
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("declare List<T>, not String");
		assertThatThrownBy(shapes::compositeAsList)
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("is a composite type; declare its record or JavaBean, not List");
	}

	@Test
	@DisplayName("colon inside a string literal")
	void colon_in_literal() {
		assertThat(shapes.colonInLiteral()).isEqualTo("a:b");
		assertThat(shapes.withQuotesInLiteral()).isEqualTo("it's :x");
		assertThat(shapes.formattedDate(JAN_15)).isEqualTo("2018-01-15 00:00:00");
	}

	@Test
	@DisplayName("a literal containing a parameter name is not bound, and the parameter next to it is")
	void parameter_name_in_literal() {
		assertThat(shapes.nameInLiteral("x")).isEqualTo(":value=x");
	}

	@Test
	@DisplayName("alternative quoting literal, in every delimiter form")
	void alternative_quoting() {
		assertThat(shapes.alternativeQuotes("x")).isEqualTo("a:b c:d e:f g:h i'j:k l:m x");
	}

	@Test
	@DisplayName("colon in a comment, single-line and block, and in a quoted identifier")
	void comments_and_identifiers() {
		assertThat(shapes.withComments("x")).isEqualTo("x");
		assertThat(shapes.identifierWithColon()).isEqualTo(1);
	}

	@Test
	@DisplayName("a question mark in a literal or comment does not become a bind for the driver")
	void question_mark() {
		assertThat(shapes.withQuestionMark("x")).isEqualTo("a?b x");
	}

	@Test
	@DisplayName("a q outside an alternative quoting literal is just an identifier")
	void identifier_ending_in_q() {
		assertThat(shapes.identifierEndingInQ("x")).isEqualTo("a:b x");
	}
}
