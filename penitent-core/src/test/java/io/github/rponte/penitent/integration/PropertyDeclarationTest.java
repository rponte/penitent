package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlAttr;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.PlsqlRoutine;
import io.github.rponte.penitent.exception.PlsqlMappingException;

/**
 * How a JavaBean's property declares its Oracle name, with no ambiguity (#189): an annotation where Penitent does not
 * look, or two that disagree, would map a property otherwise than its author wrote, without a word.
 */
class PropertyDeclarationTest extends OracleIntegrationTest {

	/** {@code @PlsqlAttr} on the field says STREET, on the getter NUM: two names for one property. */
	public static class Conflicting {
		@PlsqlAttr("STREET")
		private String street;
		private Integer num;

		@PlsqlAttr("NUM")
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

	/** {@code @PlsqlAttr("STREET")} on the field and on the getter: they agree, and are still two places. */
	public static class Twice {
		@PlsqlAttr("STREET")
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

	/** {@code rua} carries the annotation, but the property is {@code street}, read by {@code getStreet()}. */
	public static class FieldNotAProperty {
		@PlsqlAttr("STREET")
		private String rua;
		private Integer num;

		public String getStreet() {
			return rua;
		}

		public void setStreet(String street) {
			this.rua = street;
		}

		public Integer getNum() {
			return num;
		}

		public void setNum(Integer num) {
			this.num = num;
		}
	}

	/** The superclass's field carries the Oracle name: {@code rua} is STREET. */
	public static class BaseWithAttr {
		@PlsqlAttr("STREET")
		private String rua;

		public String getRua() {
			return rua;
		}

		public void setRua(String rua) {
			this.rua = rua;
		}
	}

	public static class InheritsAttr extends BaseWithAttr {
		private Integer num;

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
		Conflicting conflicting(Conflicting value);

		@PlsqlRoutine("ECHO_ADDRESS")
		InheritsAttr inheritsAttr(InheritsAttr value);

		@PlsqlRoutine("ECHO_ADDRESS")
		Twice twice(Twice value);

		@PlsqlRoutine("ECHO_ADDRESS")
		FieldNotAProperty fieldNotAProperty(FieldNotAProperty value);
	}

	private static Composites composites() {
		return PlsqlPackages.create(Composites.class, DATA_SOURCE);
	}

	@Test
	@DisplayName("@PlsqlAttr on the field and on the getter, with other names: refused, naming both")
	void conflicting_names() {
		Conflicting value = new Conflicting();
		value.setStreet("Street A");
		value.setNum(1);

		assertThatThrownBy(() -> composites().conflicting(value)).isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("Conflicting.street declares @PlsqlAttr in more than one place")
				.hasMessageContaining("the field street").hasMessageContaining("getStreet()");
	}

	@Test
	@DisplayName("@PlsqlAttr on the field and on the getter, agreeing: refused all the same, one place only")
	void agreeing_twice() {
		Twice value = new Twice();
		value.setStreet("Street A");
		value.setNum(1);

		assertThatThrownBy(() -> composites().twice(value)).isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("Twice.street declares @PlsqlAttr in more than one place");
	}

	@Test
	@DisplayName("an annotated field that is no property: refused, not its annotation ignored")
	void annotated_field_not_a_property() {
		FieldNotAProperty value = new FieldNotAProperty();
		value.setStreet("Street A");
		value.setNum(1);

		assertThatThrownBy(() -> composites().fieldNotAProperty(value)).isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("FieldNotAProperty.rua")
				.hasMessageContaining("@PlsqlAttr")
				.hasMessageContaining("no property");
	}

	@Test
	@DisplayName("@PlsqlAttr on a field of the superclass: honored, as on the class's own")
	void inherited_field() {
		InheritsAttr value = new InheritsAttr();
		value.setRua("Street A");
		value.setNum(1);

		InheritsAttr echoed = composites().inheritsAttr(value);

		assertThat(echoed.getRua()).isEqualTo("Street A");
		assertThat(echoed.getNum()).isEqualTo(1);
	}
}
