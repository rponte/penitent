package io.github.rponte.penitent.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.internal.convert.AppliedConverter;

/** What can be proven without a database: which types each converter declares, and which one applies at each point. */
class PlsqlConvertersTest {

	record TaxId(String value) {
	}

	static class TaxIdConverter implements PlsqlConverter<TaxId, String> {
		@Override
		public String toDatabase(TaxId value) {
			return value.value();
		}

		@Override
		public TaxId fromDatabase(String value) {
			return new TaxId(value);
		}
	}

	static class InheritedTaxIdConverter extends TaxIdConverter {
	}

	static class GenericConverter<T> implements PlsqlConverter<T, String> {
		@Override
		public String toDatabase(T value) {
			return null;
		}

		@Override
		public T fromDatabase(String value) {
			return null;
		}
	}

	static class WithoutConstructor implements PlsqlConverter<TaxId, String> {
		WithoutConstructor(int any) {
		}

		@Override
		public String toDatabase(TaxId value) {
			return null;
		}

		@Override
		public TaxId fromDatabase(String value) {
			return null;
		}
	}

	static class Annotated {
		@PlsqlConvert(TaxIdConverter.class)
		TaxId taxId;

		@PlsqlConvert(WithoutConstructor.class)
		TaxId withoutConstructor;
	}

	private static PlsqlConvert annotation(String field) throws NoSuchFieldException {
		return Annotated.class.getDeclaredField(field).getAnnotation(PlsqlConvert.class);
	}

	@Test
	@DisplayName("reads the types from the class, including inherited ones, and applies to the value or to the element")
	void types_from_the_class() {
		PlsqlConverters converters = PlsqlConverters.of(new InheritedTaxIdConverter());

		assertThat(converters.find(null, TaxId.class, null, "x").databaseType()).isEqualTo(String.class);
		assertThat(converters.find(null, TaxId.class, null, "x").elementWise()).isFalse();
		assertThat(converters.find(null, List.class, TaxId.class, "x").elementWise()).isTrue();
		assertThat(converters.find(null, String.class, null, "x")).isNull();
	}

	@Test
	@DisplayName("the annotation wins over the registration")
	void annotation_wins() throws Exception {
		PlsqlConverter<TaxId, Integer> global = PlsqlConverter.of(TaxId.class, Integer.class, c -> 1, n -> null);

		assertThat(PlsqlConverters.of(global).find(annotation("taxId"), TaxId.class, null, "x").databaseType())
				.isEqualTo(String.class);
		assertThat(PlsqlConverters.of(global).find(null, TaxId.class, null, "x").databaseType())
				.isEqualTo(Integer.class);
	}

	@Test
	@DisplayName("a primitive matches the wrapper's converter")
	void primitive() {
		PlsqlConverters converters = PlsqlConverters.of(
				PlsqlConverter.of(Integer.class, String.class, String::valueOf, Integer::valueOf));

		AppliedConverter applied = converters.find(null, int.class, null, "x");

		assertThat(applied.declared()).isEqualTo(int.class);
		assertThat(applied.fromDatabase("7")).isEqualTo(7);
		assertThat(converters.find(null, int[].class, int.class, "x").elementWise()).isTrue();
	}

	@Test
	@DisplayName("of passes null to the function, and ofNullable never calls it")
	void nulls() {
		PlsqlConverter<TaxId, String> of = PlsqlConverter.of(TaxId.class, String.class,
				c -> c == null ? "empty" : c.value(), s -> s == null ? new TaxId("none") : new TaxId(s));
		PlsqlConverter<TaxId, String> ofNullable = PlsqlConverter.ofNullable(TaxId.class, String.class, TaxId::value, TaxId::new);

		assertThat(of.toDatabase(null)).isEqualTo("empty");
		assertThat(of.fromDatabase(null)).isEqualTo(new TaxId("none"));
		assertThat(ofNullable.toDatabase(null)).isNull();
		assertThat(ofNullable.fromDatabase(null)).isNull();
		assertThat(ofNullable.fromDatabase("1")).isEqualTo(new TaxId("1"));
	}

	@Test
	@DisplayName("a converter failure reports the name and the value, including NULL")
	void failure() {
		AppliedConverter applied = PlsqlConverters.of(new TaxIdConverter()).find(null, TaxId.class, null, "x");

		assertThatThrownBy(() -> applied.toDatabase(null))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("TaxIdConverter failed to write NULL")
				.hasCauseInstanceOf(NullPointerException.class);
	}

	@Test
	@DisplayName("a converter returning null for a primitive says what to declare")
	void null_for_primitive() {
		AppliedConverter applied = PlsqlConverters.of(PlsqlConverter.of(Integer.class, String.class,
				String::valueOf, s -> null)).find(null, int.class, null, "x");

		assertThatThrownBy(() -> applied.fromDatabase("1"))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("returned null for a primitive int");
	}

	@Test
	@DisplayName("declaration errors: duplicate, generic type, no constructor and wrong type")
	void declaration_errors() throws Exception {
		assertThatThrownBy(() -> PlsqlConverters.of(new TaxIdConverter(), new InheritedTaxIdConverter()))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("more than one converter");
		assertThatThrownBy(() -> PlsqlConverters.of(new GenericConverter<TaxId>()))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("PlsqlConverter.of");

		PlsqlConvert withoutConstructor = annotation("withoutConstructor");
		assertThatThrownBy(() -> PlsqlConverters.none().find(withoutConstructor, TaxId.class, null, "x"))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("no-arg constructor");

		PlsqlConvert taxId = annotation("taxId");
		assertThatThrownBy(() -> PlsqlConverters.none().find(taxId, String.class, null, "echo"))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("echo: TaxIdConverter converts TaxId, but the value is String");
	}

	record Count(int value) {
	}

	@Test
	@DisplayName("with adds converters, and for the same Java type the added one wins")
	void with_overrides_by_java_type() {
		PlsqlConverters global = PlsqlConverters.of(new TaxIdConverter(),
				PlsqlConverter.ofNullable(Count.class, Integer.class, Count::value, Count::new));
		PlsqlConverters local = PlsqlConverters
				.of(PlsqlConverter.ofNullable(TaxId.class, Long.class, t -> Long.valueOf(t.value()), l -> new TaxId("" + l)));

		PlsqlConverters merged = global.with(local);

		assertThat(merged.find(null, TaxId.class, null, "x").databaseType()).isEqualTo(Long.class);
		assertThat(merged.find(null, Count.class, null, "x").databaseType()).isEqualTo(Integer.class);
		assertThat(global.find(null, TaxId.class, null, "x").databaseType()).isEqualTo(String.class);
		assertThat(merged).hasToString("[Count, TaxId]");
	}
}
