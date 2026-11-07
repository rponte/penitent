package io.github.rponte.penitent.internal.metadata;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Optional;

import io.github.rponte.penitent.exception.PlsqlMappingException;

/**
 * The type a method returns, as the rest of the library sees it: an {@code Optional<T>} is a
 * {@code T} that may be absent, so everything that validates, binds or reads the return value looks at
 * {@code T}, and only the proxy wraps it at the end.
 */
public final class ReturnType {

	private ReturnType() {
	}

	static boolean isOptional(Method method) {
		return method.getReturnType() == Optional.class;
	}

	public static Class<?> of(Method method) {
		if (!isOptional(method)) {
			return method.getReturnType();
		}
		Type element = element(method);
		if (element instanceof Class<?> type) {
			return type;
		}
		return (Class<?>) ((ParameterizedType) element).getRawType();
	}

	public static Type genericOf(Method method) {
		return isOptional(method) ? element(method) : method.getGenericReturnType();
	}

	private static Type element(Method method) {
		if (method.getGenericReturnType() instanceof ParameterizedType optional
				&& (optional.getActualTypeArguments()[0] instanceof Class<?>
						|| optional.getActualTypeArguments()[0] instanceof ParameterizedType)) {
			return optional.getActualTypeArguments()[0];
		}
		throw new PlsqlMappingException(method.getName() + " returns Optional without a concrete type;"
				+ " declare Optional<SomeType>");
	}

	/** {@code Optional} is a return type only: a parameter that may be absent is just {@code null}. */
	public static void requireNoOptionalParameter(Method method) {
		for (java.lang.reflect.Parameter parameter : method.getParameters()) {
			if (parameter.getType() == Optional.class) {
				throw new PlsqlMappingException(method.getName() + ": parameter " + parameter.getName()
						+ " is Optional, which is only supported as a return type; pass the value or null");
			}
		}
	}

	public static Object wrap(Method method, Object result) {
		return isOptional(method) ? Optional.ofNullable(result) : result;
	}
}
