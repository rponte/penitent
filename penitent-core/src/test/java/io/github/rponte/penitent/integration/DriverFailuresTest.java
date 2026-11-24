package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
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
import io.github.rponte.penitent.exception.PlsqlExecutionException;
import io.github.rponte.penitent.exception.PlsqlMappingException;

/**
 * A runtime exception the Oracle driver throws of its own, outside the call's {@code execute()}, in each phase of a
 * call (#147). JDBC lets a driver report a failure only as a {@code SQLException}, so such a failure is the driver's:
 * <ul>
 * <li>while binding a value, which the driver converts in the client before anything is sent, it is a
 * {@code PlsqlMappingException}, and the connection is kept;</li>
 * <li>anywhere else, reading the results, building a type, writing a LOB, preparing the call or reading the
 * dictionary, it is a {@code PlsqlExecutionException}, never the routine's, and the connection is discarded, since the
 * driver may have failed in the middle of the protocol.</li>
 * </ul>
 * A failure of the application's own code, such as a converter's, is not the driver's, and goes as it is. The driver
 * fails on demand, through {@link FailingDriver}, while everything else runs in the database.
 */
class DriverFailuresTest extends OracleIntegrationTest {

	@PlsqlPackage("FIXTURE_SCALARS")
	interface Scalars {
		BigDecimal echoNumber(BigDecimal pValue);

		void withOut(BigDecimal pA, PlsqlOut<BigDecimal> pB);

		@PlsqlRoutine("ECHO_TEXT")
		String broken(@PlsqlConvert(BrokenConverter.class) String pValue);
	}

	@PlsqlPackage("FIXTURE_COMPOSITES")
	interface Composites {
		OutParametersTest.Address echoAddress(OutParametersTest.Address pValue);

		List<String> echoNames(List<String> pValue);

		List<OutParametersTest.Item> items(int pCount);
	}

	@PlsqlPackage("FIXTURE_LOBS")
	interface Lobs {
		BigDecimal clobLength(String pValue);
	}

	interface Blocks {
		@PlsqlBlock("begin :result := 40 + :n; end;")
		BigDecimal plus(BigDecimal n);
	}

	/** A converter with a bug of its own: the application's failure, not the driver's. */
	public static class BrokenConverter implements PlsqlConverter<String, String> {
		@Override
		public String toDatabase(String value) {
			throw new IllegalStateException("the application's own bug");
		}

		@Override
		public String fromDatabase(String value) {
			return value;
		}
	}

	private final FailingDriver driver = new FailingDriver(DATA_SOURCE);

	private <T> T over(Class<T> type) {
		return PlsqlPackages.create(type, driver.dataSource());
	}

	/** The failure was thrown where the test meant it, and the pool still serves a call afterwards. */
	private void thenHealthy(int aborted) {
		assertThat(driver.fired()).as("the driver failed where the test meant it").isTrue();
		assertThat(driver.aborted()).as("connections discarded").isEqualTo(aborted);
		assertThat(over(Scalars.class).echoNumber(BigDecimal.TEN)).isEqualByComparingTo("10");
	}

	@Test
	@DisplayName("binding a value: a PlsqlMappingException naming the parameter and the driver's failure, and the"
			+ " connection is kept")
	void binding() {
		Scalars scalars = over(Scalars.class);
		scalars.echoNumber(BigDecimal.ONE);
		IllegalArgumentException overflow = new IllegalArgumentException("Overflow");
		driver.failAt("CallableStatement", "set", overflow);

		assertThatThrownBy(() -> scalars.echoNumber(BigDecimal.ONE))
				.isExactlyInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("P_VALUE of PLSQL_TEST.FIXTURE_SCALARS.ECHO_NUMBER: the driver refused it while"
						+ " binding it, in CallableStatement.set")
				.hasMessageEndingWith(", at a limit of its own: IllegalArgumentException: Overflow")
				.hasCause(overflow);
		thenHealthy(0);
	}

	@Test
	@DisplayName("registering an OUT: a PlsqlMappingException, and the connection is kept")
	void registering_an_out() {
		Scalars scalars = over(Scalars.class);
		scalars.withOut(BigDecimal.ONE, new PlsqlOut<>());
		AssertionError failed = new AssertionError("an internal check");
		driver.failAt("CallableStatement", "registerOutParameter", failed);

		assertThatThrownBy(() -> scalars.withOut(BigDecimal.ONE, new PlsqlOut<>()))
				.isExactlyInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("P_B of PLSQL_TEST.FIXTURE_SCALARS.WITH_OUT: the driver refused it while binding"
						+ " it, in CallableStatement.registerOutParameter, at a limit of its own: AssertionError: an"
						+ " internal check")
				.hasCause(failed);
		thenHealthy(0);
	}

	@Test
	@DisplayName("reading the return value: a PlsqlExecutionException, the driver's, and the connection is discarded")
	void reading_the_return_value() {
		Scalars scalars = over(Scalars.class);
		scalars.echoNumber(BigDecimal.ONE);
		ArrayIndexOutOfBoundsException broken = new ArrayIndexOutOfBoundsException("Index 3 out of bounds for length 3");
		driver.failReadingAt("CallableStatement", "get", broken);

		assertThatThrownBy(() -> scalars.echoNumber(BigDecimal.ONE))
				.isExactlyInstanceOf(PlsqlExecutionException.class)
				.hasMessageContaining("PLSQL_TEST.FIXTURE_SCALARS.ECHO_NUMBER: the Oracle driver failed itself in"
						+ " CallableStatement.get")
				.hasMessageContaining("(ArrayIndexOutOfBoundsException: Index 3 out of bounds for length 3). The"
						+ " connection was discarded")
				.hasRootCause(broken);
		thenHealthy(1);
	}

	@Test
	@DisplayName("reading an OUT: a PlsqlExecutionException, and the connection is discarded")
	void reading_an_out() {
		Scalars scalars = over(Scalars.class);
		scalars.withOut(BigDecimal.ONE, new PlsqlOut<>());
		driver.failReadingAt("CallableStatement", "get", new IllegalStateException());

		assertThatThrownBy(() -> scalars.withOut(BigDecimal.ONE, new PlsqlOut<>()))
				.isExactlyInstanceOf(PlsqlExecutionException.class)
				.hasMessageContaining("the Oracle driver failed itself in CallableStatement.get")
				.hasRootCauseInstanceOf(IllegalStateException.class);
		thenHealthy(1);
	}

	@Test
	@DisplayName("reading a cursor's rows: a PlsqlExecutionException, and the connection is discarded")
	void reading_a_cursor() {
		Composites composites = over(Composites.class);
		composites.items(2);
		driver.failReadingAt("ResultSet", "next", new ArrayIndexOutOfBoundsException(-128));

		assertThatThrownBy(() -> composites.items(2))
				.isExactlyInstanceOf(PlsqlExecutionException.class)
				.hasMessageContaining("PLSQL_TEST.FIXTURE_COMPOSITES.ITEMS: the Oracle driver failed itself in"
						+ " ResultSet.next")
				.hasRootCauseInstanceOf(ArrayIndexOutOfBoundsException.class);
		thenHealthy(1);
	}

	@Test
	@DisplayName("reading an object type's attributes: a PlsqlExecutionException, and the connection is discarded")
	void reading_a_struct() {
		Composites composites = over(Composites.class);
		OutParametersTest.Address address = new OutParametersTest.Address("Main", 1);
		composites.echoAddress(address);
		// the first read after the call is the log's, of the value received, which ignores a failure
		driver.failReadingAt("Struct", "getAttributes", new NullPointerException(), 1);

		assertThatThrownBy(() -> composites.echoAddress(address))
				.isExactlyInstanceOf(PlsqlExecutionException.class)
				.hasMessageContaining("the Oracle driver failed itself in Struct.getAttributes")
				.hasRootCauseInstanceOf(NullPointerException.class);
		thenHealthy(1);
	}

	@Test
	@DisplayName("reading a collection's elements: a PlsqlExecutionException, and the connection is discarded")
	void reading_an_array() {
		Composites composites = over(Composites.class);
		composites.echoNames(List.of("a"));
		driver.failReadingAt("Array", "getArray", new ClassCastException());

		assertThatThrownBy(() -> composites.echoNames(List.of("a")))
				.isExactlyInstanceOf(PlsqlExecutionException.class)
				.hasMessageContaining("the Oracle driver failed itself in Array.getArray")
				.hasRootCauseInstanceOf(ClassCastException.class);
		thenHealthy(1);
	}

	@Test
	@DisplayName("building an object type to send: a PlsqlMappingException naming the type and its attributes, as"
			+ " before for an IllegalArgumentException, now for any runtime exception of the driver; the connection is"
			+ " kept, since the driver converts the values in the client")
	void building_a_struct() {
		Composites composites = over(Composites.class);
		OutParametersTest.Address address = new OutParametersTest.Address("Main", 1);
		composites.echoAddress(address);
		ArrayIndexOutOfBoundsException broken = new ArrayIndexOutOfBoundsException("Index 9");
		driver.failAt("Connection", "createStruct", broken);

		assertThatThrownBy(() -> composites.echoAddress(address))
				.isExactlyInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("a value does not fit in PLSQL_TEST.ADDRESS_T: Index 9. Attributes: [STREET=String,"
						+ " NUM=Integer]")
				.hasCause(broken);
		thenHealthy(0);
	}

	@Test
	@DisplayName("building a collection to send, through the driver's own connection: a PlsqlMappingException, as"
			+ " before, now for any runtime exception of the driver; the connection is kept")
	void building_an_array() {
		Composites composites = over(Composites.class);
		composites.echoNames(List.of("a"));
		driver.failAt("OracleConnection", "createOracleArray", new AssertionError("check"));

		assertThatThrownBy(() -> composites.echoNames(List.of("a")))
				.isExactlyInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("an element does not fit in PLSQL_TEST.NAME_LIST_T: check")
				.hasRootCauseInstanceOf(AssertionError.class);
		thenHealthy(0);
	}

	@Test
	@DisplayName("writing a temporary LOB, a round trip: a PlsqlExecutionException, and the connection is discarded")
	void writing_a_lob() {
		Lobs lobs = over(Lobs.class);
		String large = "x".repeat(40_000);
		lobs.clobLength(large);
		driver.failAt("Clob", "setString", new IllegalStateException("lob"));

		assertThatThrownBy(() -> lobs.clobLength(large))
				.isExactlyInstanceOf(PlsqlExecutionException.class)
				.hasMessageContaining("P_VALUE of PLSQL_TEST.FIXTURE_LOBS.CLOB_LENGTH: the Oracle driver failed itself in"
						+ " Clob.setString");
		thenHealthy(1);
	}

	@Test
	@DisplayName("freeing a temporary LOB after the call does not hide the call's result")
	void freeing_a_lob() {
		Lobs lobs = over(Lobs.class);
		String large = "x".repeat(40_000);
		lobs.clobLength(large);
		driver.failAt("Clob", "free", new IllegalStateException("free"));

		assertThat(lobs.clobLength(large)).isEqualByComparingTo("40000");
		thenHealthy(0);
	}

	@Test
	@DisplayName("preparing the call: a PlsqlExecutionException, and the connection is discarded")
	void preparing() {
		Scalars scalars = over(Scalars.class);
		scalars.echoNumber(BigDecimal.ONE);
		driver.failAt("Connection", "prepareCall", new IllegalStateException("prepare"));

		assertThatThrownBy(() -> scalars.echoNumber(BigDecimal.ONE))
				.isExactlyInstanceOf(PlsqlExecutionException.class)
				.hasMessageContaining("PLSQL_TEST.FIXTURE_SCALARS.ECHO_NUMBER: the Oracle driver failed itself in"
						+ " Connection.prepareCall");
		thenHealthy(1);
	}

	@Test
	@DisplayName("closing the statement: a PlsqlExecutionException, and the connection is discarded")
	void closing() {
		Scalars scalars = over(Scalars.class);
		scalars.echoNumber(BigDecimal.ONE);
		driver.failAt("CallableStatement", "close", new IllegalStateException("close"));

		assertThatThrownBy(() -> scalars.echoNumber(BigDecimal.ONE))
				.isExactlyInstanceOf(PlsqlExecutionException.class)
				.hasMessageContaining("the Oracle driver failed itself in CallableStatement.close");
		thenHealthy(1);
	}

	@Test
	@DisplayName("running the call, as since #137: a PlsqlExecutionException, and the connection is discarded")
	void executing() {
		Scalars scalars = over(Scalars.class);
		scalars.echoNumber(BigDecimal.ONE);
		AssertionError failed = new AssertionError("oacdty: 0");
		driver.failAt("CallableStatement", "execute", failed);

		assertThatThrownBy(() -> scalars.echoNumber(BigDecimal.ONE))
				.isExactlyInstanceOf(PlsqlExecutionException.class)
				.hasMessageContaining("PLSQL_TEST.FIXTURE_SCALARS.ECHO_NUMBER: the Oracle driver failed an internal check"
						+ " (oacdty: 0)")
				.hasRootCause(failed);
		thenHealthy(1);
	}

	@Test
	@DisplayName("reading the dictionary, to describe the routine: a PlsqlExecutionException, and the connection is"
			+ " discarded")
	void reading_the_dictionary() {
		Scalars scalars = over(Scalars.class);
		driver.failAt("PreparedStatement", "executeQuery", new IllegalStateException("dictionary"));

		assertThatThrownBy(() -> scalars.echoNumber(BigDecimal.ONE))
				.isExactlyInstanceOf(PlsqlExecutionException.class)
				.hasMessageContaining("reading the dictionary: the Oracle driver failed itself in"
						+ " PreparedStatement.executeQuery");
		thenHealthy(1);
	}

	@Test
	@DisplayName("a block: reading its result is the driver's failure, and binding its parameter a mapping one")
	void a_block() {
		Blocks blocks = over(Blocks.class);
		blocks.plus(BigDecimal.ONE);
		driver.failReadingAt("CallableStatement", "get", new IllegalStateException("result"));
		assertThatThrownBy(() -> blocks.plus(BigDecimal.ONE))
				.isExactlyInstanceOf(PlsqlExecutionException.class)
				.hasMessageContaining("the block of Blocks.plus: the Oracle driver failed itself in"
						+ " CallableStatement.get");
		thenHealthy(1);

		driver.failAt("CallableStatement", "set", new IllegalArgumentException("Overflow"));
		assertThatThrownBy(() -> blocks.plus(BigDecimal.ONE))
				.isExactlyInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining(":n: the driver refused it while binding it, in CallableStatement.set");
		thenHealthy(1);
	}

	@Test
	@DisplayName("an Error that is not the driver's to explain, such as a StackOverflowError, goes as it is")
	void other_errors() {
		Scalars scalars = over(Scalars.class);
		scalars.echoNumber(BigDecimal.ONE);
		StackOverflowError deep = new StackOverflowError();
		driver.failReadingAt("CallableStatement", "get", deep);

		assertThatThrownBy(() -> scalars.echoNumber(BigDecimal.ONE)).isSameAs(deep);
	}

	@Test
	@DisplayName("a converter's own failure is the application's, not the driver's: a PlsqlMappingException naming the"
			+ " parameter, as before, and the connection is kept")
	void a_converters_failure() {
		Scalars scalars = over(Scalars.class);
		assertThatThrownBy(() -> scalars.broken("a"))
				.isExactlyInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("P_VALUE of PLSQL_TEST.FIXTURE_SCALARS.ECHO_TEXT")
				.hasMessageNotContaining("driver")
				.hasRootCauseMessage("the application's own bug");
		assertThat(driver.aborted()).isZero();
		assertThat(scalars.echoNumber(BigDecimal.TEN)).isEqualByComparingTo("10");
	}

	@Test
	@DisplayName("a failure of the driver while the call's log reads a value it sends is ignored, as before: the log"
			+ " never fails a call")
	void logging() {
		Composites composites = over(Composites.class);
		OutParametersTest.Address address = new OutParametersTest.Address("Main", 1);
		composites.echoAddress(address);
		// the first getAttributes of the call is the log's, before the call runs
		driver.failAt("Struct", "getAttributes", new IllegalStateException("log"));

		assertThat(composites.echoAddress(address)).isEqualTo(address);
		thenHealthy(0);
	}
}
