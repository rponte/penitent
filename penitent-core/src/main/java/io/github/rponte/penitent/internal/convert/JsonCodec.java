package io.github.rponte.penitent.internal.convert;

import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import oracle.sql.json.OracleJsonArray;
import oracle.sql.json.OracleJsonException;
import oracle.sql.json.OracleJsonFactory;
import oracle.sql.json.OracleJsonGenerator;
import oracle.sql.json.OracleJsonObject;
import oracle.sql.json.OracleJsonValue;

/**
 * Oracle's JSON type in Java, beyond its text: a {@code Map<String, Object>} for an object, a {@code List<Object>} for
 * an array, and a number or a {@code Boolean} for a scalar at the top. A {@code String} is always the JSON text, and
 * goes as it is.
 *
 * <p>The text is parsed and written with the driver's own JSON API, {@code oracle.sql.json}, which every supported
 * driver ships: no other dependency. The value crosses JDBC as text either way, so the mapping is the same whether
 * the driver knows the JSON type or the call converts the text in the database.
 *
 * <p><b>Numbers.</b> Oracle's JSON type keeps a number's value, not its written form: {@code 24.90} comes back as
 * {@code 24.9}, and an integer and a decimal look the same. So every number inside a {@code Map} or a {@code List} is
 * a {@code BigDecimal}, which loses nothing beyond what the database already did. A number at the top becomes the
 * declared numeric type with the library's exact conversion.
 */
public final class JsonCodec {

	private static final OracleJsonFactory FACTORY = new OracleJsonFactory();

	private static final Pattern PLAIN_KEY = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

	private JsonCodec() {
	}

	/**
	 * Whether a Java type maps to a JSON value the library reads and writes: its text, an object, an array or a scalar
	 * at the top. Only the interfaces {@code Map} and {@code List}: what comes back is a {@code LinkedHashMap} and an
	 * {@code ArrayList}, which a {@code HashMap} or a {@code LinkedList} would not hold.
	 */
	public static boolean accepts(Class<?> type) {
		return CharSequence.class.isAssignableFrom(type) || type == Map.class || type == List.class
				|| type == Boolean.class || type == boolean.class
				|| (isNumeric(type) && (type.isPrimitive() || ScalarCodec.isScalar(type)));
	}

	/**
	 * What is wrong with the type arguments of a {@code Map} or a {@code List} declared for a JSON value, or null when
	 * nothing is: a document holds values of many types, so only {@code Map<String, Object>} and {@code List<Object>},
	 * or their wildcards, describe it. Anything narrower would let a value of another type through, unchecked.
	 */
	public static String typeArgumentProblem(Type declared) {
		if (!(declared instanceof ParameterizedType parameterized)) {
			return null;
		}
		Type raw = parameterized.getRawType();
		Type[] arguments = parameterized.getActualTypeArguments();
		if (raw == Map.class && !(arguments[0] == String.class && isAny(arguments[1]))) {
			return "declared as " + declared.getTypeName() + ": declare Map<String, Object> for an object, whose"
					+ " values are of many types";
		}
		if (raw == List.class && !isAny(arguments[0])) {
			return "declared as " + declared.getTypeName() + ": declare List<Object> for an array, whose elements"
					+ " are of many types";
		}
		return null;
	}

	private static boolean isAny(Type type) {
		return type == Object.class || (type instanceof WildcardType wildcard
				&& wildcard.getLowerBounds().length == 0 && wildcard.getUpperBounds()[0] == Object.class);
	}

	/**
	 * The value the declared type takes from the JSON text: the text itself for a {@code CharSequence} or an
	 * {@code Object}; otherwise the parsed document, which must fit the type. A number is a {@code BigDecimal}, which
	 * {@link ScalarCodec#decode} turns into the declared numeric type, exactly.
	 *
	 * @throws ScalarCodec.ConversionException when the document does not fit, or the driver's parser does not read it
	 */
	public static Object read(String text, Class<?> declared) {
		if (text == null || declared == Object.class || CharSequence.class.isAssignableFrom(declared)) {
			return text;
		}
		OracleJsonValue document = parse(text);
		OracleJsonValue.OracleJsonType type = document.getOracleJsonType();
		if (type == OracleJsonValue.OracleJsonType.NULL) {
			return null;
		}
		boolean fits = switch (type) {
			case OBJECT -> declared == Map.class;
			case ARRAY -> declared == List.class;
			case TRUE, FALSE -> declared == Boolean.class || declared == boolean.class;
			case DECIMAL, DOUBLE, FLOAT -> isNumeric(declared);
			default -> false;
		};
		if (!fits) {
			throw new ScalarCodec.ConversionException("the document is " + kindOf(type) + ", which "
					+ declared.getSimpleName() + " does not hold: " + suggestionFor(type));
		}
		return toJava(document);
	}

	private static OracleJsonValue parse(String text) {
		try {
			return FACTORY.createJsonTextValue(new StringReader(text));
		} catch (IllegalArgumentException e) {
			// the parser keeps numbers as Oracle NUMBERs, whose range is about 1e-130 to 1e126, and says no more
			throw new ScalarCodec.ConversionException("the document holds a number that the driver's JSON parser does"
					+ " not read, beyond the range of Oracle's NUMBER, about 1e-130 to 1e126: declare String for its"
					+ " text", e);
		} catch (OracleJsonException e) {
			throw new ScalarCodec.ConversionException("the driver's JSON parser does not read the document: "
					+ e.getMessage(), e);
		}
	}

	private static Object toJava(OracleJsonValue value) {
		return switch (value.getOracleJsonType()) {
			case OBJECT -> {
				OracleJsonObject object = value.asJsonObject();
				Map<String, Object> map = new LinkedHashMap<>();
				for (Map.Entry<String, OracleJsonValue> entry : object.entrySet()) {
					map.put(entry.getKey(), toJava(entry.getValue()));
				}
				yield map;
			}
			case ARRAY -> {
				OracleJsonArray array = value.asJsonArray();
				List<Object> list = new ArrayList<>(array.size());
				for (OracleJsonValue element : array) {
					list.add(toJava(element));
				}
				yield list;
			}
			case STRING -> value.asJsonString().getString();
			case DECIMAL, DOUBLE, FLOAT -> value.asJsonNumber().bigDecimalValue();
			case TRUE -> Boolean.TRUE;
			case FALSE -> Boolean.FALSE;
			case NULL -> null;
			// a text document holds none of OSON's own types, such as a timestamp: they come back as strings
			default -> throw new ScalarCodec.ConversionException("the document holds a value of OSON's type "
					+ value.getOracleJsonType() + ", which has no form in JSON text");
		};
	}

	private static String kindOf(OracleJsonValue.OracleJsonType type) {
		return switch (type) {
			case OBJECT -> "a JSON object";
			case ARRAY -> "a JSON array";
			case STRING -> "a JSON string";
			case TRUE, FALSE -> "a JSON boolean";
			case DECIMAL, DOUBLE, FLOAT -> "a JSON number";
			default -> "a JSON " + type;
		};
	}

	private static String suggestionFor(OracleJsonValue.OracleJsonType type) {
		return switch (type) {
			case OBJECT -> "declare Map<String, Object>, or String for its text";
			case ARRAY -> "declare List<Object>, or String for its text";
			case TRUE, FALSE -> "declare Boolean, or String for its text";
			case DECIMAL, DOUBLE, FLOAT -> "declare BigDecimal or another numeric type, or String for its text";
			default -> "declare String for its text";
		};
	}

	/**
	 * The JSON text of a value: a {@code CharSequence} as it is, since it already is the text; anything else written as
	 * a document. Inside it, only what JSON has a form for: a {@code Map} with {@code String} keys, a {@code List}, a
	 * {@code CharSequence}, a {@code Boolean}, a finite number and {@code null}. Anything else is refused, naming where
	 * it is, rather than written in a form of the library's choosing.
	 *
	 * @throws ScalarCodec.ConversionException for a value JSON has no form for
	 */
	public static String text(Object value) {
		if (value == null || value instanceof CharSequence) {
			return value == null ? null : value.toString();
		}
		StringWriter out = new StringWriter();
		// the writer takes a number beyond the range of Oracle's NUMBER, such as 1e200: the bind or the read refuses it
		try (OracleJsonGenerator generator = FACTORY.createJsonTextGenerator(out)) {
			write(generator, value, "$");
		}
		return out.toString();
	}

	private static void write(OracleJsonGenerator generator, Object value, String path) {
		if (value == null) {
			generator.writeNull();
		} else if (value instanceof Map<?, ?> map) {
			generator.writeStartObject();
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				if (!(entry.getKey() instanceof String key)) {
					throw new ScalarCodec.ConversionException(path + " has a key " + describe(entry.getKey())
							+ ", and a JSON object's keys are text: use String keys");
				}
				generator.writeKey(key);
				write(generator, entry.getValue(), path + step(key));
			}
			generator.writeEnd();
		} else if (value instanceof List<?> list) {
			generator.writeStartArray();
			for (int i = 0; i < list.size(); i++) {
				write(generator, list.get(i), path + "[" + i + "]");
			}
			generator.writeEnd();
		} else if (value instanceof CharSequence text) {
			generator.write(text.toString());
		} else if (value instanceof Boolean flag) {
			generator.write(flag);
		} else if (value instanceof Number number) {
			generator.write(decimal(number, path));
		} else {
			throw new ScalarCodec.ConversionException(path + " is a " + value.getClass().getName()
					+ ", which JSON has no form for: put a String, a Number, a Boolean, a Map or a List there, or"
					+ " use a converter");
		}
	}

	/**
	 * A number as a decimal, with the value it shows: a {@code double} as {@code Double.toString} writes it, so
	 * {@code 0.1} stays {@code 0.1}, not the binary fraction behind it.
	 */
	private static BigDecimal decimal(Number number, String path) {
		if (number instanceof BigDecimal decimal) {
			return decimal;
		}
		if (number instanceof BigInteger integer) {
			return new BigDecimal(integer);
		}
		if (number instanceof Integer || number instanceof Long || number instanceof Short
				|| number instanceof Byte) {
			return BigDecimal.valueOf(number.longValue());
		}
		if (number instanceof Double || number instanceof Float) {
			double value = number.doubleValue();
			if (!Double.isFinite(value)) {
				throw new ScalarCodec.ConversionException(path + " is " + number
						+ ", which JSON has no form for: a JSON number is finite");
			}
			return new BigDecimal(number.toString());
		}
		throw new ScalarCodec.ConversionException(path + " is a " + number.getClass().getName()
				+ ", a Number whose value the library does not know how to read exactly: convert it to a"
				+ " BigDecimal");
	}

	private static String step(String key) {
		return PLAIN_KEY.matcher(key).matches() ? "." + key : "[\"" + key.replace("\"", "\\\"") + "\"]";
	}

	private static String describe(Object key) {
		return key == null ? "null" : "of type " + key.getClass().getName() + " (" + key + ")";
	}

	private static boolean isNumeric(Class<?> type) {
		return Number.class.isAssignableFrom(type)
				|| (type.isPrimitive() && type != boolean.class && type != char.class && type != void.class);
	}
}
