package io.github.rponte.penitent.internal.binding;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Struct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.rponte.penitent.PlsqlBoolean;
import io.github.rponte.penitent.PlsqlType;
import io.github.rponte.penitent.convert.PlsqlConvert;
import io.github.rponte.penitent.convert.PlsqlConverters;
import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.internal.Caches;
import io.github.rponte.penitent.internal.binding.JavaProperties.JavaProperty;
import io.github.rponte.penitent.internal.convert.AppliedConverter;
import io.github.rponte.penitent.internal.convert.ConverterLookup;
import io.github.rponte.penitent.internal.convert.ScalarCodec;
import io.github.rponte.penitent.internal.convert.ScalarCodec.ValueConversion;
import io.github.rponte.penitent.internal.metadata.CollectionType;
import io.github.rponte.penitent.internal.metadata.CompositeType;
import io.github.rponte.penitent.internal.metadata.Dictionary;
import io.github.rponte.penitent.internal.metadata.RoutineMetadata;
import io.github.rponte.penitent.internal.metadata.RoutineValidation;

/**
 * The binding between a Java type and an Oracle OBJECT, matched by attribute name and checked
 * against {@code ALL_TYPE_ATTRS}.
 *
 * <p>It supports {@code record} and JavaBean. The distinction is not a whim: the entities of this
 * project are mutable beans with builders, and requiring records would couple "use the new API" to
 * "convert everything to records", two refactorings that are better kept apart. A library that only
 * accepted records would also be hostile to adoption, because almost all real code has beans.
 */
public final class TypeBinding {

	/**
	 * The shape of an attribute, which decides how it crosses over: a scalar as is, a composite as a
	 * nested {@link Struct}, or a collection as a {@link java.sql.Array}.
	 *
	 * <p>These are the three the dictionary distinguishes, and the distinction matters: treating a
	 * collection as a composite yields {@code ORA-17060}, and the reverse, a failed cast.
	 */
	private sealed interface Shape {

		record Scalar() implements Shape {
		}

		record Composite(CompositeType type) implements Shape {
		}

		/**
		 * {@code element} is null when the collection holds scalars, and also when it holds collections,
		 * which {@code ofCollections} says.
		 */
		record CollectionOf(String bindName, CompositeType element, String elementTypeName, String indexBy,
				boolean ofCollections) implements Shape {
		}
	}

	/**
	 * An attribute of the composite type, already resolved to how it is read and written in Java.
	 *
	 * <p>The binding of a composite attribute is resolved when it is used, not here, because types
	 * may reference each other in a tree, and resolving everything up front would recurse.
	 *
	 * <p>{@code javaElementType} only applies to {@link Shape.CollectionOf}: it is the {@code T} of a
	 * {@code List<T>} or {@code T[]} declared in Java, because the dictionary gives the element's
	 * Oracle type but not the Java class it should land in.
	 */
	private record Attribute(int attrNo, String oracleName, String oracleType, String javaName, Class<?> javaType,
			Class<?> javaElementType, Method reader, Method writer, ValueConversion conversion, Shape shape,
			AppliedConverter converter) {

		boolean isComposite() {
			return shape instanceof Shape.Composite;
		}

		boolean isCollection() {
			return shape instanceof Shape.CollectionOf;
		}
	}

	private final Class<?> javaType;
	private final String oracleTypeName;
	private final List<Attribute> attributes;
	private final Constructor<?> recordConstructor;
	private final java.util.concurrent.ConcurrentMap<String, TypeBinding> nested = new java.util.concurrent.ConcurrentHashMap<>();
	private final PlsqlConverters converters;

	private TypeBinding(Class<?> javaType, String oracleTypeName, List<Attribute> attributes,
			Constructor<?> recordConstructor, PlsqlConverters converters) {
		this.converters = converters;
		this.javaType = javaType;
		this.oracleTypeName = oracleTypeName;
		this.attributes = attributes;
		this.recordConstructor = recordConstructor;
	}

	/** The binding of the composite attribute, resolved the first time it is requested. */
	private TypeBinding nestedBinding(Attribute attribute, Connection connection) {
		Shape.Composite composite = (Shape.Composite) attribute.shape();
		return Caches.get(nested, attribute.oracleName(),
				k -> TypeBinding.of(new Dictionary(connection), attribute.javaType(), composite.type(), converters));
	}

	/** The binding of the <b>element</b> of a collection attribute. */
	private TypeBinding elementBinding(Attribute attribute, Connection connection) {
		Shape.CollectionOf collection = (Shape.CollectionOf) attribute.shape();
		return Caches.get(nested, attribute.oracleName() + "[]",
				k -> TypeBinding.of(new Dictionary(connection), attribute.javaElementType(), collection.element(),
						converters));
	}

	/**
	 * Resolves now, all the way down, the bindings of the composite attributes, which otherwise are
	 * resolved the first time a value goes through them: this is how a validation finds a mismatch
	 * before any call. {@code visited} keeps a tree that references itself from recursing forever.
	 */
	public void resolveNested(Connection connection) {
		resolveNested(connection, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
	}

	private void resolveNested(Connection connection, java.util.Set<TypeBinding> visited) {
		if (!visited.add(this)) {
			return;
		}
		for (Attribute attribute : attributes) {
			if (attribute.shape() instanceof Shape.Composite) {
				nestedBinding(attribute, connection).resolveNested(connection, visited);
			} else if (attribute.shape() instanceof Shape.CollectionOf collection && collection.element() != null) {
				elementBinding(attribute, connection).resolveNested(connection, visited);
			}
		}
	}

	String oracleTypeName() {
		return oracleTypeName;
	}

	/**
	 * Builds the binding, or fails naming exactly what did not match, which is the reason the
	 * validation exists: the alternative is the user getting an {@code ORA-06550} and having to guess.
	 */
	public static TypeBinding of(Dictionary dictionary, Class<?> javaType, CompositeType type, PlsqlConverters converters) {

		String oracleType = type.bindName();
		requireDeclaredTypeToAgree(javaType, type);
		List<OracleAttribute> oracleAttributes = readAttributes(dictionary, type);

		Map<String, JavaProperty> properties = JavaProperties.of(javaType);

		List<Attribute> bound = new ArrayList<>();
		List<String> mismatched = new ArrayList<>();

		for (OracleAttribute attr : oracleAttributes) {
			JavaProperty property = properties.get(JavaProperties.key(attr.name()));
			if (property == null) {
				mismatched.add(attr.name() + " " + attr.typeName());
				continue;
			}
			if (attr.shape() instanceof Shape.CollectionOf collection && collection.ofCollections()
					&& property.elementType() == null) {
				throw new PlsqlMappingException(attr.name() + " of " + oracleType + " is " + collection.bindName()
						+ ", a collection of collections, which is supported as a parameter or return value, not as an"
						+ " attribute");
			}
			if (attr.shape() instanceof Shape.CollectionOf && property.elementType() == null) {
				throw new PlsqlMappingException(attr.name() + " of " + oracleType + " is a collection ("
						+ attr.typeName() + "), but " + javaType.getSimpleName() + "." + property.name()
						+ " is " + property.type().getSimpleName()
						+ "; declare List<T> or T[] to state the element type");
			}
			AppliedConverter applied = ConverterLookup.find(converters, property.annotation(PlsqlConvert.class),
					property.type(), property.elementType(), javaType.getSimpleName() + "." + property.name());
			requireConverterToFit(applied, attr, oracleType, javaType, property);
			Class<?> sent = applied == null ? null : applied.databaseType();
			if (attr.shape() instanceof Shape.CollectionOf collection && collection.indexBy() != null) {
				String indexByRefusal = RoutineValidation.indexByRefusal(sent != null ? sent : property.elementType());
				if (indexByRefusal != null) {
					throw new PlsqlMappingException(attr.name() + " of " + oracleType + ": " + indexByRefusal);
				}
			}
			String refusal = attr.shape() instanceof Shape.CollectionOf collection
					? property.elementType() == null ? null
							: ScalarCodec.temporalRefusal(collection.elementTypeName(),
									sent != null ? sent : property.elementType())
					: ScalarCodec.temporalRefusal(attr.typeName(), sent != null ? sent : property.type());
			if (refusal != null) {
				throw new PlsqlMappingException(attr.name() + " of " + oracleType + " is " + attr.typeName() + ", and "
						+ javaType.getSimpleName() + "." + property.name() + ": " + refusal);
			}
			bound.add(new Attribute(attr.attrNo(), attr.name(), attr.typeName(), property.name(), property.type(),
					property.elementType(), property.reader(), property.writer(),
					conversionOf(attr, property.annotation(PlsqlBoolean.class)),
					attr.shape(), applied));
		}

		if (!mismatched.isEmpty()) {
			throw new PlsqlMappingException(javaType.getSimpleName() + " does not cover attribute(s) of "
					+ oracleType + ": " + mismatched
					+ ". Available properties: " + properties.keySet()
					+ ". Use @PlsqlAttr to declare the name.");
		}

		Constructor<?> ctor = javaType.isRecord() ? JavaProperties.canonicalConstructor(javaType) : null;
		return new TypeBinding(javaType, type.qualifiedBindName(), List.copyOf(bound), ctor, converters);
	}

	/** A composite attribute has its own attributes, and the converter applies to those. */
	private static void requireConverterToFit(AppliedConverter applied, OracleAttribute attr, String oracleType,
			Class<?> javaType, JavaProperty property) {
		if (applied == null) {
			return;
		}
		boolean fits = attr.shape() instanceof Shape.CollectionOf collection
				? applied.elementWise() && collection.element() == null
				: !(attr.shape() instanceof Shape.Composite) && !applied.elementWise();
		if (!fits) {
			throw new PlsqlMappingException(attr.name() + " of " + oracleType + " is " + attr.typeName() + ", and "
					+ javaType.getSimpleName() + "." + property.name() + " has a converter, which only applies to a"
					+ " scalar value, or to the scalar elements of a collection");
		}
	}

	/**
	 * {@code @PlsqlType} is optional: the dictionary already says which type this argument uses. But
	 * when it is present and disagrees, the class is most likely bound to the wrong type, and saying
	 * so is better than silently matching the attributes of another type.
	 */
	private static void requireDeclaredTypeToAgree(Class<?> javaType, CompositeType type) {
		PlsqlType declared = javaType.getAnnotation(PlsqlType.class);
		if (declared == null) {
			return;
		}
		String declaredName = RoutineMetadata.normalizeTypeName(declared.value());
		if (!declaredName.equals(type.simpleName()) && !declaredName.equals(type.bindName())) {
			throw new PlsqlMappingException(javaType.getSimpleName() + " declares @PlsqlType(\"" + declaredName
					+ "\"), but the dictionary says this argument is " + type.bindName());
		}
	}

	private record OracleAttribute(int attrNo, String name, String typeName, Shape shape) {

		boolean isBoolean() {
			return "BOOLEAN".equals(typeName) || "PL/SQL BOOLEAN".equals(typeName);
		}

		boolean isNumeric() {
			return typeName != null && (typeName.equals("NUMBER") || typeName.equals("FLOAT")
					|| typeName.contains("BINARY_") || typeName.contains("PLS_INTEGER"));
		}
	}

	/** The same order as for a parameter: the dictionary BOOLEAN, the annotation, then the dictionary type. */
	private static ValueConversion conversionOf(OracleAttribute attribute, PlsqlBoolean annotation) {
		if (attribute.isBoolean()) {
			return ValueConversion.NATIVE_BOOLEAN;
		}
		if (annotation == null && attribute.isNumeric()) {
			return ValueConversion.NUMERIC_BOOLEAN;
		}
		return ValueConversion.from(annotation);
	}

	/**
	 * The attributes, read from wherever the dictionary keeps them for each form of composite type.
	 * See {@link CompositeType} for the table of the three.
	 */
	private static List<OracleAttribute> readAttributes(Dictionary dictionary, CompositeType type) {
		List<OracleAttribute> attributes = switch (type.kind()) {
			// an OBJECT only references schema-level types, so the attribute's container is null
			case OBJECT -> dictionary.query("""
					select attr_no, attr_name, attr_type_name, attr_type_owner
					  from all_type_attrs
					 where owner = ? and type_name = ?
					 order by attr_no
					""",
					(rs, n) -> new OracleAttribute(rs.getInt("attr_no"), rs.getString("attr_name"),
							rs.getString("attr_type_name"),
							shapeOf(dictionary, type.schema(), null, rs.getString("attr_type_owner"),
									rs.getString("attr_type_name"))),
					type.schema(), type.simpleName());

			case PLSQL_RECORD -> dictionary.query("""
					select attr_no, attr_name, attr_type_name, attr_type_owner, attr_type_package
					  from all_plsql_type_attrs
					 where owner = ? and package_name = ? and type_name = ?
					 order by attr_no
					""",
					(rs, n) -> new OracleAttribute(rs.getInt("attr_no"), rs.getString("attr_name"),
							rs.getString("attr_type_name"),
							shapeOf(dictionary, type.schema(), rs.getString("attr_type_package"),
									rs.getString("attr_type_owner"), rs.getString("attr_type_name"))),
					type.schema(), type.container(), type.simpleName());

			// %ROWTYPE: the "attributes" are the table's columns, in declared order
			case ROWTYPE -> dictionary.query("""
					select column_id, column_name, data_type
					  from all_tab_columns
					 where owner = ? and table_name = ?
					 order by column_id
					""",
					(rs, n) -> new OracleAttribute(rs.getInt("column_id"), rs.getString("column_name"),
							rs.getString("data_type"), new Shape.Scalar()),
					type.schema(), type.simpleName());
		};

		if (attributes.isEmpty()) {
			throw new PlsqlMappingException("type " + type + " in " + type.schema()
					+ " does not exist, has no attributes, or is not visible to this user");
		}
		return attributes;
	}

	/**
	 * The shape of an attribute, decided by the dictionary's {@code typecode}.
	 *
	 * <p>Without an owner, it is a scalar. With an owner, it is a user-defined type, and then only the
	 * {@code typecode} says whether it is an OBJECT/RECORD (becomes a {@code Struct}) or a COLLECTION
	 * (becomes an {@code Array}). The type name alone does not tell: {@code VARCHAR2_LIST} and
	 * {@code PERIOD_T} can both be schema-level types in the same schema, and only one of them is a
	 * collection.
	 */
	private static Shape shapeOf(Dictionary dictionary, String schema, String container, String owner,
			String typeName) {

		if (owner == null && container == null) {
			return new Shape.Scalar();
		}

		String typeOwner = owner != null ? owner : schema;
		String typecode = container != null
				? typecodeOf(dictionary, """
						select typecode from all_plsql_types
						 where owner = ? and package_name = ? and type_name = ?
						""", typeOwner, container, typeName)
				: typecodeOf(dictionary, "select typecode from all_types where owner = ? and type_name = ?",
						typeOwner, typeName);

		if ("COLLECTION".equals(typecode)) {
			String indexBy = RoutineMetadata.indexByOf(dictionary, typeOwner, container, typeName);
			RoutineValidation.requireNotIndexedByText(indexBy, container, typeName);
			CollectionType collection = RoutineMetadata.collectionTypeOf(dictionary, typeOwner, container, typeName);
			return new Shape.CollectionOf(collection.bindName(), collection.compositeElement(),
					RoutineMetadata.elementTypeNameOf(dictionary, typeOwner, container, typeName), indexBy,
					collection.collectionElement() != null);
		}
		return new Shape.Composite(container != null
				? new CompositeType(CompositeType.Kind.PLSQL_RECORD, typeOwner, container, typeName,
						container + "." + typeName)
				: new CompositeType(CompositeType.Kind.OBJECT, typeOwner, null, typeName, typeName));
	}

	private static String typecodeOf(Dictionary dictionary, String sql, Object... parameters) {
		List<String> found = dictionary.strings(sql, parameters);
		if (found.isEmpty()) {
			throw new PlsqlMappingException("could not find type " + java.util.Arrays.toString(parameters)
					+ " in the dictionary; does it exist and is it visible to this user?");
		}
		return found.get(0);
	}

	// --- conversion -----------------------------------------------------------------

	/** Java → {@link Struct}, with the attributes in the order Oracle declares them. */
	public Struct toStruct(Object source, Connection connection) throws SQLException {
		Object[] values = new Object[attributes.size()];
		for (Attribute attribute : attributes) {
			values[attribute.attrNo() - 1] = encodeAttribute(attribute, read(source, attribute), connection);
		}
		try {
			return connection.createStruct(oracleTypeName, values);
		} catch (IllegalArgumentException | ClassCastException e) {
			// the driver converts the value right here, and does not say which attribute it was
			throw new PlsqlMappingException("a value does not fit in " + oracleTypeName + ": " + e.getMessage()
					+ ". Attributes: " + describe(values), e);
		}
	}

	private String describe(Object[] values) {
		return attributes.stream()
				.map(attribute -> {
					Object value = values[attribute.attrNo() - 1];
					return attribute.oracleName() + "=" + (value == null ? "null" : value.getClass().getSimpleName());
				})
				.toList().toString();
	}

	private Object encodeAttribute(Attribute attribute, Object raw, Connection connection) throws SQLException {
		if (attribute.converter() != null && !attribute.converter().elementWise()) {
			return TemporaryLobs.forLob(attribute.oracleType(),
					ScalarCodec.encode(withContext(attribute, () -> attribute.converter().toDatabase(raw)),
							attribute.conversion()),
					connection);
		}
		if (raw == null) {
			return null;
		}
		if (attribute.isComposite()) {
			return nestedBinding(attribute, connection).toStruct(raw, connection);
		}
		if (attribute.isCollection()) {
			return encodeCollectionAttribute(attribute, raw, connection);
		}
		return TemporaryLobs.forLob(attribute.oracleType(), ScalarCodec.encode(raw, attribute.conversion()), connection);
	}

	private Object encodeCollectionAttribute(Attribute attribute, Object raw, Connection connection)
			throws SQLException {

		Shape.CollectionOf collection = (Shape.CollectionOf) attribute.shape();
		requireNotOfCollections(attribute, collection);
		if (collection.indexBy() != null) {
			throw new PlsqlMappingException(attribute.oracleName() + " of " + oracleTypeName
					+ " is INDEX BY, and the driver would write it starting at index 0, not 1. Declare the field with"
					+ " a TABLE OF collection");
		}
		Object[] source = toArray(attribute, raw);
		Object[] encoded = new Object[source.length];

		for (int i = 0; i < source.length; i++) {
			Object element = source[i];
			if (attribute.converter() != null) {
				encoded[i] = ScalarCodec.encode(withContext(attribute, () -> attribute.converter().toDatabase(element)),
						attribute.conversion());
			} else if (source[i] == null) {
				encoded[i] = null;
			} else if (collection.element() != null) {
				encoded[i] = elementBinding(attribute, connection).toStruct(source[i], connection);
			} else {
				// the element inherits the attribute's conversion: that is how a List<Enum> becomes a VARCHAR2_LIST
				encoded[i] = ScalarCodec.encode(source[i], attribute.conversion());
			}
		}
		return OracleArrays.create(connection, collection.bindName(), encoded);
	}

	/**
	 * Only when there is a value: a null attribute crosses without looking inside, as it did before a
	 * collection of collections was recognized.
	 */
	private void requireNotOfCollections(Attribute attribute, Shape.CollectionOf collection) {
		if (collection.ofCollections()) {
			throw new PlsqlMappingException(attribute.oracleName() + " of " + oracleTypeName + " is "
					+ collection.bindName() + ", a collection of collections, which is supported as a parameter or"
					+ " return value, not as an attribute");
		}
	}

	private Object[] toArray(Attribute attribute, Object raw) {
		if (raw instanceof java.util.Collection<?> collection) {
			return collection.toArray();
		}
		if (raw instanceof Object[] array) {
			return array;
		}
		if (raw.getClass().isArray()) {
			int length = java.lang.reflect.Array.getLength(raw);
			Object[] boxed = new Object[length];
			for (int i = 0; i < length; i++) {
				boxed[i] = java.lang.reflect.Array.get(raw, i);
			}
			return boxed;
		}
		throw new PlsqlMappingException(attribute.oracleName() + " of " + oracleTypeName
				+ " is a collection in Oracle; declare List<T> or T[] in Java, not "
				+ raw.getClass().getSimpleName());
	}

	/** {@link Struct} → Java. */
	public Object fromStruct(Struct struct, Connection connection) throws SQLException {
		return instantiate(struct.getAttributes(), connection);
	}

	/** Builds through the record's canonical constructor or the bean's setters. */
	private Object instantiate(Object[] values, Connection connection) {
		List<JavaProperties.Assignment> assignments = new ArrayList<>();
		for (Attribute attribute : attributes) {
			assignments.add(new JavaProperties.SimpleAssignment(attribute.javaName(), attribute.writer(),
					decodeAttribute(attribute, values[attribute.attrNo() - 1], connection)));
		}
		return JavaProperties.instantiate(javaType, recordConstructor, assignments);
	}

	/** A composite attribute comes back as a {@link Struct} and a collection one as an {@code Array}. */
	private Object decodeAttribute(Attribute attribute, Object raw, Connection connection) {
		if (raw == null && (attribute.isComposite() || attribute.isCollection())) {
			return null;
		}
		try {
			if (attribute.isComposite()) {
				return nestedBinding(attribute, connection).fromStruct((Struct) raw, connection);
			}
			if (attribute.isCollection()) {
				return decodeCollectionAttribute(attribute, raw, connection);
			}
		} catch (SQLException e) {
			throw new PlsqlMappingException("failed to read attribute "
					+ attribute.oracleName() + " of " + oracleTypeName + ": " + e.getMessage());
		}
		return withContext(attribute, () -> {
			AppliedConverter applied = attribute.converter();
			return applied == null
					? ScalarCodec.decode(raw, attribute.javaType(), attribute.conversion(), connection)
					: applied.fromDatabase(
							ScalarCodec.decode(raw, applied.databaseType(), attribute.conversion(), connection));
		});
	}

	private Object withContext(Attribute attribute, java.util.function.Supplier<Object> conversion) {
		try {
			return conversion.get();
		} catch (ScalarCodec.ConversionException e) {
			throw new ScalarCodec.ConversionException("attribute " + attribute.oracleName() + " of "
					+ oracleTypeName + ": " + e.getMessage(), e);
		}
	}

	/** The target is what Java declares: {@code List<T>} or {@code T[]}. */
	private Object decodeCollectionAttribute(Attribute attribute, Object raw, Connection connection)
			throws SQLException {
		Shape.CollectionOf collection = (Shape.CollectionOf) attribute.shape();
		requireNotOfCollections(attribute, collection);
		AppliedConverter applied = attribute.converter();
		Class<?> received = applied != null ? applied.databaseType() : attribute.javaElementType();
		Object[] source = toArray(attribute, OracleArrays.elements((java.sql.Array) raw, received));

		List<Object> decoded = new ArrayList<>(source.length);
		for (Object element : source) {
			if (applied != null) {
				decoded.add(withContext(attribute, () -> applied.fromDatabase(
						ScalarCodec.decode(element, received, attribute.conversion(), connection))));
			} else if (element == null) {
				decoded.add(null);
			} else if (collection.element() != null) {
				decoded.add(elementBinding(attribute, connection).fromStruct((Struct) element, connection));
			} else {
				decoded.add(ScalarCodec.decode(element, attribute.javaElementType(), attribute.conversion(), connection));
			}
		}

		if (attribute.javaType().isArray()) {
			Object array = java.lang.reflect.Array.newInstance(attribute.javaElementType(), decoded.size());
			for (int i = 0; i < decoded.size(); i++) {
				java.lang.reflect.Array.set(array, i, decoded.get(i));
			}
			return array;
		}
		return decoded;
	}

	private Object read(Object source, Attribute attribute) {
		try {
			attribute.reader().setAccessible(true);
			return attribute.reader().invoke(source);
		} catch (ReflectiveOperationException e) {
			throw new PlsqlMappingException("failed to read " + attribute.javaName()
					+ " of " + javaType.getName() + ": " + e.getMessage());
		}
	}
}
