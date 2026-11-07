package io.github.rponte.penitent.convert;

import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.internal.convert.AppliedConverter;
import io.github.rponte.penitent.internal.convert.ConverterLookup;
import io.github.rponte.penitent.internal.convert.DeclaredTypes;

/**
 * The converters of an interface, one per Java type: they apply everywhere the type appears, unless
 * {@link PlsqlConvert} says otherwise.
 */
public final class PlsqlConverters {

	private static final PlsqlConverters NONE = new PlsqlConverters(Map.of());

	static {
		ConverterLookup.install(new ConverterLookup.Access() {

			@Override
			public PlsqlConverters none() {
				return NONE;
			}

			@Override
			public AppliedConverter find(PlsqlConverters converters, PlsqlConvert annotation, Class<?> declared,
					Class<?> element, String where) {
				return converters.find(annotation, declared, element, where);
			}
		});
	}

	private final Map<Class<?>, Typed> byJavaType;
	private final Map<Class<?>, Typed> annotated = new ConcurrentHashMap<>();

	private PlsqlConverters(Map<Class<?>, Typed> byJavaType) {
		this.byJavaType = byJavaType;
	}

	static PlsqlConverters none() {
		return NONE;
	}

	public static PlsqlConverters of(PlsqlConverter<?, ?>... converters) {
		Map<Class<?>, Typed> byJavaType = new HashMap<>();
		for (PlsqlConverter<?, ?> converter : converters) {
			Typed typed = typedOf(converter);
			if (byJavaType.put(typed.javaType(), typed) != null) {
				throw new PlsqlMappingException("there is more than one converter for " + typed.javaType().getName());
			}
		}
		return new PlsqlConverters(Map.copyOf(byJavaType));
	}

	/**
	 * These converters plus {@code overrides}; for the same Java type, the one in {@code overrides}
	 * wins. Useful to add an interface's converters to the global ones in {@code PlsqlSettings}.
	 */
	public PlsqlConverters with(PlsqlConverters overrides) {
		Map<Class<?>, Typed> merged = new HashMap<>(byJavaType);
		merged.putAll(overrides.byJavaType);
		return new PlsqlConverters(Map.copyOf(merged));
	}

	/** The Java types converted, for messages and logs. */
	@Override
	public String toString() {
		return byJavaType.keySet().stream().map(Class::getSimpleName).sorted().toList().toString();
	}

	/**
	 * The converter for a value declared as {@code declared}, or {@code null} if there is none.
	 *
	 * @param element the {@code T} of a {@code List<T>} or {@code T[]}, or {@code null}
	 * @param where how to refer to this point in an error message
	 */
	AppliedConverter find(PlsqlConvert annotation, Class<?> declared, Class<?> element, String where) {
		if (declared == null) {
			return null; // an untyped PlsqlOut, which has its own message
		}
		if (annotation != null) {
			Typed typed = annotated.computeIfAbsent(annotation.value(), PlsqlConverters::instantiate);
			if (typed.javaType() == wrapper(declared)) {
				return new AppliedConverter(typed.converter(), declared, typed.databaseType(), false);
			}
			if (element != null && typed.javaType() == wrapper(element)) {
				return new AppliedConverter(typed.converter(), element, typed.databaseType(), true);
			}
			throw new PlsqlMappingException(where + ": " + annotation.value().getSimpleName() + " converts "
					+ typed.javaType().getSimpleName() + ", but the value is " + declared.getSimpleName());
		}
		Typed typed = byJavaType.get(wrapper(declared));
		if (typed != null) {
			return new AppliedConverter(typed.converter(), declared, typed.databaseType(), false);
		}
		typed = element == null ? null : byJavaType.get(wrapper(element));
		return typed == null ? null : new AppliedConverter(typed.converter(), element, typed.databaseType(), true);
	}

	private static Typed instantiate(Class<?> converterClass) {
		try {
			Constructor<?> constructor = converterClass.getDeclaredConstructor();
			constructor.setAccessible(true);
			return typedOf((PlsqlConverter<?, ?>) constructor.newInstance());
		} catch (ReflectiveOperationException e) {
			throw new PlsqlMappingException(converterClass.getSimpleName()
					+ " needs a no-arg constructor: " + e, e);
		}
	}

	private static Typed typedOf(PlsqlConverter<?, ?> converter) {
		if (converter instanceof DeclaredTypes declared) {
			return new Typed(cast(converter), declared.javaType(), declared.databaseType());
		}
		Type[] arguments = argumentsOf(converter.getClass());
		if (arguments == null) {
			throw new PlsqlMappingException(converter.getClass().getName() + " must declare its types, as in"
					+ " implements PlsqlConverter<TaxId, String>; or use PlsqlConverter.of");
		}
		return new Typed(cast(converter), classOf(arguments[0], converter), classOf(arguments[1], converter));
	}

	/** The types of {@code PlsqlConverter<J, D>}, on the class itself or on one it inherits from. */
	private static Type[] argumentsOf(Class<?> type) {
		if (type == null) {
			return null;
		}
		for (Type generic : type.getGenericInterfaces()) {
			if (generic instanceof ParameterizedType parameterized
					&& parameterized.getRawType() == PlsqlConverter.class) {
				return parameterized.getActualTypeArguments();
			}
		}
		return argumentsOf(type.getSuperclass());
	}

	private static Class<?> classOf(Type type, PlsqlConverter<?, ?> converter) {
		if (type instanceof Class<?> declared) {
			return declared;
		}
		if (type instanceof ParameterizedType parameterized) {
			return (Class<?>) parameterized.getRawType();
		}
		throw new PlsqlMappingException(converter.getClass().getName() + " must declare its types, without"
				+ " type variables; or use PlsqlConverter.of");
	}

	@SuppressWarnings("unchecked")
	private static PlsqlConverter<Object, Object> cast(PlsqlConverter<?, ?> converter) {
		return (PlsqlConverter<Object, Object>) converter;
	}

	private static Class<?> wrapper(Class<?> type) {
		return type.isPrimitive() ? java.lang.invoke.MethodType.methodType(type).wrap().returnType() : type;
	}

	private record Typed(PlsqlConverter<Object, Object> converter, Class<?> javaType, Class<?> databaseType) {
	}
}
