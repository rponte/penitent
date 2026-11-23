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
import oracle.sql.json.OracleJsonParser;
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
	 * The JSON text of a value: a {@code CharSequence} as it is, once {@link #requireValid checked}, since it already is
	 * the text; anything else written as a document. Inside it, only what JSON has a form for: a {@code Map} with
	 * {@code String} keys, a {@code List}, a {@code CharSequence}, a {@code Boolean}, a finite number and {@code null}.
	 * Anything else is refused, naming where it is, rather than written in a form of the library's choosing; so is a
	 * number beyond {@code NUMBER}'s range, and a lone surrogate, as in a text.
	 *
	 * @throws ScalarCodec.ConversionException for a text that is not JSON, or a value JSON has no form for
	 */
	public static String text(Object value) {
		if (value == null || value instanceof CharSequence) {
			if (value == null) {
				return null;
			}
			String text = value.toString();
			requireValid(text);
			return text;
		}
		StringWriter out = new StringWriter();
		try (OracleJsonGenerator generator = FACTORY.createJsonTextGenerator(out)) {
			write(generator, value, "$");
		}
		return out.toString();
	}

	/**
	 * A JSON text checked the same way with every driver, before the call (#140). Without it, drivers 21 and 23 parsed
	 * the text themselves while binding, each with errors of its own, and with driver 19 the database's {@code JSON()}
	 * took "almost JSON", lax by default, and corrected it silently: a leading zero, single quotes, a trailing comma,
	 * {@code True}, a byte order mark, and on 23ai a comment and {@code NaN}, as the string {@code "Nan"}. Checked here,
	 * in one pass of the driver's streaming text parser:
	 * <ul>
	 * <li>the JSON specification's syntax, strictly, as drivers 21 and 23 already did. The message is the parser's first
	 * sentence, with the line and the column, which is the same in drivers 19, 21 and 23; driver 23 only adds an
	 * {@code ORA-} code and a link, left out;</li>
	 * <li>an empty or a blank text, which driver 19 sent as SQL {@code NULL};</li>
	 * <li>a key twice in the same object, which the parser does not report;</li>
	 * <li>a number beyond {@code NUMBER}'s range, which the JSON type stores numbers as. The streaming parser, unlike the
	 * tree one, takes it; drivers 21 and 23 refused it with a bare {@code IllegalArgumentException}, and with driver 19
	 * the database refused it on 21c, took it on 23ai, and stored {@code 1e-200} as 0 on 21c;</li>
	 * <li>a lone UTF-16 surrogate, which is no character, and which drivers 21 and 23 silently store as {@code ?}.</li>
	 * </ul>
	 * The limits of the binary form are left to the driver and the database, which set them by version: a key's length
	 * (256 bytes with driver 21, 65,535 with driver 23, and the database's own) and the depth of nesting. A new driver
	 * or database that raises them then just works.
	 *
	 * @throws ScalarCodec.ConversionException with no context: whoever knows the parameter adds it
	 */
	static void requireValid(String text) {
		if (text.isBlank()) {
			throw new ScalarCodec.ConversionException("the text is empty, which is not JSON: send null for SQL NULL");
		}
		java.util.Deque<Frame> frames = new java.util.ArrayDeque<>();
		try (OracleJsonParser parser = FACTORY.createJsonTextParser(new StringReader(text))) {
			while (parser.hasNext()) {
				OracleJsonParser.Event event = parser.next();
				switch (event) {
					case START_OBJECT, START_ARRAY -> frames.push(new Frame(pathOf(frames), event == OracleJsonParser.Event.START_ARRAY));
					case END_OBJECT, END_ARRAY -> {
						frames.pop();
						valueDone(frames);
					}
					case KEY_NAME -> {
						Frame object = frames.peek();
						String key = parser.getString();
						requireNoLoneSurrogate(key, object.path + " has a key that");
						if (!object.keys.add(key)) {
							throw new ScalarCodec.ConversionException(object.path + " has the key \"" + key
									+ "\" twice, and the keys of a JSON object are unique");
						}
						object.key = key;
					}
					case VALUE_STRING -> {
						requireNoLoneSurrogate(parser.getString(), pathOf(frames));
						valueDone(frames);
					}
					case VALUE_DECIMAL, VALUE_DOUBLE, VALUE_FLOAT -> {
						requireWithinNumber(parser.getBigDecimal(), pathOf(frames));
						valueDone(frames);
					}
					default -> valueDone(frames);
				}
			}
		} catch (OracleJsonException e) {
			throw new ScalarCodec.ConversionException("the text is not JSON: " + firstSentence(e.getMessage()), e);
		}
	}

	/** An object or an array being read, with its path, and for an object its keys so far. */
	private static final class Frame {
		final String path;
		final boolean array;
		final java.util.Set<String> keys = new java.util.HashSet<>();
		String key;
		int index;

		Frame(String path, boolean array) {
			this.path = path;
			this.array = array;
		}
	}

	/** The path of the value being read: {@code $} at the top, then a key or an index for each level. */
	private static String pathOf(java.util.Deque<Frame> frames) {
		Frame parent = frames.peek();
		if (parent == null) {
			return "$";
		}
		return parent.array ? parent.path + "[" + parent.index + "]" : parent.path + step(parent.key);
	}

	private static void valueDone(java.util.Deque<Frame> frames) {
		Frame parent = frames.peek();
		if (parent != null && parent.array) {
			parent.index++;
		}
	}

	/**
	 * The parser's first sentence, which drivers 19, 21 and 23 write the same, as in "Unexpected character 'b' at line
	 * 1, column 2": driver 23 adds an {@code ORA-} code before it and a link after it, and the sentences after it differ
	 * in their punctuation.
	 */
	private static String firstSentence(String message) {
		String sentence = String.valueOf(message).replaceFirst("^ORA-\\d+:\\s*", "");
		// a period followed by a space, a line break before driver 23's link, or the end
		java.util.regex.Matcher end = java.util.regex.Pattern.compile("\\.(\\s|$)").matcher(sentence);
		return (end.find() ? sentence.substring(0, end.start()) : sentence).strip();
	}

	private static void requireWithinNumber(BigDecimal number, String path) {
		try {
			OracleNumberLimits.requireWithin(number);
		} catch (ScalarCodec.ConversionException e) {
			throw beyondNumber(number, path, e);
		}
	}

	private static ScalarCodec.ConversionException beyondNumber(Object number, String path, Exception cause) {
		return new ScalarCodec.ConversionException(path + " is " + number + ", beyond the range of Oracle's NUMBER, whose"
				+ " magnitude goes from 1E-130 to just under 1E+126, besides 0, which the JSON type stores numbers as:"
				+ " keep such a number as a JSON string", cause);
	}

	/**
	 * A lone UTF-16 surrogate, half of a character beyond the Basic Multilingual Plane, such as {@code \\uD800}:
	 * drivers 21 and 23 store it as {@code ?}, silently, measured.
	 *
	 * @param where the path, or the path and "has a key that", for a key
	 */
	private static void requireNoLoneSurrogate(String text, String where) {
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			boolean paired = Character.isHighSurrogate(c) && i + 1 < text.length()
					&& Character.isLowSurrogate(text.charAt(i + 1));
			if (paired) {
				i++;
			} else if (Character.isSurrogate(c)) {
				String escaped = String.format(java.util.Locale.ROOT, "\\u%04X", (int) c);
				throw new ScalarCodec.ConversionException(where + " holds a lone UTF-16 surrogate, " + escaped
						+ ", which is no character, and which the driver would store as '?': send the whole character,"
						+ " both surrogates");
			}
		}
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
				requireNoLoneSurrogate(key, path + " has a key that");
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
			requireNoLoneSurrogate(text.toString(), path);
			generator.write(text.toString());
		} else if (value instanceof Boolean flag) {
			generator.write(flag);
		} else if (value instanceof Number number) {
			BigDecimal decimal = decimal(number, path);
			try {
				OracleNumberLimits.requireWithin(decimal);
			} catch (ScalarCodec.ConversionException e) {
				throw beyondNumber(number, path, e);
			}
			generator.write(decimal);
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
