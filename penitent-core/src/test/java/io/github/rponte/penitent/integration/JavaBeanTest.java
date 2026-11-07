package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlAttr;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlRoutine;
import io.github.rponte.penitent.exception.PlsqlMappingException;

/** The JavaBean and record variations in the mapping of {@code ADDRESS_T}. */
class JavaBeanTest extends OracleIntegrationTest {

	record Address(String street, Integer num) {
	}

	/** The second name is the one that exists in the database. */
	record WithAlternativeName(@PlsqlAttr({ "DOES_NOT_EXIST", "STREET" }) String street, Integer num) {
	}

	record ConstructorThatThrows(String street, Integer num) {
		ConstructorThatThrows {
			throw new IllegalStateException("constructor rule");

		}
	}

	public static class WithoutDefaultConstructor {
		public WithoutDefaultConstructor(int ignored) {
		}

		public String getStreet() {
			return "Street A";
		}

		public Integer getNum() {
			return 1;
		}
	}

	public static class WithoutSetter {
		public String getStreet() {
			return "value do getter";
		}

		public Integer getNum() {
			return 1;
		}
	}

	public static class Base {
		private String street;

		public String getStreet() {
			return street;
		}

		public void setStreet(String street) {
			this.street = street;
		}
	}

	public static class Inherited extends Base {
		private Integer num;

		public Integer getNum() {
			return num;
		}

		public void setNum(Integer num) {
			this.num = num;
		}
	}

	/** {@code getComputed} does not match any attribute of the type. */
	public static class WithExtraProperty extends Inherited {
		public String getComputed() {
			return "x";
		}
	}

	public static class AttrOnField {
		@PlsqlAttr("STREET")
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

	public static class AttrOnGetter {
		private String street;
		private Integer num;

		@PlsqlAttr("STREET")
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

	public static class AttrOnSetter {
		private String street;
		private Integer num;

		public String getStreet() {
			return street;
		}

		@PlsqlAttr("STREET")
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

	@PlsqlPackage("FIXTURE_COMPOSITES")
	interface Composites {
		@PlsqlRoutine("ECHO_ADDRESS")
		WithAlternativeName withAlternativeName(WithAlternativeName value);

		@PlsqlRoutine("ECHO_ADDRESS")
		ConstructorThatThrows constructorThatThrows(Address value);

		@PlsqlRoutine("ECHO_ADDRESS")
		WithoutDefaultConstructor readWithoutDefaultConstructor(Address value);

		@PlsqlRoutine("ECHO_ADDRESS")
		Address writeWithoutDefaultConstructor(WithoutDefaultConstructor value);

		@PlsqlRoutine("ECHO_ADDRESS")
		WithoutSetter readWithoutSetter(Address value);

		@PlsqlRoutine("ECHO_ADDRESS")
		Inherited inherited(Inherited value);

		@PlsqlRoutine("ECHO_ADDRESS")
		WithExtraProperty withExtraProperty(WithExtraProperty value);

		@PlsqlRoutine("ECHO_ADDRESS")
		AttrOnField attrOnField(AttrOnField value);

		@PlsqlRoutine("ECHO_ADDRESS")
		AttrOnGetter attrOnGetter(AttrOnGetter value);

		@PlsqlRoutine("ECHO_ADDRESS")
		AttrOnSetter attrOnSetter(AttrOnSetter value);

		@PlsqlRoutine("ECHO_ADDRESS")
		Address stringInsteadOfObject(String value);
	}

	private final Composites composites = pkg(Composites.class);

	@Test
	@DisplayName("@PlsqlAttr with several names uses the one that exists in the type")
	void alternative_name() {
		assertThat(composites.withAlternativeName(new WithAlternativeName("Street A", 1)).street()).isEqualTo("Street A");
	}

	@Test
	@DisplayName("a record constructor that throws becomes PlsqlMappingException")
	void constructor_that_throws() {
		assertThatThrownBy(() -> composites.constructorThatThrows(new Address("Street A", 1)))
				.isInstanceOf(PlsqlMappingException.class);
	}

	@Test
	@DisplayName("a bean without a no-arg constructor cannot be read, and the error says why")
	void no_default_constructor_on_read() {
		assertThatThrownBy(() -> composites.readWithoutDefaultConstructor(new Address("Street A", 1)))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("no-arg constructor");
	}

	@Test
	@DisplayName("a bean without a no-arg constructor can be written, which only uses the getters")
	void no_default_constructor_on_write() {
		assertThat(composites.writeWithoutDefaultConstructor(new WithoutDefaultConstructor(0)))
				.isEqualTo(new Address("Street A", 1));
	}

	@Test
	@DisplayName("a bean without a setter should fail on read, instead of discarding the value")
	void no_setter_on_read() {
		assertThatThrownBy(() -> composites.readWithoutSetter(new Address("Street Z", 9)))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("WithoutSetter.street")
				.hasMessageContaining("has no setter");
	}

	@Test
	@DisplayName("inherited properties count")
	void inherited_properties() {
		Inherited value = new Inherited();
		value.setStreet("Street A");
		value.setNum(1);

		Inherited echo = composites.inherited(value);

		assertThat(echo.getStreet()).isEqualTo("Street A");
		assertThat(echo.getNum()).isEqualTo(1);
	}

	@Test
	@DisplayName("a property without a matching attribute is ignored")
	void extra_property() {
		WithExtraProperty value = new WithExtraProperty();
		value.setStreet("Street A");
		value.setNum(1);

		assertThat(composites.withExtraProperty(value).getStreet()).isEqualTo("Street A");
	}

	@Test
	@DisplayName("@PlsqlAttr works on the field, the getter and the setter")
	void attr_anywhere() {
		AttrOnField onField = new AttrOnField();
		onField.setStreet("Street A");
		onField.setNum(1);
		AttrOnGetter noGetter = new AttrOnGetter();
		noGetter.setStreet("Street B");
		noGetter.setNum(2);
		AttrOnSetter noSetter = new AttrOnSetter();
		noSetter.setStreet("Street C");
		noSetter.setNum(3);

		assertThat(composites.attrOnField(onField).getStreet()).isEqualTo("Street A");
		assertThat(composites.attrOnGetter(noGetter).getStreet()).isEqualTo("Street B");
		assertThat(composites.attrOnSetter(noSetter).getStreet()).isEqualTo("Street C");
	}

	@Test
	@DisplayName("a String where the database expects an OBJECT is a mapping error")
	void wrong_type_in_parameter() {
		assertThatThrownBy(() -> composites.stringInsteadOfObject("Street A"))
				.isInstanceOf(PlsqlMappingException.class);
	}
}
