package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlBlock;
import io.github.rponte.penitent.PlsqlOut;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.PlsqlRoutine;
import io.github.rponte.penitent.convert.PlsqlConvert;
import io.github.rponte.penitent.convert.PlsqlConverter;
import io.github.rponte.penitent.convert.PlsqlConverters;
import io.github.rponte.penitent.exception.PlsqlMappingException;

class ConvertersTest extends OracleIntegrationTest {

	record TaxId(String value) {
	}

	enum Consent {
		YES, NO, NOT_INFORMED
	}

	/** The way a legacy database does it: {@code NULL} is a value, and anything unknown is an error. */
	public static class ConsentConverter implements PlsqlConverter<Consent, String> {
		@Override
		public String toDatabase(Consent value) {
			return value == null || value == Consent.NOT_INFORMED ? null : value == Consent.YES ? "Y" : "N";
		}

		@Override
		public Consent fromDatabase(String value) {
			if (value == null) {
				return Consent.NOT_INFORMED;
			}
			return switch (value) {
				case "Y" -> Consent.YES;
				case "N" -> Consent.NO;
				default -> throw new IllegalArgumentException("Invalid consent: " + value);
			};
		}
	}

	/** Does not handle {@code null}: this is the oversight that must fail close to its cause. */
	public static class TaxIdConverter implements PlsqlConverter<TaxId, String> {
		@Override
		public String toDatabase(TaxId value) {
			return value.value();
		}

		@Override
		public TaxId fromDatabase(String value) {
			return new TaxId(value.strip());
		}
	}

	public static class TaxIdWithPrefix implements PlsqlConverter<TaxId, String> {
		@Override
		public String toDatabase(TaxId value) {
			return "local:" + value.value();
		}

		@Override
		public TaxId fromDatabase(String value) {
			return new TaxId(value);
		}
	}

	public static class DropTimeZone implements PlsqlConverter<OffsetDateTime, LocalDateTime> {
		@Override
		public LocalDateTime toDatabase(OffsetDateTime value) {
			return value.toLocalDateTime();
		}

		@Override
		public OffsetDateTime fromDatabase(LocalDateTime value) {
			return value.atOffset(ZoneOffset.UTC);
		}
	}

	public static class KeepTimeZone implements PlsqlConverter<OffsetDateTime, OffsetDateTime> {
		@Override
		public OffsetDateTime toDatabase(OffsetDateTime value) {
			return value;
		}

		@Override
		public OffsetDateTime fromDatabase(OffsetDateTime value) {
			return value;
		}
	}

	record Data(@PlsqlConvert(TaxIdConverter.class) TaxId document, @PlsqlConvert(ConsentConverter.class) Consent consent,
			@PlsqlConvert(TaxIdConverter.class) List<TaxId> names) {
	}

	record GlobalData(TaxId document, String consent, TaxId[] names) {
	}

	record PrimitiveData(int v) {
	}

	record ConvertedNumber(@PlsqlConvert(PrimitiveNumber.class) int v) {
	}

	public static class PrimitiveNumber implements PlsqlConverter<Integer, Integer> {
		@Override
		public Integer toDatabase(Integer value) {
			return value;
		}

		@Override
		public Integer fromDatabase(Integer value) {
			return value == null ? -1 : value;
		}
	}

	record ConvertedItem(@PlsqlConvert(ConsentConverter.class) Consent consent, TaxId document) {
	}

	record PrimitiveItem(int quantity) {
	}

	record InvalidItem(@PlsqlConvert(TaxIdConverter.class) TaxId document) {
	}

	record Num(Integer v) {
	}

	record Envelope(@PlsqlConvert(NumberToText.class) Num n) {
	}

	public static class NumberToText implements PlsqlConverter<Num, String> {
		@Override
		public String toDatabase(Num value) {
			return String.valueOf(value);
		}

		@Override
		public Num fromDatabase(String value) {
			return new Num(1);
		}
	}

	@PlsqlPackage("FIXTURE_CONVERTERS")
	interface Conversions {
		@PlsqlRoutine("ECHO_TEXT")
		@PlsqlConvert(TaxIdConverter.class)
		TaxId echo(@PlsqlConvert(TaxIdConverter.class) TaxId value);

		@PlsqlRoutine("ECHO_TEXT")
		String echoGlobal(TaxId value);

		@PlsqlRoutine("ECHO_TEXT")
		String echoLocal(@PlsqlConvert(TaxIdWithPrefix.class) TaxId value);

		@PlsqlRoutine("DESCRIBE")
		String describe(@PlsqlConvert(ConsentConverter.class) Consent value);

		@PlsqlRoutine("NULL_VALUE")
		@PlsqlConvert(ConsentConverter.class)
		Consent null_value();

		@PlsqlRoutine("INVALID")
		@PlsqlConvert(ConsentConverter.class)
		Consent invalid();

		@PlsqlRoutine("NULL_VALUE")
		@PlsqlConvert(TaxIdConverter.class)
		TaxId forgottenNull();

		@PlsqlRoutine("DESCRIBE")
		String forgottenWrite(@PlsqlConvert(TaxIdConverter.class) TaxId value);

		@PlsqlRoutine("OVERLOAD")
		String overloadText(@PlsqlConvert(TaxIdConverter.class) TaxId value);

		@PlsqlRoutine("OUT_TEXT")
		void out(@PlsqlConvert(TaxIdConverter.class) PlsqlOut<TaxId> value);

		@PlsqlRoutine("CHANGE_TEXT")
		void inOut(@PlsqlConvert(TaxIdConverter.class) PlsqlOut<TaxId> value);

		@PlsqlRoutine("ECHO_TIMESTAMP")
		@PlsqlConvert(DropTimeZone.class)
		OffsetDateTime dropTimeZone(@PlsqlConvert(DropTimeZone.class) OffsetDateTime value);

		@PlsqlRoutine("ECHO_TIMESTAMP")
		LocalDateTime keepTimeZone(@PlsqlConvert(KeepTimeZone.class) OffsetDateTime value);

		@PlsqlRoutine("ECHO_TEXTS")
		@PlsqlConvert(TaxIdConverter.class)
		List<TaxId> list(@PlsqlConvert(TaxIdConverter.class) List<TaxId> value);

		@PlsqlRoutine("ECHO_TEXTS")
		@PlsqlConvert(ConsentConverter.class)
		List<Consent> listWithNulls(@PlsqlConvert(ConsentConverter.class) List<Consent> value);

		@PlsqlRoutine("COUNT_TEXTS")
		int countArray(@PlsqlConvert(TaxIdConverter.class) TaxId[] value);

		@PlsqlRoutine("COUNT_TEXTS")
		int nullCount(@PlsqlConvert(TaxIdConverter.class) List<TaxId> value);

		@PlsqlRoutine("ECHO_DATA")
		Data data(Data value);

		@PlsqlRoutine("ECHO_DATA")
		GlobalData globalData(GlobalData value);

		@PlsqlRoutine("NULL_NUMBER")
		PrimitiveData nullPrimitive();

		@PlsqlRoutine("NULL_NUMBER")
		ConvertedNumber convertedPrimitive();

		@PlsqlRoutine("ITEMS")
		List<ConvertedItem> items();

		@PlsqlRoutine("ITEMS")
		List<PrimitiveItem> primitiveItems();

		@PlsqlRoutine("ITEMS")
		List<InvalidItem> invalidItems();

		@PlsqlBlock("begin :result := :value; end;")
		@PlsqlConvert(TaxIdConverter.class)
		TaxId block(@PlsqlConvert(TaxIdConverter.class) TaxId value);

		@PlsqlBlock("begin :result := case when :value is null then 'got NULL' else 'other' end; end;")
		String blockNull(@PlsqlConvert(ConsentConverter.class) Consent value);

		@PlsqlBlock("begin :result := :value; end;")
		@PlsqlConvert(DropTimeZone.class)
		OffsetDateTime blockWithTimeZone(@PlsqlConvert(DropTimeZone.class) OffsetDateTime value);

		@PlsqlBlock("declare l_value varchar2(1) := :value; begin null; end;")
		void blockWithList(@PlsqlConvert(TaxIdConverter.class) List<TaxId> value);

		@PlsqlRoutine("ECHO_NUMBER_OBJ")
		@PlsqlConvert(NumberToText.class)
		Num converterOnObject(@PlsqlConvert(NumberToText.class) Num value);

		@PlsqlRoutine("ECHO_OBJECTS")
		List<Num> converterOnObjectCollection(@PlsqlConvert(NumberToText.class) List<Num> value);

		@PlsqlRoutine("ECHO_TEXTS")
		String scalarConverterOnCollection(@PlsqlConvert(TaxIdConverter.class) TaxId value);

		@PlsqlRoutine("ECHO_TEXT")
		String converterFromListToScalar(@PlsqlConvert(TaxIdConverter.class) List<TaxId> value);

		@PlsqlRoutine("ITEMS")
		@PlsqlConvert(NumberToText.class)
		List<Num> converterOnCursor();

		@PlsqlRoutine("ECHO_ENVELOPE")
		Envelope converterOnCompositeAttribute(Envelope value);
	}

	private static final TaxId TAX_ID = new TaxId("123");

	private final Conversions conversions = pkg(Conversions.class);

	private final Conversions withGlobals = PlsqlPackages.create(Conversions.class, DATA_SOURCE, PlsqlConverters.of(
			PlsqlConverter.ofNullable(TaxId.class, String.class, TaxId::value, TaxId::new)));

	@Test
	@DisplayName("@PlsqlConvert on parameter and return")
	void parameter_and_return() {
		assertThat(conversions.echo(TAX_ID)).isEqualTo(TAX_ID);
	}

	@Test
	@DisplayName("PlsqlConverters applies to the type across the whole interface, and the annotation wins")
	void global_and_local() {
		assertThat(withGlobals.echoGlobal(TAX_ID)).isEqualTo("123");
		assertThat(withGlobals.echoLocal(TAX_ID)).isEqualTo("local:123");
	}

	@Test
	@DisplayName("NULL goes through the converter in both directions, and can mean something")
	void null_has_meaning() {
		assertThat(conversions.null_value()).isEqualTo(Consent.NOT_INFORMED);
		assertThat(conversions.describe(Consent.NOT_INFORMED)).isEqualTo("got NULL");
		assertThat(conversions.describe(null)).isEqualTo("got NULL");
		assertThat(conversions.describe(Consent.YES)).isEqualTo("got Y");
	}

	@Test
	@DisplayName("forgetting about NULL fails with the converter and the location, instead of blowing up far from the cause")
	void forgotten_null() {
		assertThatThrownBy(conversions::forgottenNull)
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageStartingWith("forgottenNull(): the return value of ")
				.hasMessageContaining("TaxIdConverter failed to read NULL");
		assertThatThrownBy(() -> conversions.forgottenWrite(null))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageStartingWith("forgottenWrite(TaxId): P_VALUE of ")
				.hasMessageContaining("TaxIdConverter failed to write NULL");
	}

	@Test
	@DisplayName("ofNullable keeps the null without calling the function")
	void of_nullable() {
		assertThat(withGlobals.echoGlobal(null)).isNull();
	}

	@Test
	@DisplayName("inconsistent data: the converter decides, and the failure quotes the value")
	void inconsistent_data() {
		assertThatThrownBy(conversions::invalid)
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("ConsentConverter failed to read 'X'")
				.hasMessageContaining("Invalid consent: X");
	}

	@Test
	@DisplayName("D picks the overload version")
	void overloading() {
		assertThat(conversions.overloadText(TAX_ID)).isEqualTo("text");
	}

	@Test
	@DisplayName("OUT and IN OUT")
	void out_parameters() {
		PlsqlOut<TaxId> output = new PlsqlOut<>();
		conversions.out(output);
		assertThat(output.get()).isEqualTo(TAX_ID);

		PlsqlOut<TaxId> changed = PlsqlOut.of(TAX_ID);
		conversions.inOut(changed);
		assertThat(changed.get()).isEqualTo(new TaxId("1239"));
	}

	@Test
	@DisplayName("dropping the time zone on purpose: validation looks at D")
	void validation_looks_at_d() {
		OffsetDateTime value = OffsetDateTime.parse("2018-01-15T10:30:00Z");

		assertThat(conversions.dropTimeZone(value)).isEqualTo(value);
		assertThat(conversions.dropTimeZone(value.withOffsetSameLocal(ZoneOffset.ofHours(9))))
				.isEqualTo(value);
		assertThatThrownBy(() -> conversions.keepTimeZone(value))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("OffsetDateTime would lose the time zone");
	}

	@Test
	@DisplayName("collection: element-wise, in List and array, and a null element goes through the converter")
	void collections() {
		assertThat(conversions.list(List.of(TAX_ID))).containsExactly(TAX_ID);
		assertThat(conversions.listWithNulls(Arrays.asList(Consent.YES, null, Consent.NOT_INFORMED)))
				.containsExactly(Consent.YES, Consent.NOT_INFORMED, Consent.NOT_INFORMED);
		assertThat(conversions.countArray(new TaxId[] { TAX_ID, TAX_ID })).isEqualTo(2);
	}

	@Test
	@DisplayName("a whole null collection does not go through the converter")
	void whole_null_collection() {
		assertThat(conversions.nullCount(null)).isEqualTo(-1);
		assertThat(conversions.list(null)).isEmpty();
	}

	@Test
	@DisplayName("OBJECT attribute, with annotation and with registration, and NULL with meaning")
	void attributes() {
		assertThat(conversions.data(new Data(TAX_ID, null, List.of(TAX_ID))))
				.isEqualTo(new Data(TAX_ID, Consent.NOT_INFORMED, List.of(TAX_ID)));

		GlobalData global = withGlobals.globalData(new GlobalData(TAX_ID, "Y", new TaxId[] { TAX_ID }));
		assertThat(global.document()).isEqualTo(TAX_ID);
		assertThat(global.names()).containsExactly(TAX_ID);
	}

	@Test
	@DisplayName("cursor column, with annotation and with registration, and NULL with meaning")
	void cursor() {
		assertThat(withGlobals.items()).containsExactly(
				new ConvertedItem(Consent.YES, TAX_ID),
				new ConvertedItem(Consent.NOT_INFORMED, null));
		assertThatThrownBy(conversions::invalidItems)
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("cursor column DOCUMENT")
				.hasMessageContaining("TaxIdConverter failed to read NULL");
	}

	@Test
	@DisplayName("anonymous block: D decides the type of :result, and NULL goes through the converter")
	void anonymous_block() {
		OffsetDateTime value = OffsetDateTime.parse("2018-01-15T10:30:00Z");

		assertThat(conversions.block(TAX_ID)).isEqualTo(TAX_ID);
		assertThat(conversions.blockNull(Consent.NOT_INFORMED)).isEqualTo("got NULL");
		assertThat(conversions.blockWithTimeZone(value)).isEqualTo(value);
		assertThatThrownBy(() -> conversions.blockWithList(List.of(TAX_ID)))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("only applies to a scalar value");
	}

	@Test
	@DisplayName("a converter only applies to a scalar: composite and collection of composites are rejected")
	void where_it_does_not_apply() {
		Num num = new Num(1);
		String message = "only applies to a scalar value";

		assertThatThrownBy(() -> conversions.converterOnObject(num))
				.isInstanceOf(PlsqlMappingException.class).hasMessageContaining(message);
		assertThatThrownBy(() -> conversions.converterOnObjectCollection(List.of(num)))
				.isInstanceOf(PlsqlMappingException.class).hasMessageContaining(message);
		assertThatThrownBy(() -> conversions.scalarConverterOnCollection(TAX_ID))
				.isInstanceOf(PlsqlMappingException.class).hasMessageContaining(message);
		assertThatThrownBy(() -> conversions.converterFromListToScalar(List.of(TAX_ID)))
				.isInstanceOf(PlsqlMappingException.class).hasMessageContaining(message);
		assertThatThrownBy(() -> conversions.converterOnCompositeAttribute(new Envelope(num)))
				.isInstanceOf(PlsqlMappingException.class).hasMessageContaining(message);
	}

	@PlsqlPackage("FIXTURE_NESTED")
	interface ScalarCursors {
		@PlsqlRoutine("NUMBERS_CURSOR")
		@PlsqlConvert(TaxIdConverter.class)
		List<TaxId> numbersAsTaxIds();
	}

	@Test
	@DisplayName("a converter on the element of a cursor makes it a cursor of that scalar, read from its only column")
	void converter_on_cursor() {
		assertThat(pkg(ScalarCursors.class).numbersAsTaxIds()).containsExactly(new TaxId("1.5"), new TaxId("3"));
		assertThatThrownBy(conversions::converterOnCursor)
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("a cursor of Num is read from its only column");
	}

	@Test
	@DisplayName("NULL in a primitive is an error, with the location, and a converter can give it meaning")
	void null_in_primitive() {
		assertThatThrownBy(conversions::nullPrimitive)
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("attribute V")
				.hasMessageContaining("NULL does not fit in int");
		assertThatThrownBy(conversions::primitiveItems)
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("column QUANTITY")
				.hasMessageContaining("NULL does not fit in int");
		assertThat(conversions.convertedPrimitive().v()).isEqualTo(-1);
	}
}
