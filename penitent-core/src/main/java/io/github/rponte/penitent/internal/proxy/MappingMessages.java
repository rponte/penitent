package io.github.rponte.penitent.internal.proxy;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.stream.Collectors;

import io.github.rponte.penitent.exception.PlsqlMappingException;

/**
 * The one shape of a mapping error of a method: {@code method(types): detail}. The parameter types
 * tell overloads apart; the detail, written where the error is found, says what was looked up and
 * what exists, without naming the method again.
 */
final class MappingMessages {

	private MappingMessages() {
	}

	/** {@code placeOrder(long, BigDecimal, PlsqlOut)}: the name and the simple names of the parameter types. */
	static String signature(Method method) {
		return method.getName() + Arrays.stream(method.getParameterTypes()).map(Class::getSimpleName)
				.collect(Collectors.joining(", ", "(", ")"));
	}

	/**
	 * {@code error}, with the method in front, once. It replaces {@code error} rather than wrapping it:
	 * same cause, same stack trace, same suppressed exceptions.
	 */
	static PlsqlMappingException of(Method method, PlsqlMappingException error) {
		String prefix = signature(method) + ": ";
		if (error.getMessage() != null && error.getMessage().startsWith(prefix)) {
			return error;
		}
		PlsqlMappingException prefixed = new PlsqlMappingException(prefix + error.getMessage(), error.getCause());
		prefixed.setStackTrace(error.getStackTrace());
		for (Throwable suppressed : error.getSuppressed()) {
			prefixed.addSuppressed(suppressed);
		}
		return prefixed;
	}
}
