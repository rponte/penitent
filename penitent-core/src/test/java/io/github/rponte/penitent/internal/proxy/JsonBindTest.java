package io.github.rponte.penitent.internal.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.internal.metadata.RoutineMetadata.Argument;

/**
 * The bind of a JSON text with the JSON type, by drivers 21 and later, which parse it into OSON while binding (#140).
 * The text was already checked, so what the driver still refuses there is a limit of its own, which becomes a
 * {@code PlsqlMappingException} with its message; any other failure goes as it is. The failures are the ones measured
 * with drivers 21 and 23, played by a statement that throws them.
 */
class JsonBindTest {

	private static final Argument DOC = new Argument(1, "P_DOC", "JSON", "IN", null, null, null, false);

	/** A statement whose setObject throws the failure, or records what was bound when there is none. */
	private static CallableStatement throwing(Throwable failure, List<Object> bound) {
		return (CallableStatement) Proxy.newProxyInstance(JsonBindTest.class.getClassLoader(),
				new Class<?>[] { CallableStatement.class }, (proxy, method, args) -> {
					if (method.getName().equals("setObject")) {
						if (failure != null) {
							throw failure;
						}
						bound.add(args[1]);
						bound.add(args[2]);
						return null;
					}
					throw new UnsupportedOperationException(method.getName());
				});
	}

	@Test
	@DisplayName("the text goes with the JSON type, OracleTypes.JSON, 2016")
	void binds_the_json_type() throws SQLException {
		List<Object> bound = new ArrayList<>();
		RoutineCall.bindJson(throwing(null, bound), 1, "{\"a\":1}", DOC);
		assertThat(bound).containsExactly("{\"a\":1}", 2016);
	}

	@Test
	@DisplayName("driver 23's JSON codes, ORA-26300 to ORA-26399, such as ORA-26338 for a key above 65,535 bytes, become"
			+ " a mapping failure with the driver's message")
	void driver_23_json_codes() {
		SQLException keyTooLong = new SQLException("ORA-26338: The keys of a JSON object may not exceed 65,535 bytes",
				"99999", 26338);
		assertThatThrownBy(() -> RoutineCall.bindJson(throwing(keyTooLong, null), 1, "{}", DOC))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessage("P_DOC is Oracle's JSON type, and the driver refused the text while binding it, at a limit of"
						+ " its own: SQLException: ORA-26338: The keys of a JSON object may not exceed 65,535 bytes")
				.hasCause(keyTooLong);
	}

	@Test
	@DisplayName("driver 21's internal error, ORA-17001, which it gives for every JSON failure, such as a key above 256"
			+ " bytes, becomes a mapping failure")
	void driver_21_internal_error() {
		SQLException keyTooLong = new SQLException("the UTF-8 encoded key length must not be greater than 256 bytes",
				"99999", 17001);
		assertThatThrownBy(() -> RoutineCall.bindJson(throwing(keyTooLong, null), 1, "{}", DOC))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("at a limit of its own: SQLException: the UTF-8 encoded key length must not be"
						+ " greater than 256 bytes");
	}

	@Test
	@DisplayName("a runtime exception of the driver, such as driver 21's bare ArrayIndexOutOfBoundsException at 10,000"
			+ " levels of nesting, or an IllegalArgumentException, becomes a mapping failure instead of escaping")
	void runtime_exceptions() {
		ArrayIndexOutOfBoundsException deep = new ArrayIndexOutOfBoundsException("Index -128 out of bounds for length 256");
		assertThatThrownBy(() -> RoutineCall.bindJson(throwing(deep, null), 1, "[]", DOC))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("at a limit of its own: ArrayIndexOutOfBoundsException: Index -128 out of bounds"
						+ " for length 256")
				.hasCause(deep);
		assertThatThrownBy(() -> RoutineCall.bindJson(throwing(new IllegalArgumentException(), null), 1, "{}", DOC))
				.isInstanceOf(PlsqlMappingException.class);
	}

	@Test
	@DisplayName("any other failure while binding is not the driver's JSON, and goes as it is: a closed statement, say")
	void other_failures_go_as_they_are() {
		SQLException closed = new SQLException("Closed Statement", "99999", 17009);
		assertThatThrownBy(() -> RoutineCall.bindJson(throwing(closed, null), 1, "{}", DOC)).isSameAs(closed);
		assertThatCode(() -> RoutineCall.bindJson(throwing(null, new ArrayList<>()), 1, "{}", DOC))
				.doesNotThrowAnyException();
	}
}
