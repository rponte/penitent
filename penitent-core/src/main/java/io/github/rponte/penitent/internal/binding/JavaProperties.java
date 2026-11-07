package io.github.rponte.penitent.internal.binding;

import java.lang.annotation.Annotation;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.rponte.penitent.PlsqlAttr;
import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.internal.convert.ScalarCodec;

/**
 * The properties of a Java type, indexed by the Oracle names they accept.
 *
 * <p>It lives in its own file because it has two clients with different sources of truth:
 * {@link TypeBinding}, which matches against the dictionary, and {@link CursorBinding}, which
 * matches against the {@code ResultSetMetaData}. The Java side of the operation is the same for
 * both.
 *
 * <p>It supports {@code record} and JavaBean. The distinction is not a whim: the entities of this
 * project are mutable beans with builders, and requiring records would couple "use the new API" to
 * "convert everything to records", two refactorings that are better kept apart. A library that only
 * accepted records would also be hostile to adoption, because almost all real code has beans.
 */
public final class JavaProperties {

	private JavaProperties() {
	}

	interface JavaProperty {
		String name();

		Class<?> type();

		/** The {@code T} of {@code List<T>} or {@code T[]}; null when it is neither. */
		Class<?> elementType();

		Method reader();

		Method writer();

		<A extends Annotation> A annotation(Class<A> type);
	}

	/**
	 * Whether a Java type is mapped attribute by attribute, as a {@code record} or a JavaBean: not a
	 * scalar, a collection, an array or a type of the platform or of the driver, which go as they are.
	 */
	public static boolean isComposite(Class<?> type) {
		if (type.isRecord()) {
			return true;
		}
		String name = type.getName();
		return !ScalarCodec.isScalar(type) && !type.isArray() && !type.isInterface()
				&& !java.util.Collection.class.isAssignableFrom(type) && !Map.class.isAssignableFrom(type)
				&& !name.startsWith("java.") && !name.startsWith("javax.") && !name.startsWith("oracle.");
	}

	/** Java name and Oracle name, reduced to the same form: no underscores, upper case. */
	static String key(String name) {
		return name.replace("_", "").toUpperCase(Locale.ROOT);
	}

	static Map<String, JavaProperty> of(Class<?> javaType) {
		return javaType.isRecord() ? ofRecord(javaType) : ofBean(javaType);
	}

	/**
	 * A {@code @PlsqlAttr} can list several names, so the property is registered under all of them,
	 * and two owners for the same name is an error, not a silent tie-break.
	 */
	private static void register(Map<String, JavaProperty> byName, Class<?> javaType, String[] oracleNames,
			JavaProperty property) {

		for (String oracleName : oracleNames) {
			JavaProperty previous = byName.put(key(oracleName), property);
			if (previous != null && !previous.name().equals(property.name())) {
				throw new PlsqlMappingException(javaType.getSimpleName() + ": " + previous.name() + " and "
						+ property.name() + " claim the same Oracle name (" + oracleName + ")");
			}
		}
	}

	private static String[] oracleNamesOf(PlsqlAttr named, String fallback) {
		return named != null && named.value().length > 0 ? named.value() : new String[] { fallback };
	}

	private static Map<String, JavaProperty> ofRecord(Class<?> javaType) {
		Map<String, JavaProperty> byName = new LinkedHashMap<>();
		for (RecordComponent component : javaType.getRecordComponents()) {
			JavaProperty property = new JavaProperty() {
				@Override
				public String name() {
					return component.getName();
				}

				@Override
				public Class<?> type() {
					return component.getType();
				}

				@Override
				public Class<?> elementType() {
					return elementClassOf(component.getGenericType(), component.getType());
				}

				@Override
				public Method reader() {
					return component.getAccessor();
				}

				@Override
				public Method writer() {
					return null; // a record is written through its canonical constructor
				}

				@Override
				public <A extends Annotation> A annotation(Class<A> type) {
					return component.getAnnotation(type);
				}
			};
			register(byName, javaType, oracleNamesOf(component.getAnnotation(PlsqlAttr.class),
					component.getName()), property);
		}
		return byName;
	}

	private static Map<String, JavaProperty> ofBean(Class<?> javaType) {
		Map<String, JavaProperty> byName = new LinkedHashMap<>();
		for (Method method : javaType.getMethods()) {
			String propertyName = propertyNameOf(method);
			if (propertyName == null) {
				continue;
			}
			Method writer = writerFor(javaType, propertyName, method.getReturnType());
			JavaProperty property = new JavaProperty() {
				@Override
				public String name() {
					return propertyName;
				}

				@Override
				public Class<?> type() {
					return method.getReturnType();
				}

				@Override
				public Class<?> elementType() {
					return elementClassOf(method.getGenericReturnType(), method.getReturnType());
				}

				@Override
				public Method reader() {
					return method;
				}

				@Override
				public Method writer() {
					return writer;
				}

				@Override
				public <A extends Annotation> A annotation(Class<A> type) {
					return annotationOf(javaType, method, writer, propertyName, type);
				}
			};
			register(byName, javaType,
					oracleNamesOf(annotationOf(javaType, method, writer, propertyName, PlsqlAttr.class),
							propertyName),
					property);
		}
		return byName;
	}

	private static String propertyNameOf(Method method) {
		if (method.getParameterCount() != 0 || method.getDeclaringClass() == Object.class) {
			return null;
		}
		String name = method.getName();
		if (name.startsWith("get") && name.length() > 3) {
			return Character.toLowerCase(name.charAt(3)) + name.substring(4);
		}
		if (name.startsWith("is") && name.length() > 2
				&& (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class)) {
			return Character.toLowerCase(name.charAt(2)) + name.substring(3);
		}
		return null;
	}

	private static Method writerFor(Class<?> javaType, String propertyName, Class<?> propertyType) {
		String setter = "set" + Character.toUpperCase(propertyName.charAt(0)) + propertyName.substring(1);
		try {
			return javaType.getMethod(setter, propertyType);
		} catch (NoSuchMethodException e) {
			return null; // read-only: fine for writing to the database; reading into it fails in instantiate
		}
	}

	/** The annotation may be on the getter, the setter or the field; we look in all three. */
	static <A extends Annotation> A annotationOf(Class<?> javaType, Method reader, Method writer,
			String propertyName, Class<A> annotationType) {
		for (AccessibleObject candidate : new AccessibleObject[] { reader, writer }) {
			if (candidate != null && candidate.isAnnotationPresent(annotationType)) {
				return candidate.getAnnotation(annotationType);
			}
		}
		try {
			return javaType.getDeclaredField(propertyName).getAnnotation(annotationType);
		} catch (NoSuchFieldException e) {
			return null;
		}
	}

	/**
	 * The element type, as declared in Java. The dictionary knows the element's Oracle type, but not
	 * which class it should land in, and a raw {@code List} does not say.
	 */
	static Class<?> elementClassOf(Type generic, Class<?> raw) {
		if (raw.isArray()) {
			return raw.getComponentType();
		}
		if (generic instanceof ParameterizedType parameterized
				&& parameterized.getActualTypeArguments().length == 1
				&& parameterized.getActualTypeArguments()[0] instanceof Class<?> element) {
			return element;
		}
		return null;
	}

	static Constructor<?> canonicalConstructor(Class<?> javaType) {
		Class<?>[] parameters = java.util.Arrays.stream(javaType.getRecordComponents())
				.map(RecordComponent::getType)
				.toArray(Class<?>[]::new);
		try {
			return javaType.getDeclaredConstructor(parameters);
		} catch (NoSuchMethodException e) {
			throw new PlsqlMappingException("could not find the canonical constructor of " + javaType.getName());
		}
	}

	/** Instantiates and populates, through the record's canonical constructor or the bean's setters. */
	static Object instantiate(Class<?> javaType, Constructor<?> recordConstructor,
			List<? extends Assignment> assignments) {

		if (javaType.isRecord()) {
			RecordComponent[] components = javaType.getRecordComponents();
			Object[] arguments = new Object[components.length];
			for (Assignment assignment : assignments) {
				arguments[indexOfComponent(components, assignment.javaName())] = assignment.value();
			}
			try {
				recordConstructor.setAccessible(true);
				return recordConstructor.newInstance(arguments);
			} catch (ReflectiveOperationException e) {
				throw new PlsqlMappingException("failed to construct " + javaType.getName() + ": " + e.getMessage());
			}
		}

		try {
			Object instance = javaType.getDeclaredConstructor().newInstance();
			for (Assignment assignment : assignments) {
				if (assignment.writer() == null) {
					throw new PlsqlMappingException(javaType.getSimpleName() + "." + assignment.javaName()
							+ " has no setter, and the value read from the database would be discarded");
				}
				assignment.writer().invoke(instance, assignment.value());
			}
			return instance;
		} catch (NoSuchMethodException e) {
			throw new PlsqlMappingException(javaType.getName()
					+ " needs a no-arg constructor to be read from the database, or must be a record");
		} catch (ReflectiveOperationException e) {
			throw new PlsqlMappingException("failed to populate " + javaType.getName() + ": " + e.getMessage());
		}
	}

	/** An already decoded value, with the Java target it belongs to. */
	interface Assignment {
		String javaName();

		Method writer();

		Object value();
	}

	record SimpleAssignment(String javaName, Method writer, Object value) implements Assignment {
	}

	static List<Assignment> assignments() {
		return new ArrayList<>();
	}

	private static int indexOfComponent(RecordComponent[] components, String name) {
		for (int i = 0; i < components.length; i++) {
			if (components[i].getName().equals(name)) {
				return i;
			}
		}
		throw new PlsqlMappingException("component " + name + " disappeared from the record");
	}
}
