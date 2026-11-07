package io.github.rponte.penitent.internal.binding;

import java.lang.reflect.Constructor;
import java.sql.Array;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Struct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.rponte.penitent.PlsqlAttr;
import io.github.rponte.penitent.PlsqlBoolean;
import io.github.rponte.penitent.convert.PlsqlConvert;
import io.github.rponte.penitent.convert.PlsqlConverters;
import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.internal.binding.JavaProperties.Assignment;
import io.github.rponte.penitent.internal.binding.JavaProperties.JavaProperty;
import io.github.rponte.penitent.internal.convert.AppliedConverter;
import io.github.rponte.penitent.internal.convert.ConverterLookup;
import io.github.rponte.penitent.internal.convert.ScalarCodec;
import io.github.rponte.penitent.internal.convert.ScalarCodec.ValueConversion;
import io.github.rponte.penitent.internal.metadata.CompositeType;

/**
 * The binding between a Java type and the rows of a {@code REF CURSOR}, matched by column name.
 *
 * <p>It is a sibling of {@link TypeBinding} and differs in one point that decides everything else:
 * the source of truth is not the dictionary but the {@link ResultSetMetaData}. The dictionary does
 * not say which columns a cursor returns, because that lives in the routine's body, not in its
 * signature. Hence validation happens on the first row read, not when the proxy is built.
 *
 * <p>A practical consequence: the cursor may name the same thing differently from the composite
 * type. {@code OrderItem.description} is {@code DESCRIPTION} in the record and
 * {@code ORDER_ITEM_DESCRIPTION} in the column, which is why {@link PlsqlAttr} accepts several
 * names.
 *
 * <p>An OBJECT column lands in a {@code record} or JavaBean property through {@link TypeBinding}, and a
 * cursor of a scalar, such as {@code List<String>}, is read from its only column.
 */
public final class CursorBinding {

	/**
	 * Where the binding of an OBJECT column comes from: the proxy's cache, the same one parameters and
	 * return values use, so that a type is read from the dictionary once.
	 */
	@FunctionalInterface
	public interface Bindings {
		TypeBinding of(Class<?> javaType, CompositeType type, Connection connection);
	}

	private final Class<?> javaType;
	private final Map<String, JavaProperty> properties;
	private final Constructor<?> recordConstructor;
	private final PlsqlConverters converters;
	private final Bindings bindings;

	private CursorBinding(Class<?> javaType, Map<String, JavaProperty> properties,
			Constructor<?> recordConstructor, PlsqlConverters converters, Bindings bindings) {
		this.converters = converters;
		this.javaType = javaType;
		this.properties = properties;
		this.recordConstructor = recordConstructor;
		this.bindings = bindings;
	}

	public static CursorBinding of(Class<?> javaType, PlsqlConverters converters, Bindings bindings) {
		if (ScalarCodec.isScalar(javaType)) {
			return new CursorBinding(javaType, Map.of(), null, converters, bindings);
		}
		return new CursorBinding(javaType, JavaProperties.of(javaType),
				javaType.isRecord() ? JavaProperties.canonicalConstructor(javaType) : null, converters, bindings);
	}

	/**
	 * Reads the whole cursor. Columns that no property claims are <b>ignored</b>, unlike what happens
	 * with a composite type: a cursor often carries working columns (keys, counters) that are not part
	 * of what one wants to map, and requiring full coverage would force declaring useless fields. What
	 * is not tolerated is the opposite, no column matching at all, because then the mapping is wrong
	 * and the result would be a list of empty objects.
	 *
	 * <p>A scalar element, or one with a converter, has no properties to match: it is read from the
	 * only column, and a cursor with more than one is an error, since there would be no telling which.
	 *
	 * @param conversion the literals of a {@code Boolean} element, from the method
	 * @param converter the converter of the element, or {@code null}
	 */
	public List<Object> readAll(ResultSet rs, Connection connection, ValueConversion conversion,
			AppliedConverter converter) throws SQLException {
		if (converter != null || ScalarCodec.isScalar(javaType)) {
			return readScalars(rs, connection, conversion, converter);
		}
		ResultSetMetaData metadata = rs.getMetaData();
		List<Column> columns = new ArrayList<>();
		List<String> ignored = new ArrayList<>();

		for (int i = 1; i <= metadata.getColumnCount(); i++) {
			String label = metadata.getColumnLabel(i);
			JavaProperty property = properties.get(JavaProperties.key(label));
			if (property == null) {
				ignored.add(label);
			} else {
				columns.add(new Column(i, property));
			}
		}

		if (columns.isEmpty()) {
			throw new PlsqlMappingException(javaType.getSimpleName()
					+ " matches no cursor column: " + ignored
					+ ". Available properties: " + properties.keySet()
					+ ". Use @PlsqlAttr to declare the column name.");
		}

		List<Object> rows = new ArrayList<>();
		while (rs.next()) {
			List<Assignment> assignments = new ArrayList<>(columns.size());
			for (Column column : columns) {
				JavaProperty property = column.property();
				ValueConversion propertyConversion = ValueConversion.from(property.annotation(PlsqlBoolean.class));
				String label = metadata.getColumnLabel(column.index());
				try {
					assignments.add(new JavaProperties.SimpleAssignment(property.name(), property.writer(),
							decode(rs, column.index(), label, property, propertyConversion, connection)));
				} catch (ScalarCodec.ConversionException e) {
					throw new ScalarCodec.ConversionException("cursor column " + label + ": " + e.getMessage(), e);
				}
			}
			rows.add(JavaProperties.instantiate(javaType, recordConstructor, assignments));
		}
		return rows;
	}

	private List<Object> readScalars(ResultSet rs, Connection connection, ValueConversion conversion,
			AppliedConverter converter) throws SQLException {
		ResultSetMetaData metadata = rs.getMetaData();
		if (metadata.getColumnCount() != 1) {
			List<String> labels = new ArrayList<>();
			for (int i = 1; i <= metadata.getColumnCount(); i++) {
				labels.add(metadata.getColumnLabel(i));
			}
			throw new PlsqlMappingException("a cursor of " + javaType.getSimpleName()
					+ " is read from its only column, but this one has " + labels.size() + ": " + labels
					+ ". Select a single column, or declare a record or JavaBean, matched by column name");
		}
		String label = metadata.getColumnLabel(1);
		List<Object> rows = new ArrayList<>();
		while (rs.next()) {
			Object raw = ScalarCodec.read(rs, 1, converter == null ? javaType : converter.databaseType());
			try {
				rows.add(converter == null
						? ScalarCodec.decode(raw, javaType, conversion, connection)
						: converter.fromDatabase(ScalarCodec.decode(raw, converter.databaseType(), conversion,
								connection)));
			} catch (ScalarCodec.ConversionException e) {
				throw new ScalarCodec.ConversionException("cursor column " + label + ": " + e.getMessage(), e);
			}
		}
		return rows;
	}

	/**
	 * A column is always a single value, so there is no per-element converter here. An OBJECT column
	 * comes as a {@link Struct}, which carries its own type name, {@code SCHEMA.TYPE}: the dictionary
	 * does the rest, as for an OBJECT parameter.
	 */
	private Object decode(ResultSet rs, int column, String label, JavaProperty property, ValueConversion conversion,
			Connection connection) throws SQLException {
		AppliedConverter applied = ConverterLookup.find(converters, property.annotation(PlsqlConvert.class),
				property.type(), property.elementType(), javaType.getSimpleName() + "." + property.name());
		Object raw = ScalarCodec.read(rs, column,
				applied == null || applied.elementWise() ? property.type() : applied.databaseType());
		if (applied == null && raw instanceof Struct struct && JavaProperties.isComposite(property.type())) {
			return bindings.of(property.type(), objectTypeOf(struct), connection).fromStruct(struct, connection);
		}
		if (applied == null && raw instanceof Array array && property.elementType() != null) {
			throw new PlsqlMappingException(javaType.getSimpleName() + "." + property.name() + ": cursor column "
					+ label + " is a collection (" + array.getBaseTypeName() + "), and a cursor column does not"
					+ " map to a List; select its elements with TABLE(), or return it as an attribute of an OBJECT"
					+ " column");
		}
		if (applied == null || applied.elementWise()) {
			return ScalarCodec.decode(raw, property.type(), conversion, connection);
		}
		return applied.fromDatabase(ScalarCodec.decode(raw, applied.databaseType(), conversion, connection));
	}

	/** Only an OBJECT can be a column: a RECORD or {@code %ROWTYPE} does not exist in SQL. */
	private static CompositeType objectTypeOf(Struct struct) throws SQLException {
		String qualified = struct.getSQLTypeName();
		int dot = qualified.indexOf('.');
		String schema = qualified.substring(0, dot);
		String name = qualified.substring(dot + 1);
		return new CompositeType(CompositeType.Kind.OBJECT, schema, null, name, name);
	}

	private record Column(int index, JavaProperty property) {
	}
}
