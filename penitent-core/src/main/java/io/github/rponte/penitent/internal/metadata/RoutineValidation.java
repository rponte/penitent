package io.github.rponte.penitent.internal.metadata;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.temporal.Temporal;
import java.util.Collection;
import java.util.List;

import io.github.rponte.penitent.PlsqlOut;
import io.github.rponte.penitent.convert.PlsqlConverters;
import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.internal.convert.AppliedConverter;
import io.github.rponte.penitent.internal.convert.ScalarCodec;
import io.github.rponte.penitent.internal.metadata.RoutineMetadata.Argument;

/**
 * Whether a method and the routine it maps agree, checked before the call: afterwards, the routine
 * would already have done its work, and the user would get an {@code ORA-} error about text they did
 * not write. Some checks run once, when the routine is described, because they need the dictionary;
 * the others run on every call.
 */
public final class RoutineValidation {

	private final RoutineMetadata routine;

	public RoutineValidation(RoutineMetadata routine) {
		this.routine = routine;
	}

	/** How a refusal names a parameter: {@code P_X of SCHEMA.PACKAGE.ROUTINE}, or the return value. */
	private String nameOf(Argument argument) {
		return (argument.isReturnValue() ? "the return value" : argument.name()) + " of " + routine.qualifiedName();
	}

	/**
	 * Checks the arity. The message names the parameters the database expects, because that is the
	 * information the user does not have at hand when getting it wrong.
	 */
	public void requireArity(int javaArgumentCount) {
		List<Argument> params = routine.parameters();
		int required = routine.requiredParameterCount();

		if (javaArgumentCount < required || javaArgumentCount > params.size()) {
			String expected = required == params.size()
					? String.valueOf(params.size())
					: required + " to " + params.size() + " (the last ones have DEFAULT)";
			throw new PlsqlMappingException("the method declares " + javaArgumentCount
					+ (javaArgumentCount == 1 ? " parameter" : " parameters") + ", but " + routine.qualifiedName()
					+ " expects " + expected + ": " + params);
		}
	}

	/** A function mapped to a {@code void} method would have its return value silently discarded. */
	public void requireProcedure() {
		routine.returnValue().ifPresent(ret -> {
			throw new PlsqlMappingException("the method is void, but " + routine.qualifiedName()
					+ " is a function that returns " + ret.dataType() + "; declare the return type in the method");
		});
	}

	/** The reverse: a method with a return value pointing at a procedure would have nothing to return. */
	public void requireFunction() {
		if (routine.returnValue().isEmpty()) {
			throw new PlsqlMappingException("the method declares a return value, but " + routine.qualifiedName()
					+ " is a procedure and returns nothing; use void");
		}
	}

	/** Before calling: afterwards, the routine would already have done its work, and the value would be lost. */
	public void requireTemporalTypesToAgree(Method method, PlsqlConverters converters) {
		Parameter[] javaParameters = method.getParameters();
		List<Argument> params = routine.parameters();

		for (int i = 0; i < javaParameters.length; i++) {
			requireTemporalTypeToAgree(method, params.get(i),
					RoutineMetadata.sentTypeOf(method, javaParameters[i], converters));
		}
		if (ReturnType.of(method) != void.class) {
			AppliedConverter applied = RoutineMetadata.converterOfReturn(method, converters);
			Class<?> received = applied == null || applied.elementWise() ? ReturnType.of(method)
					: applied.databaseType();
			routine.returnValue().ifPresent(value -> requireTemporalTypeToAgree(method, value, received));
		}
	}

	private void requireTemporalTypeToAgree(Method method, Argument argument, Class<?> javaType) {
		String refusal = ScalarCodec.temporalRefusal(argument.dataType(), javaType);
		if (refusal != null) {
			throw new PlsqlMappingException(nameOf(argument) + " is " + argument.dataType() + ", and " + refusal);
		}
	}

	/**
	 * Whatever is {@code OUT} in the database is {@link PlsqlOut} in Java, and vice versa. Checking
	 * on the way in avoids an {@code ORA-06550} pointing at text the user did not write.
	 */
	public void requireOutParametersToAgree(Method method) {
		Parameter[] javaParameters = method.getParameters();
		List<Argument> params = routine.parameters();

		for (int i = 0; i < javaParameters.length; i++) {
			Argument param = params.get(i);
			boolean holder = OutParameters.isDeclaredBy(javaParameters[i]);
			if (param.isOut() && !holder) {
				throw new PlsqlMappingException(nameOf(param) + " is " + param.inOut()
						+ "; declare PlsqlOut<T> in this position, not "
						+ javaParameters[i].getType().getSimpleName());
			}
			if (!param.isOut() && holder) {
				throw new PlsqlMappingException(nameOf(param) + " is IN; declare the value in this position, not PlsqlOut");
			}
			if (holder && OutParameters.valueClassOf(javaParameters[i]) == null) {
				throw new PlsqlMappingException(nameOf(param) + " is a PlsqlOut without a type; declare PlsqlOut<SomeType>");
			}
		}
	}

	/**
	 * The driver does not write an {@code INDEX BY VARCHAR2}, and when reading one it loses the keys,
	 * which are its whole reason to exist.
	 */
	public static void requireNotIndexedByText(String indexBy, String container, String typeName) {
		if (indexBy != null && indexBy.contains("CHAR")) {
			throw new PlsqlMappingException(container + "." + typeName + " is INDEX BY " + indexBy
					+ ", and JDBC does not carry the keys. Expose a routine with a TABLE OF collection"
					+ " or a cursor, or call the routine via @PlsqlBlock with scalars");
		}
	}

	/** A {@code ZonedDateTime} would read a null element, without an error: the driver does not deliver its type here. */
	public static String indexByRefusal(Class<?> element) {
		return element == java.time.ZonedDateTime.class
				? "in an INDEX BY, the driver does not return the region of a ZonedDateTime; declare OffsetDateTime or Instant"
				: null;
	}

	/**
	 * When writing, the driver numbers an {@code INDEX BY} from 0, not from 1 like a {@code TABLE
	 * OF}: a {@code FOR i IN 1..p.COUNT} in the routine would read the wrong element, or not even
	 * find the last one. That is why it is only supported for reading.
	 */
	public void requireCollectionsToBeSupported(Method method, Dictionary dictionary, PlsqlConverters converters) {
		Parameter[] javaParameters = method.getParameters();
		List<Argument> params = routine.parameters();

		for (int i = 0; i < javaParameters.length; i++) {
			Argument param = params.get(i);
			if ("PL/SQL TABLE".equals(param.dataType())) {
				requireIndexBySupported(dictionary, param);
				if (param.isIn()) {
					throw new PlsqlMappingException(nameOf(param)
							+ " is INDEX BY, and the driver would write it starting at index 0, not 1. Declare the"
							+ " routine with a TABLE OF collection, or call it via @PlsqlBlock");
				}
				requireReadableIndexBy(method, param, param.name(), OutParameters.valueTypeOf(javaParameters[i]),
						RoutineMetadata.converterOf(method, javaParameters[i], converters));
			}
		}
		if (ReturnType.of(method) != void.class) {
			routine.returnValue().filter(value -> "PL/SQL TABLE".equals(value.dataType())).ifPresent(value -> {
				requireIndexBySupported(dictionary, value);
				requireReadableIndexBy(method, value, "the return value", ReturnType.genericOf(method),
						RoutineMetadata.converterOfReturn(method, converters));
			});
		}
	}

	private void requireIndexBySupported(Dictionary dictionary, Argument argument) {
		String owner = argument.ownerOr(routine.schema());
		requireNotIndexedByText(
				RoutineMetadata.indexByOf(dictionary, owner, argument.typeName(), argument.typeSubname()),
				argument.typeName(), argument.typeSubname());
	}

	private void requireReadableIndexBy(Method method, Argument argument, String name, java.lang.reflect.Type declared,
			AppliedConverter applied) {
		String refusal = indexByRefusal(applied != null && applied.elementWise() ? applied.databaseType()
				: RoutineMetadata.elementClassOf(declared));
		if (refusal != null) {
			throw new PlsqlMappingException(nameOf(argument) + ": " + refusal);
		}
	}

	/**
	 * The element of a collection lives in another dictionary query, so this validation only runs
	 * when the routine is described, and only for a date element, the only kind it rejects.
	 */
	public void requireTemporalElementsToAgree(Method method, Dictionary dictionary, PlsqlConverters converters) {
		Parameter[] javaParameters = method.getParameters();
		List<Argument> params = routine.parameters();

		for (int i = 0; i < javaParameters.length; i++) {
			java.lang.reflect.Type declared = OutParameters.isDeclaredBy(javaParameters[i])
					? OutParameters.valueTypeOf(javaParameters[i])
					: javaParameters[i].getParameterizedType();
			requireTemporalElementToAgree(method, params.get(i), declared,
					RoutineMetadata.converterOf(method, javaParameters[i], converters), dictionary);
		}
		if (ReturnType.of(method) != void.class) {
			AppliedConverter applied = RoutineMetadata.converterOfReturn(method, converters);
			routine.returnValue().ifPresent(value -> requireTemporalElementToAgree(method, value,
					ReturnType.genericOf(method), applied, dictionary));
		}
	}

	/**
	 * A converter only applies to a scalar value. In an OBJECT, RECORD or cursor, it is the attributes
	 * and columns that each have their own. For a collection, it must be per element, and the element
	 * must be a scalar.
	 */
	public void requireConvertersToFit(Method method, Dictionary dictionary, PlsqlConverters converters) {
		Parameter[] javaParameters = method.getParameters();
		List<Argument> params = routine.parameters();

		for (int i = 0; i < javaParameters.length; i++) {
			requireConverterToFit(method, params.get(i),
					RoutineMetadata.converterOf(method, javaParameters[i], converters), dictionary);
		}
		if (ReturnType.of(method) != void.class) {
			AppliedConverter applied = RoutineMetadata.converterOfReturn(method, converters);
			routine.returnValue().ifPresent(value -> requireConverterToFit(method, value, applied, dictionary));
		}
	}

	private void requireConverterToFit(Method method, Argument argument, AppliedConverter applied,
			Dictionary dictionary) {
		if (applied == null) {
			return;
		}
		// a cursor of scalars takes the converter of its element, as a collection of scalars does
		boolean fits = !argument.isStruct()
				&& (argument.isCollection() || argument.isCursor()) == applied.elementWise()
				&& (!argument.isCollection()
						|| RoutineMetadata.collectionTypeOf(dictionary, argument, routine.schema()).holdsScalars());
		if (!fits) {
			throw new PlsqlMappingException(nameOf(argument) + " is "
					+ argument.dataType() + ", and a converter only applies to a scalar value, or to the"
					+ " scalar elements of a collection");
		}
	}

	private void requireTemporalElementToAgree(Method method, Argument argument, java.lang.reflect.Type declared,
			AppliedConverter applied, Dictionary dictionary) {
		Class<?> element = applied != null && applied.elementWise() ? applied.databaseType()
				: RoutineMetadata.elementClassOf(declared);
		if (!argument.isCollection() || element == null || !Temporal.class.isAssignableFrom(element)) {
			return;
		}
		String oracleElement = RoutineMetadata.elementTypeNameOf(dictionary, argument, routine.schema());
		String refusal = ScalarCodec.temporalRefusal(oracleElement, element);
		if (refusal != null) {
			throw new PlsqlMappingException(nameOf(argument) + " is a collection of " + oracleElement + ", and "
					+ refusal);
		}
	}

	/**
	 * Why the Java element type does not fit the levels of an Oracle collection, or {@code null} if it
	 * does: a collection of collections is a {@code List} of {@code List}s (or arrays), level by level,
	 * and a collection of scalars or composites does not take a {@code List} as its element. Without a
	 * generic type to look at, there is nothing to compare.
	 */
	public static String nestingRefusal(CollectionType collection, java.lang.reflect.Type javaElement) {
		CollectionType level = collection;
		java.lang.reflect.Type element = javaElement;
		while (element != null) {
			boolean javaNested = isJavaCollection(element);
			if (level.collectionElement() != null && !javaNested) {
				return level.bindName() + " holds collections (" + level.collectionElement().bindName()
						+ "), so its Java element must be a List or an array, not " + element.getTypeName();
			}
			if (level.collectionElement() == null && javaNested) {
				return level.bindName() + " holds " + (level.compositeElement() != null
						? level.compositeElement().bindName() : "scalars")
						+ ", not collections, so its Java element cannot be " + element.getTypeName();
			}
			if (level.collectionElement() == null) {
				return null;
			}
			level = level.collectionElement();
			element = RoutineMetadata.elementTypeOf(element);
		}
		return null;
	}

	private static boolean isJavaCollection(java.lang.reflect.Type type) {
		Class<?> raw = type instanceof java.lang.reflect.ParameterizedType parameterized
				? (Class<?>) parameterized.getRawType()
				: type instanceof Class<?> c ? c : null;
		return type instanceof java.lang.reflect.GenericArrayType
				|| (raw != null && (Collection.class.isAssignableFrom(raw) || (raw.isArray() && raw != byte[].class)));
	}

	/**
	 * Checked when the routine is described: otherwise an inner {@code Array} would be read as a scalar,
	 * or a {@code List} sent as one, with a driver message that does not say which level is wrong.
	 */
	public void requireNestingToAgree(Method method, Dictionary dictionary) {
		Parameter[] javaParameters = method.getParameters();
		List<Argument> params = routine.parameters();

		for (int i = 0; i < javaParameters.length; i++) {
			if (params.get(i).isCollection()) {
				java.lang.reflect.Type declared = OutParameters.isDeclaredBy(javaParameters[i])
						? OutParameters.valueTypeOf(javaParameters[i])
						: javaParameters[i].getParameterizedType();
				requireNestingToAgree(method, params.get(i), declared, dictionary);
			}
		}
		if (ReturnType.of(method) != void.class) {
			routine.returnValue().filter(Argument::isCollection)
					.ifPresent(value -> requireNestingToAgree(method, value, ReturnType.genericOf(method), dictionary));
		}
	}

	private void requireNestingToAgree(Method method, Argument argument, java.lang.reflect.Type declared,
			Dictionary dictionary) {
		String refusal = declared == null ? null
				: nestingRefusal(RoutineMetadata.collectionTypeOf(dictionary, argument, routine.schema()),
						RoutineMetadata.elementTypeOf(declared));
		if (refusal != null) {
			throw new PlsqlMappingException(nameOf(argument) + ": " + refusal);
		}
	}
}
