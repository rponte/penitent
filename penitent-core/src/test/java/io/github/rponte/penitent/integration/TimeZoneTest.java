package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlBlock;
import io.github.rponte.penitent.PlsqlOut;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlRoutine;
import io.github.rponte.penitent.exception.PlsqlMappingException;

/** An Oracle type with a time zone stores an instant, so only a Java type that also stores one will do. */
class TimeZoneTest extends OracleIntegrationTest {

	private static final OffsetDateTime WITH_OFFSET = OffsetDateTime.parse("2018-01-15T10:30:00-03:00");
	private static final ZonedDateTime WITH_REGION = ZonedDateTime.parse("2018-01-15T10:30:00+09:00[Asia/Tokyo]");
	private static final Instant INSTANT = Instant.parse("2018-01-15T13:30:00Z");

	record AttributeOffset(OffsetDateTime v) {
	}

	record AttributeZoned(ZonedDateTime v) {
	}

	record AttributeInstant(Instant v) {
	}

	record AttributeLocal(LocalDateTime v) {
	}

	record ZonedItem(OffsetDateTime withTimeZone, Instant sessionLocal, LocalDateTime withoutTimeZone) {
	}

	record ZonedDateTimeItem(ZonedDateTime withTimeZone) {
	}

	record LocalDateTimeItem(LocalDateTime withTimeZone) {
	}

	record InstantItemWithoutTimeZone(Instant withoutTimeZone) {
	}

	record ListAttribute(List<OffsetDateTime> v) {
	}

	record LocalDateTimeListAttribute(List<LocalDateTime> v) {
	}

	record ZonedDateTimeListAttribute(List<ZonedDateTime> v) {
	}

	@PlsqlPackage("FIXTURE_TIME_ZONE_ATTRIBUTES")
	interface AttributeWithoutTimeZone {
		@PlsqlRoutine("ECHO_TIMESTAMP")
		AttributeOffset offset(AttributeOffset value);
	}

	@PlsqlPackage("FIXTURE_TIME_ZONE_LISTS")
	interface Lists {
		@PlsqlRoutine("ECHO_TSTZ")
		List<OffsetDateTime> tstzOffset(List<OffsetDateTime> value);

		@PlsqlRoutine("ECHO_TSTZ")
		List<ZonedDateTime> tstzZoned(List<ZonedDateTime> value);

		@PlsqlRoutine("ECHO_TSTZ")
		List<Instant> tstzInstant(List<Instant> value);

		@PlsqlRoutine("ECHO_TSTZ")
		List<OffsetDateTime> tstzFromArray(OffsetDateTime[] value);

		@PlsqlRoutine("ECHO_VARRAY")
		List<OffsetDateTime> varray(List<OffsetDateTime> value);

		@PlsqlRoutine("ECHO_PKG")
		List<OffsetDateTime> fromPackage(List<OffsetDateTime> value);

		@PlsqlRoutine("ECHO_TSLTZ")
		List<OffsetDateTime> tsltz(List<OffsetDateTime> value);

		@PlsqlRoutine("IN_TOKYO")
		List<ZonedDateTime> inTokyo();

		@PlsqlRoutine("OUT_TSTZ")
		void outTstz(PlsqlOut<List<ZonedDateTime>> value);

		@PlsqlRoutine("ECHO_ATTRIBUTE")
		ListAttribute attribute(ListAttribute value);

		@PlsqlRoutine("ECHO_ATTRIBUTE")
		ZonedDateTimeListAttribute attributeZoned(ZonedDateTimeListAttribute value);

		@PlsqlRoutine("ECHO_TSTZ")
		List<LocalDateTime> localAsTstz(List<LocalDateTime> value);

		@PlsqlRoutine("ECHO_TSTZ")
		LocalDateTime[] localAsTstzFromArray(LocalDateTime[] value);

		@PlsqlRoutine("IN_TOKYO")
		List<LocalDateTime> localResult();

		@PlsqlRoutine("OUT_TSTZ")
		void outLocal(PlsqlOut<List<LocalDateTime>> value);

		@PlsqlRoutine("ECHO_ATTRIBUTE")
		LocalDateTimeListAttribute attributeLocal(LocalDateTimeListAttribute value);

		@PlsqlRoutine("ECHO_TIMESTAMP")
		List<OffsetDateTime> offsetAsTimestamp(List<OffsetDateTime> value);

		@PlsqlRoutine("ECHO_TIMESTAMP")
		List<Instant> instantAsTimestamp(List<Instant> value);

		@PlsqlRoutine("ECHO_TIMESTAMP")
		List<LocalDateTime> localAsTimestamp(List<LocalDateTime> value);
	}

	@PlsqlPackage("FIXTURE_TIME_ZONE")
	interface TimeZones {
		@PlsqlRoutine("ECHO_TIMESTAMP")
		LocalDateTime offsetAsTimestamp(OffsetDateTime value);

		@PlsqlRoutine("ECHO_TIMESTAMP")
		LocalDateTime zonedAsTimestamp(ZonedDateTime value);

		@PlsqlRoutine("ECHO_DATE")
		LocalDateTime offsetAsDate(OffsetDateTime value);

		@PlsqlRoutine("ECHO_TIMESTAMP")
		OffsetDateTime offsetResultFromTimestamp(LocalDateTime value);

		@PlsqlRoutine("ECHO_TSTZ")
		OffsetDateTime tstzOffset(OffsetDateTime value);

		@PlsqlRoutine("ECHO_TSTZ")
		ZonedDateTime tstzZoned(ZonedDateTime value);

		@PlsqlRoutine("ECHO_TSTZ")
		Instant tstzInstant(Instant value);

		@PlsqlRoutine("ECHO_TSLTZ")
		OffsetDateTime tsltzOffset(OffsetDateTime value);

		@PlsqlRoutine("ECHO_TSLTZ")
		ZonedDateTime tsltzZoned(ZonedDateTime value);

		@PlsqlRoutine("ECHO_TSLTZ")
		Instant tsltzInstant(Instant value);

		@PlsqlRoutine("IN_TOKYO")
		OffsetDateTime tokyoOffset();

		@PlsqlRoutine("IN_TOKYO")
		ZonedDateTime tokyoZoned();

		@PlsqlRoutine("IN_TOKYO")
		Instant tokyoInstant();

		String asStored(Instant value);

		void outTstz(PlsqlOut<ZonedDateTime> value);

		void changeTstz(PlsqlOut<OffsetDateTime> value);

		void outTsltz(PlsqlOut<Instant> value);

		@PlsqlRoutine("ECHO_ATTRIBUTE_TSTZ")
		AttributeOffset attributeOffset(AttributeOffset value);

		@PlsqlRoutine("ECHO_ATTRIBUTE_TSTZ")
		AttributeInstant attributeInstant(AttributeInstant value);

		@PlsqlRoutine("ECHO_ATTRIBUTE_TSTZ")
		AttributeZoned attributeZoned(AttributeZoned value);

		@PlsqlRoutine("ATTRIBUTE_IN_TOKYO")
		AttributeZoned attributeInTokyo();

		@PlsqlRoutine("ECHO_ATTRIBUTE_TSLTZ")
		AttributeOffset attributeTsltz(AttributeOffset value);

		@PlsqlRoutine("ECHO_ATTRIBUTE_TSTZ")
		AttributeLocal attributeLocal(AttributeLocal value);

		@PlsqlRoutine("ECHO_ATTRIBUTE_TSLTZ")
		AttributeLocal attributeLocalTsltz(AttributeLocal value);

		@PlsqlRoutine("DATES_CURSOR")
		List<ZonedItem> cursor();

		@PlsqlRoutine("DATES_CURSOR")
		List<ZonedDateTimeItem> cursorZoned();

		@PlsqlRoutine("DATES_CURSOR")
		List<LocalDateTimeItem> cursorLocal();

		@PlsqlRoutine("DATES_CURSOR")
		List<InstantItemWithoutTimeZone> instantCursorWithoutTimeZone();

		@PlsqlRoutine("OVERLOAD")
		String overloadOffset(OffsetDateTime value);

		@PlsqlRoutine("OVERLOAD")
		String overloadInstant(Instant value);

		@PlsqlRoutine("OVERLOAD")
		String overloadLocal(LocalDateTime value);

		@PlsqlRoutine("ECHO_TSTZ")
		LocalDateTime tstzAsLocalDateTime(LocalDateTime value);

		@PlsqlRoutine("IN_TOKYO")
		LocalDateTime resultAsLocalDateTime();

		@PlsqlRoutine("IN_TOKYO")
		LocalDate resultAsLocalDate();

		@PlsqlRoutine("OUT_TSTZ")
		void outAsLocalDateTime(PlsqlOut<LocalDateTime> value);

		@PlsqlRoutine("ECHO_TSLTZ")
		LocalDateTime tsltzAsLocalDateTime(LocalDateTime value);

		@PlsqlRoutine("ECHO_TIMESTAMP")
		Instant timestampAsInstant(Instant value);

		@PlsqlRoutine("ECHO_DATE")
		Instant dateAsInstant(Instant value);

		@PlsqlBlock("begin :result := :value; end;")
		OffsetDateTime blockOffset(OffsetDateTime value);

		@PlsqlBlock("begin :result := :value; end;")
		Instant blockInstant(Instant value);

		@PlsqlBlock("begin :result := :value; end;")
		ZonedDateTime blockZoned(ZonedDateTime value);
	}

	private final TimeZones timeZones = pkg(TimeZones.class);
	private final Lists lists = pkg(Lists.class);

	@Test
	@DisplayName("TIMESTAMP WITH TIME ZONE becomes OffsetDateTime, with the offset intact")
	void tstz_to_offset() {
		assertExactly(timeZones.tstzOffset(WITH_OFFSET), WITH_OFFSET);
	}

	@Test
	@DisplayName("TIMESTAMP WITH TIME ZONE becomes ZonedDateTime, with the region intact")
	void tstz_to_zoned() {
		assertExactly(timeZones.tstzZoned(WITH_REGION), WITH_REGION);
		assertExactly(timeZones.tokyoZoned(), WITH_REGION);
	}

	@Test
	@DisplayName("OffsetDateTime loses the region name, but not the instant")
	void region_to_offset() {
		assertExactly(timeZones.tokyoOffset(), WITH_REGION.toOffsetDateTime());
	}

	@Test
	@DisplayName("TIMESTAMP WITH TIME ZONE becomes Instant")
	void tstz_to_instant() {
		assertThat(timeZones.tstzInstant(INSTANT)).isEqualTo(INSTANT);
		assertThat(timeZones.tokyoInstant()).isEqualTo(WITH_REGION.toInstant());
	}

	@Test
	@DisplayName("Instant reaches the routine in UTC, and that is how PL/SQL sees the time")
	void instant_arrives_in_utc() {
		assertThat(timeZones.asStored(INSTANT)).isEqualTo("13:30 +00:00");
	}

	@Test
	@DisplayName("TIMESTAMP WITH LOCAL TIME ZONE keeps the instant, in all three types")
	void tsltz() {
		assertThat(timeZones.tsltzOffset(WITH_OFFSET)).isAtSameInstantAs(WITH_OFFSET);
		assertThat(timeZones.tsltzZoned(WITH_REGION).toInstant()).isEqualTo(WITH_REGION.toInstant());
		assertThat(timeZones.tsltzInstant(INSTANT)).isEqualTo(INSTANT);
	}

	@Test
	@DisplayName("null stays null in both time zone types")
	void nulls() {
		assertThat(timeZones.tstzOffset(null)).isNull();
		assertThat(timeZones.tstzZoned(null)).isNull();
		assertThat(timeZones.tstzInstant(null)).isNull();
		assertThat(timeZones.tsltzOffset(null)).isNull();
		assertThat(timeZones.tsltzInstant(null)).isNull();
	}

	@Test
	@DisplayName("OUT and IN OUT, with both time zone types")
	void out_parameters() {
		PlsqlOut<ZonedDateTime> zoned = new PlsqlOut<>();
		timeZones.outTstz(zoned);
		assertExactly(zoned.get(), WITH_REGION);

		PlsqlOut<OffsetDateTime> changed = PlsqlOut.of(WITH_OFFSET);
		timeZones.changeTstz(changed);
		assertThat(changed.get()).isEqualTo(WITH_OFFSET.plusHours(1));

		PlsqlOut<Instant> instant = new PlsqlOut<>();
		timeZones.outTsltz(instant);
		assertThat(instant.get()).isEqualTo(WITH_REGION.toInstant());
	}

	@Test
	@DisplayName("OBJECT attribute with a time zone, in all three types")
	void attributes() {
		assertExactly(timeZones.attributeOffset(new AttributeOffset(WITH_OFFSET)).v(), WITH_OFFSET);
		assertThat(timeZones.attributeInstant(new AttributeInstant(INSTANT)).v()).isEqualTo(INSTANT);
		assertExactly(timeZones.attributeZoned(new AttributeZoned(WITH_REGION)).v(), WITH_REGION);
		assertExactly(timeZones.attributeInTokyo().v(), WITH_REGION);
		assertThat(timeZones.attributeTsltz(new AttributeOffset(WITH_OFFSET)).v()).isAtSameInstantAs(WITH_OFFSET);
	}

	@Test
	@DisplayName("cursor column with a time zone, and the one without stays as LocalDateTime")
	void cursor() {
		ZonedItem item = timeZones.cursor().get(0);

		assertExactly(item.withTimeZone(), WITH_REGION.toOffsetDateTime());
		assertThat(item.sessionLocal()).isEqualTo(WITH_REGION.toInstant());
		assertThat(item.withoutTimeZone()).isEqualTo(WITH_REGION.toLocalDateTime());
		assertExactly(timeZones.cursorZoned().get(0).withTimeZone(), WITH_REGION);
	}

	@Test
	@DisplayName("in an overload, the Java type picks the version with or without a time zone")
	void overloading() {
		assertThat(timeZones.overloadOffset(WITH_OFFSET)).isEqualTo("with time zone");
		assertThat(timeZones.overloadInstant(INSTANT)).isEqualTo("with time zone");
		assertThat(timeZones.overloadLocal(WITH_OFFSET.toLocalDateTime())).isEqualTo("without time zone");
	}

	@Test
	@DisplayName("anonymous block: the type of :result comes from Java")
	void anonymous_block() {
		assertExactly(timeZones.blockOffset(WITH_OFFSET), WITH_OFFSET);
		assertThat(timeZones.blockInstant(INSTANT)).isEqualTo(INSTANT);
		assertExactly(timeZones.blockZoned(WITH_REGION), WITH_REGION);
	}

	@Test
	@DisplayName("LocalDateTime in a time zone type is rejected before the call, in parameter, return and OUT")
	void local_date_time_rejected() {
		LocalDateTime value = WITH_OFFSET.toLocalDateTime();

		assertThatThrownBy(() -> timeZones.tstzAsLocalDateTime(value))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("P_VALUE of ")
				.hasMessageContaining(" is TIMESTAMP WITH TIME ZONE")
				.hasMessageContaining("declare OffsetDateTime, ZonedDateTime or Instant");
		assertThatThrownBy(timeZones::resultAsLocalDateTime)
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("the return value of ")
				.hasMessageContaining(" is TIMESTAMP WITH TIME ZONE");
		assertThatThrownBy(timeZones::resultAsLocalDate)
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("LocalDate has no time zone");
		assertThatThrownBy(() -> timeZones.outAsLocalDateTime(new PlsqlOut<>()))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("P_VALUE of ")
				.hasMessageContaining(" is TIMESTAMP WITH TIME ZONE");
		assertThatThrownBy(() -> timeZones.tsltzAsLocalDateTime(value))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("TIMESTAMP WITH LOCAL TIME ZONE");
	}

	@Test
	@DisplayName("LocalDateTime in a time zone attribute is rejected, with the attribute in the message")
	void local_date_time_in_attribute() {
		AttributeLocal value = new AttributeLocal(WITH_OFFSET.toLocalDateTime());

		assertThatThrownBy(() -> timeZones.attributeLocal(value))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("AttributeLocal.v")
				.hasMessageContaining("LocalDateTime has no time zone");
		assertThatThrownBy(() -> timeZones.attributeLocalTsltz(value))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("LocalDateTime has no time zone");
	}

	@Test
	@DisplayName("a cursor column has no data dictionary entry: the rejection comes on read, with the column in the message")
	void rejection_in_cursor() {
		assertThatThrownBy(timeZones::cursorLocal)
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("WITH_TIME_ZONE")
				.hasMessageContaining("LocalDateTime has no time zone");
		assertThatThrownBy(timeZones::instantCursorWithoutTimeZone)
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("WITHOUT_TIME_ZONE")
				.hasMessageContaining("Instant would need a time zone");
	}

	@Test
	@DisplayName("Instant in DATE or TIMESTAMP is rejected: the type has no time zone to give the instant meaning")
	void instant_rejected() {
		assertThatThrownBy(() -> timeZones.timestampAsInstant(INSTANT))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("P_VALUE of ")
				.hasMessageContaining(" is TIMESTAMP")
				.hasMessageContaining("declare LocalDateTime");
		assertThatThrownBy(() -> timeZones.dateAsInstant(INSTANT))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("P_VALUE of ")
				.hasMessageContaining(" is DATE");
	}

	@Test
	@DisplayName("OffsetDateTime and ZonedDateTime in DATE or TIMESTAMP are rejected: the time zone would be lost")
	void time_zone_rejected_in_type_without_time_zone() {
		assertThatThrownBy(() -> timeZones.offsetAsTimestamp(WITH_OFFSET))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("P_VALUE of ")
				.hasMessageContaining(" is TIMESTAMP")
				.hasMessageContaining("OffsetDateTime would lose the time zone");
		assertThatThrownBy(() -> timeZones.zonedAsTimestamp(WITH_REGION))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("ZonedDateTime would lose the time zone");
		assertThatThrownBy(() -> timeZones.offsetAsDate(WITH_OFFSET))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("P_VALUE of ")
				.hasMessageContaining(" is DATE");
		assertThatThrownBy(() -> timeZones.offsetResultFromTimestamp(WITH_OFFSET.toLocalDateTime()))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("the return value of ")
				.hasMessageContaining(" is TIMESTAMP");
		assertThatThrownBy(() -> pkg(AttributeWithoutTimeZone.class).offset(new AttributeOffset(WITH_OFFSET)))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("AttributeOffset.v")
				.hasMessageContaining("OffsetDateTime would lose the time zone");
	}

	@Test
	@DisplayName("TABLE OF TIMESTAMP WITH TIME ZONE, in all three types, with a null element and without losing the region")
	void collection_of_tstz() {
		assertThat(lists.tstzOffset(Arrays.asList(WITH_OFFSET, null))).containsExactly(WITH_OFFSET, null);
		assertThat(lists.tstzZoned(Arrays.asList(WITH_REGION, null))).containsExactly(WITH_REGION, null);
		assertThat(lists.tstzInstant(List.of(INSTANT))).containsExactly(INSTANT);
		assertThat(lists.tstzFromArray(new OffsetDateTime[] { WITH_OFFSET })).containsExactly(WITH_OFFSET);
		assertThat(lists.tstzOffset(List.of())).isEmpty();
		assertThat(lists.tstzOffset(null)).isEmpty();
		assertThat(lists.inTokyo()).containsExactly(WITH_REGION);
	}

	@Test
	@DisplayName("VARRAY, package collection and TIMESTAMP WITH LOCAL TIME ZONE")
	void other_kinds_of_collection() {
		assertThat(lists.varray(List.of(WITH_OFFSET))).containsExactly(WITH_OFFSET);
		assertThat(lists.fromPackage(List.of(WITH_OFFSET))).containsExactly(WITH_OFFSET);
		assertThat(lists.tsltz(List.of(WITH_OFFSET)).get(0)).isAtSameInstantAs(WITH_OFFSET);
	}

	@Test
	@DisplayName("time zone collection in OUT and in an OBJECT attribute")
	void collection_in_out_and_attribute() {
		PlsqlOut<List<ZonedDateTime>> output = new PlsqlOut<>();
		lists.outTstz(output);
		assertThat(output.get()).containsExactly(WITH_REGION);

		assertThat(lists.attribute(new ListAttribute(List.of(WITH_OFFSET))).v()).containsExactly(WITH_OFFSET);
		assertThat(lists.attributeZoned(new ZonedDateTimeListAttribute(List.of(WITH_REGION))).v()).containsExactly(WITH_REGION);
	}

	@Test
	@DisplayName("a LocalDateTime element in a time zone collection is rejected before the call")
	void local_element_rejected() {
		List<LocalDateTime> value = List.of(WITH_OFFSET.toLocalDateTime());

		assertThatThrownBy(() -> lists.localAsTstz(value))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("P_VALUE of ")
				.hasMessageContaining(" is a collection of TIMESTAMP")
				.hasMessageContaining("LocalDateTime has no time zone");
		assertThatThrownBy(() -> lists.localAsTstzFromArray(value.toArray(LocalDateTime[]::new)))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("LocalDateTime has no time zone");
		assertThatThrownBy(lists::localResult)
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("the return value of ")
				.hasMessageContaining(" is a collection");
		assertThatThrownBy(() -> lists.outLocal(new PlsqlOut<>()))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("P_VALUE of ")
				.hasMessageContaining(" is a collection");
		assertThatThrownBy(() -> lists.attributeLocal(new LocalDateTimeListAttribute(value)))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("LocalDateTimeListAttribute.v")
				.hasMessageContaining("LocalDateTime has no time zone");
	}

	@Test
	@DisplayName("a time zone element in a plain TIMESTAMP collection is rejected, and LocalDateTime still works")
	void time_zone_element_rejected() {
		assertThatThrownBy(() -> lists.offsetAsTimestamp(List.of(WITH_OFFSET)))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("OffsetDateTime would lose the time zone");
		assertThatThrownBy(() -> lists.instantAsTimestamp(List.of(INSTANT)))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("Instant would need a time zone");

		LocalDateTime value = WITH_OFFSET.toLocalDateTime();
		assertThat(lists.localAsTimestamp(List.of(value))).containsExactly(value);
	}

	@Test
	@DisplayName("the instant does not depend on the offset chosen to represent it")
	void same_instant() {
		OffsetDateTime inUtc = WITH_OFFSET.withOffsetSameInstant(ZoneOffset.UTC);

		assertThat(timeZones.tstzOffset(inUtc)).isAtSameInstantAs(WITH_OFFSET);
	}

	/**
	 * AssertJ compares a {@code ZonedDateTime} or {@code OffsetDateTime} by its instant, so a lost region
	 * or offset would pass; this compares with {@code equals}.
	 */
	private static void assertExactly(Object actual, Object expected) {
		assertThat(actual).isEqualTo(expected);
	}
}
