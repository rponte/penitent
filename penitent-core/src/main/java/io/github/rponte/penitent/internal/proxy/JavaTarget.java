package io.github.rponte.penitent.internal.proxy;

import java.lang.reflect.Method;

import io.github.rponte.penitent.PlsqlBoolean;
import io.github.rponte.penitent.PlsqlOut;
import io.github.rponte.penitent.convert.PlsqlConverters;
import io.github.rponte.penitent.internal.convert.AppliedConverter;
import io.github.rponte.penitent.internal.convert.ScalarCodec.ValueConversion;
import io.github.rponte.penitent.internal.metadata.OutParameters;
import io.github.rponte.penitent.internal.metadata.ReturnType;
import io.github.rponte.penitent.internal.metadata.RoutineMetadata.Argument;
import io.github.rponte.penitent.internal.metadata.RoutineMetadata;

/**
 * Where a value returned by the database lands in Java: the method's return value or a
 * {@link PlsqlOut}. {@code generic} gives the {@code E} of a {@code List<E>}.
 */
record JavaTarget(String description, Class<?> type, java.lang.reflect.Type generic, ValueConversion conversion,
		AppliedConverter converter) {

	static JavaTarget ofReturn(Method method, PlsqlConverters converters) {
		return new JavaTarget(method.getName(), ReturnType.of(method), ReturnType.genericOf(method),
				returnConversion(method), RoutineMetadata.converterOfReturn(method, converters));
	}

	static JavaTarget ofOut(Method method, java.lang.reflect.Parameter parameter, Argument argument,
			PlsqlConverters converters) {
		return new JavaTarget(method.getName() + " (" + argument.name() + ")",
				OutParameters.valueClassOf(parameter), OutParameters.valueTypeOf(parameter),
				ValueConversion.from(parameter.getAnnotation(PlsqlBoolean.class)),
				RoutineMetadata.converterOf(method, parameter, converters));
	}

	/** The literals of a returned boolean: the method's, or the default for the received value. */
	static ValueConversion returnConversion(Method method) {
		return ValueConversion.from(method.getAnnotation(PlsqlBoolean.class));
	}
}
