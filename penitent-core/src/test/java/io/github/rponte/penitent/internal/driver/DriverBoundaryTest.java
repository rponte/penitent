package io.github.rponte.penitent.internal.driver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Struct;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.exception.PlsqlFailure;
import io.github.rponte.penitent.exception.PlsqlMappingException;

/**
 * The boundary at the driver's objects (#147), over fake driver objects: what it wraps, what it hands the driver back,
 * and how a runtime exception of the driver becomes a {@link DriverFailure}. {@code DriverFailuresTest} runs it against
 * the database.
 */
class DriverBoundaryTest {

	private final List<String> calls = new ArrayList<>();
	private final List<Object[]> arguments = new ArrayList<>();

	/**
	 * A fake driver object, a JDK proxy as a pool's or a framework's would be: each method answers from {@code answer},
	 * which may be a failure to throw, and records its name and arguments.
	 */
	private <T> T fake(Class<T> type, Function<String, Object> answer) {
		return type.cast(Proxy.newProxyInstance(DriverBoundaryTest.class.getClassLoader(), new Class<?>[] { type },
				(proxy, method, args) -> {
					if (method.getDeclaringClass() == Object.class) {
						return switch (method.getName()) {
							case "equals" -> proxy == args[0];
							case "hashCode" -> 7;
							default -> "fake " + type.getSimpleName();
						};
					}
					calls.add(type.getSimpleName() + "." + method.getName());
					arguments.add(args);
					Object result = answer.apply(method.getName());
					if (result instanceof Throwable failure) {
						throw failure;
					}
					return result;
				}));
	}

	/** The statement a wrapped connection over {@code statement} prepares: behind the boundary. */
	private CallableStatement behind(CallableStatement statement, Connection[] aborted) throws SQLException {
		Connection connection = fake(Connection.class, m -> switch (m) {
			case "prepareCall" -> statement;
			case "abort" -> {
				aborted[0] = null;
				yield null;
			}
			default -> null;
		});
		aborted[0] = connection;
		return DriverBoundary.wrap(connection).prepareCall("{call p}");
	}

	@Test
	@DisplayName("a statement the driver hands back is behind the boundary, even when someone else's JDK proxy, such as"
			+ " a pool's; the boundary's own proxy is not wrapped twice, and real() gives the driver's object")
	void wraps_what_the_driver_hands_back() throws SQLException {
		CallableStatement statement = fake(CallableStatement.class, m -> null);
		Connection connection = DriverBoundary.wrap(fake(Connection.class, m -> statement));

		CallableStatement wrapped = connection.prepareCall("{call p}");
		assertThat(wrapped).isNotSameAs(statement);
		assertThat(DriverBoundary.real(wrapped)).isSameAs(statement);
		assertThat(DriverBoundary.wrap(connection)).isSameAs(connection);
		assertThat(DriverBoundary.real("a value")).isEqualTo("a value");
	}

	@Test
	@DisplayName("a runtime exception or an AssertionError of the driver becomes a DriverFailure naming the operation,"
			+ " with the original as its cause; a SQLException and any other Error go as they are")
	void marks_the_drivers_failures() throws SQLException {
		IllegalArgumentException overflow = new IllegalArgumentException("Overflow");
		AssertionError check = new AssertionError("check");
		SQLException database = new SQLException("ORA-00001");
		StackOverflowError deep = new StackOverflowError();
		CallableStatement wrapped = behind(fake(CallableStatement.class, m -> switch (m) {
			case "setObject" -> overflow;
			case "registerOutParameter" -> check;
			case "execute" -> database;
			default -> deep;
		}), new Connection[1]);

		assertThatThrownBy(() -> wrapped.setObject(1, 1))
				.isInstanceOfSatisfying(DriverFailure.class, e -> {
					assertThat(e.operation()).isEqualTo("CallableStatement.setObject");
					assertThat(e.inBinding()).isTrue();
					assertThat(e.described()).isEqualTo("IllegalArgumentException: Overflow");
				})
				.hasCause(overflow);
		assertThatThrownBy(() -> wrapped.registerOutParameter(1, 2)).isInstanceOf(DriverFailure.class).hasCause(check);
		assertThatThrownBy(wrapped::execute).isSameAs(database);
		assertThatThrownBy(wrapped::getResultSet).isSameAs(deep);
	}

	@Test
	@DisplayName("binding is a statement's setter, registerOutParameter, or building a Struct or an Array; anything else"
			+ " is not, such as reading, preparing, closing or writing a LOB")
	void in_binding() {
		IllegalStateException cause = new IllegalStateException();
		for (String operation : List.of("CallableStatement.setBigDecimal", "PreparedStatement.setObject",
				"CallableStatement.registerOutParameter", "Connection.createStruct", "OracleConnection.createOracleArray",
				"Connection.createArrayOf")) {
			assertThat(new DriverFailure(operation, cause, null).inBinding()).as(operation).isTrue();
		}
		for (String operation : List.of("CallableStatement.getObject", "ResultSet.next", "Struct.getAttributes",
				"Connection.prepareCall", "CallableStatement.close", "Clob.setString", "CallableStatement.execute")) {
			assertThat(new DriverFailure(operation, cause, null).inBinding()).as(operation).isFalse();
		}
	}

	@Test
	@DisplayName("a binding failure is a PlsqlMappingException, and keeps the connection; a call's is the driver's own"
			+ " SQLException in its phase, and aborts the connection")
	void translations() throws SQLException {
		Connection[] connection = new Connection[1];
		IllegalArgumentException overflow = new IllegalArgumentException("Overflow");
		CallableStatement wrapped = behind(fake(CallableStatement.class, m -> overflow), connection);

		DriverFailure binding = catchFailure(() -> wrapped.setObject(1, 1));
		PlsqlMappingException mapping = binding.ofBinding("P_VALUE of S.PKG.R");
		assertThat(mapping).hasMessage("P_VALUE of S.PKG.R: the driver refused it while binding it, in"
				+ " CallableStatement.setObject, at a limit of its own: IllegalArgumentException: Overflow")
				.hasCause(overflow);
		assertThat(connection[0]).as("not aborted").isNotNull();

		DriverFailure reading = catchFailure(() -> wrapped.getObject(1));
		PlsqlFailure failure = reading.ofCall("{call p}", PlsqlFailure.Phase.RESULT).failure();
		assertThat(failure.phase()).isEqualTo(PlsqlFailure.Phase.RESULT);
		assertThat((Throwable) failure.cause()).hasMessage("the Oracle driver failed itself in CallableStatement.getObject"
				+ " (IllegalArgumentException: Overflow). The connection was discarded").hasCause(overflow);
		assertThat(connection[0]).as("aborted").isNull();
	}

	@Test
	@DisplayName("a failure with no message is described by its class alone")
	void no_message() {
		assertThat(new DriverFailure("ResultSet.next", new NullPointerException(), null).described())
				.isEqualTo("NullPointerException");
	}

	@Test
	@DisplayName("the driver gets its own objects back: a proxy given as an argument is unwrapped, and so are those in"
			+ " an Object[], such as a Struct's attributes, in a copy")
	void hands_the_driver_its_own_objects() throws SQLException {
		Struct real = fake(Struct.class, m -> null);
		CallableStatement statement = fake(CallableStatement.class, m -> m.equals("getObject") ? real : null);
		CallableStatement wrapped = behind(statement, new Connection[1]);
		Struct struct = (Struct) wrapped.getObject(1);
		assertThat(struct).isNotSameAs(real);

		Object[] attributes = { "street", struct };
		wrapped.setObject(1, struct);
		wrapped.setObject(2, attributes);

		assertThat(arguments.get(arguments.size() - 2)[1]).isSameAs(real);
		Object[] given = (Object[]) arguments.get(arguments.size() - 1)[1];
		assertThat(given).containsExactly("street", real).isNotSameAs(attributes);
		assertThat(attributes[1]).as("the caller's array is untouched").isSameAs(struct);
	}

	@Test
	@DisplayName("an Object[] the driver hands back has its JDBC objects wrapped, in a copy: the driver's own array,"
			+ " which it may keep, never holds a proxy")
	void wraps_the_elements_of_an_array() throws SQLException {
		Struct nested = fake(Struct.class, m -> null);
		Object[] driversOwn = { "street", nested };
		Struct struct = fake(Struct.class, m -> m.equals("getAttributes") ? driversOwn : null);
		CallableStatement wrapped = behind(fake(CallableStatement.class, m -> struct), new Connection[1]);

		Object[] attributes = ((Struct) wrapped.getObject(1)).getAttributes();
		assertThat(attributes).isNotSameAs(driversOwn);
		assertThat(attributes[0]).isEqualTo("street");
		assertThat(DriverBoundary.real(attributes[1])).isSameAs(nested).isNotSameAs(attributes[1]);
		assertThat(driversOwn[1]).isSameAs(nested);
	}

	@Test
	@DisplayName("unwrap gives the driver's own interface behind the boundary too, so a call on it is guarded")
	void unwrap() throws SQLException {
		IllegalStateException broken = new IllegalStateException("broken");
		CallableStatement inner = fake(CallableStatement.class, m -> broken);
		CallableStatement wrapped = behind(fake(CallableStatement.class, m -> m.equals("unwrap") ? inner : null),
				new Connection[1]);

		CallableStatement unwrapped = wrapped.unwrap(CallableStatement.class);
		assertThat(DriverBoundary.real(unwrapped)).isSameAs(inner);
		assertThatThrownBy(unwrapped::execute).isInstanceOf(DriverFailure.class).hasCause(broken);
	}

	@Test
	@DisplayName("equals and hashCode are the driver object's, and a proxy equals the object it stands for")
	void equality() throws SQLException {
		CallableStatement statement = fake(CallableStatement.class, m -> null);
		CallableStatement wrapped = behind(statement, new Connection[1]);
		assertThat(wrapped.hashCode()).isEqualTo(7);
		assertThat(wrapped.equals(wrapped)).isTrue();
		assertThat(wrapped).hasToString("fake CallableStatement");
	}

	@Test
	@DisplayName("guarded marks a call on the driver's value classes as the boundary does, and lets a SQLException and"
			+ " a DriverFailure through")
	void guarded() throws SQLException {
		IllegalStateException broken = new IllegalStateException("datum");
		assertThatThrownBy(() -> DriverBoundary.guarded("STRUCT.getOracleAttributes", null, () -> {
			throw broken;
		})).isInstanceOfSatisfying(DriverFailure.class,
				e -> assertThat(e.operation()).isEqualTo("STRUCT.getOracleAttributes")).hasCause(broken);
		SQLException database = new SQLException("ORA-00001");
		assertThatThrownBy(() -> DriverBoundary.guarded("op", null, () -> {
			throw database;
		})).isSameAs(database);
		assertThat(DriverBoundary.guarded("op", null, () -> "value")).isEqualTo("value");
	}

	private static DriverFailure catchFailure(ThrowingCall call) {
		try {
			call.run();
		} catch (DriverFailure e) {
			return e;
		} catch (SQLException e) {
			throw new AssertionError(e);
		}
		throw new AssertionError("no failure");
	}

	@FunctionalInterface
	private interface ThrowingCall {
		void run() throws SQLException;
	}

	/** Another library's interface, as the mocks of penitent-test have the core reach through unwrap. */
	interface Integration {
		String name();
	}

	@Test
	@DisplayName("unwrap to another library's interface, not JDBC's nor the driver's, gives it as it is: it is not the"
			+ " driver, and it may not be public")
	void unwrap_to_another_library() throws SQLException {
		Integration integration = () -> "the mocks";
		Connection connection = DriverBoundary.wrap(fake(Connection.class, m -> integration));
		assertThat(connection.unwrap(Integration.class)).isSameAs(integration);
	}
}
