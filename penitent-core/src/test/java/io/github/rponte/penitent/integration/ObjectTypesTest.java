package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlAttr;
import io.github.rponte.penitent.PlsqlBoolean;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlRoutine;
import io.github.rponte.penitent.PlsqlType;
import io.github.rponte.penitent.exception.PlsqlMappingException;

class ObjectTypesTest extends OracleIntegrationTest {

	enum Status {
		NEW, INACTIVE
	}

	record Address(String street, Integer num) {
	}

	/** The components are in a different order from the attributes, because matching is by name. */
	@PlsqlType("CUSTOMER_T")
	record Customer(
			List<Address> addresses,
			Long id,
			@PlsqlAttr("FULL_NAME") String name,
			Boolean active,
			@PlsqlBoolean(trueValue = "1", falseValue = "0") Boolean vip,
			LocalDate birthday,
			Status status,
			Address address,
			String[] nicknames) {
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

	@PlsqlPackage("FIXTURE_COMPOSITES")
	interface Composites {
		Address echoAddress(Address value);

		@PlsqlRoutine("ECHO_ADDRESS")
		AddressBean echoAddressBean(AddressBean value);

		Customer echoCustomer(Customer value);

		String customerName(Customer value);
	}

	/** All that matters here: who reads and who writes the ACTIVE attribute. */
	record CustomerWithDefault(Long id, String fullName, Boolean active, Integer vip, LocalDate birthday,
			String status, Address address, List<String> nicknames, List<Address> addresses) {
	}

	record CustomerWithSN(Long id, String fullName,
			@PlsqlBoolean(trueValue = "S", falseValue = "N") Boolean active, Integer vip, LocalDate birthday,
			String status, Address address, List<String> nicknames, List<Address> addresses) {
	}

	@PlsqlPackage("FIXTURE_LITERALS")
	interface Literals {
		@PlsqlRoutine("CUSTOMER_ACTIVE")
		String standard(CustomerWithDefault value);

		@PlsqlRoutine("CUSTOMER_ACTIVE")
		String withSN(CustomerWithSN value);

		@PlsqlRoutine("CUSTOMER_WITH_ACTIVE")
		CustomerWithDefault readDefault(String active);

		@PlsqlRoutine("CUSTOMER_WITH_ACTIVE")
		CustomerWithSN leSN(String active);
	}

	/** {@code VIP} is {@code NUMBER(1)}: without an annotation, the data dictionary already says they are 1 and 0. */
	record CustomerVip(Long id, String fullName, String active, Boolean vip, LocalDate birthday,
			String status, Address address, List<String> nicknames, List<Address> addresses) {
	}

	@PlsqlPackage("FIXTURE_COMPOSITES")
	interface WithVip {
		@PlsqlRoutine("ECHO_CUSTOMER")
		CustomerVip echoCustomerVip(CustomerVip value);
	}

	record Base(BigDecimal id) {
	}

	record Child(BigDecimal id, String name) {
	}

	@PlsqlPackage("FIXTURE_SHAPES")
	interface Inheritance {
		Base echoBase(Base value);

		Child echoChild(Child value);

		Base childAsBase();
	}

	private final Composites composites = pkg(Composites.class);

	private static Customer customer(Boolean active, Boolean vip) {
		return new Customer(
				List.of(new Address("Street B", 2), new Address("Street C", 3)),
				7L, "Ann Doe", active, vip, LocalDate.parse("1990-05-20"), Status.NEW,
				new Address("Street A", 1), new String[] { "nests", "lima" });
	}

	@Test
	@DisplayName("record round trip")
	void record_round_trip() {
		assertThat(composites.echoAddress(new Address("Street A", 1))).isEqualTo(new Address("Street A", 1));
	}

	@Test
	@DisplayName("JavaBean round trip")
	void bean_round_trip() {
		AddressBean address = new AddressBean();
		address.setStreet("Street A");
		address.setNum(1);

		AddressBean echo = composites.echoAddressBean(address);

		assertThat(echo.getStreet()).isEqualTo("Street A");
		assertThat(echo.getNum()).isEqualTo(1);
	}

	@Test
	@DisplayName("@PlsqlAttr names the attribute, and the order of the components does not matter")
	void matching_by_name() {
		assertThat(composites.customerName(customer(true, false))).isEqualTo("Ann Doe");
	}

	@Test
	@DisplayName("OBJECT with a nested object, List<T>, T[], enum, date and both boolean formats")
	void full_object() {
		Customer echo = composites.echoCustomer(customer(true, true));

		assertThat(echo.id()).isEqualTo(7L);
		assertThat(echo.name()).isEqualTo("Ann Doe");
		assertThat(echo.active()).isTrue();
		assertThat(echo.vip()).isTrue();
		assertThat(echo.birthday()).isEqualTo("1990-05-20");
		assertThat(echo.status()).isEqualTo(Status.NEW);
		assertThat(echo.address()).isEqualTo(new Address("Street A", 1));
		assertThat(echo.nicknames()).containsExactly("nests", "lima");
		assertThat(echo.addresses()).containsExactly(new Address("Street B", 2), new Address("Street C", 3));
	}

	@Test
	@DisplayName("@PlsqlBoolean decides the literals, and false also comes back as false")
	void boolean_with_custom_literals() {
		Customer echo = composites.echoCustomer(customer(false, false));

		assertThat(echo.active()).isFalse();
		assertThat(echo.vip()).isFalse();
	}

	@Test
	@DisplayName("null: in a scalar, in a nested object and in a collection")
	void nulls() {
		assertThat(composites.echoAddress(new Address(null, null))).isEqualTo(new Address(null, null));

		Customer echo = composites.echoCustomer(new Customer(null, 1L, null, null, null, null, null, null, null));

		assertThat(echo.id()).isEqualTo(1L);
		assertThat(echo.name()).isNull();
		assertThat(echo.address()).isNull();
		assertThat(echo.nicknames()).isNull();
		assertThat(echo.addresses()).isNull();
	}

	@Test
	@DisplayName("null in place of the whole object")
	void whole_object_null() {
		assertThat(composites.echoAddress(null)).isNull();
	}

	@Test
	@DisplayName("OBJECT inheritance: base, subtype and subtype returned as base")
	void inheritance() {
		Inheritance inheritance = pkg(Inheritance.class);

		assertThat(inheritance.echoBase(new Base(BigDecimal.ONE)).id()).isEqualByComparingTo("1");
		assertThat(inheritance.echoChild(new Child(BigDecimal.ONE, "x")).name()).isEqualTo("x");
		assertThat(inheritance.childAsBase().id()).isEqualByComparingTo("1");
	}

	private static CustomerWithDefault withDefault(boolean active) {
		return new CustomerWithDefault(1L, null, active, null, null, null, null, null, null);
	}

	private static CustomerWithSN withSN(boolean active) {
		return new CustomerWithSN(1L, null, active, null, null, null, null, null, null);
	}

	@Test
	@DisplayName("without an annotation, the text attribute uses Y and N")
	void default_literals() {
		Literals literals = pkg(Literals.class);

		assertThat(literals.standard(withDefault(true))).isEqualTo("Y");
		assertThat(literals.standard(withDefault(false))).isEqualTo("N");
		assertThat(literals.readDefault("Y").active()).isTrue();
		assertThat(literals.readDefault("N").active()).isFalse();
	}

	@Test
	@DisplayName("without an annotation, reading an S is an error: the schema uses another literal")
	void s_without_annotation() {
		assertThatThrownBy(() -> pkg(Literals.class).readDefault("S"))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("attribute ACTIVE")
				.hasMessageContaining("@PlsqlBoolean");
	}

	@Test
	@DisplayName("@PlsqlBoolean declares S and N, in both directions")
	void declared_literals() {
		Literals literals = pkg(Literals.class);

		assertThat(literals.withSN(withSN(true))).isEqualTo("S");
		assertThat(literals.withSN(withSN(false))).isEqualTo("N");
		assertThat(literals.leSN("S").active()).isTrue();
		assertThat(literals.leSN("N").active()).isFalse();
	}

	@Test
	@DisplayName("NUMBER attribute as Boolean, without an annotation, in both directions")
	void boolean_in_number_attribute() {

		WithVip withVip = pkg(WithVip.class);

		assertThat(withVip.echoCustomerVip(new CustomerVip(1L, null, null, true, null, null, null, null, null)).vip())
				.isTrue();
		assertThat(withVip.echoCustomerVip(new CustomerVip(1L, null, null, false, null, null, null, null, null)).vip())
				.isFalse();
	}
}
