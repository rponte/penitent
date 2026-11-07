package io.github.rponte.penitent.internal.metadata;

import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

import io.github.rponte.penitent.PlsqlOut;

/** What a method parameter declared as {@link PlsqlOut} carries: its {@code T}. */
public final class OutParameters {

	private OutParameters() {
	}

	public static boolean isDeclaredBy(Parameter parameter) {
		return parameter.getType() == PlsqlOut.class;
	}

	/** The declared {@code T}; null for a raw or wildcard {@code PlsqlOut}. */
	public static Type valueTypeOf(Parameter parameter) {
		if (parameter.getParameterizedType() instanceof ParameterizedType parameterized) {
			Type argument = parameterized.getActualTypeArguments()[0];
			return argument instanceof Class<?> || argument instanceof ParameterizedType ? argument : null;
		}
		return null;
	}

	/** The class of {@code T}: {@code List} for {@code PlsqlOut<List<E>>}. */
	public static Class<?> valueClassOf(Parameter parameter) {
		Type type = valueTypeOf(parameter);
		if (type instanceof ParameterizedType parameterized) {
			return (Class<?>) parameterized.getRawType();
		}
		return (Class<?>) type;
	}
}
