# Types

The dictionary says which Oracle type each parameter, attribute and return value has, so the Java side declares only
the Java type. The library checks that the two fit before the call, and the [compatibility table](compatibility.md)
lists every pair that is tested.

## Scalars

| Oracle type | Usual Java types |
|---|---|
| `NUMBER`, `BINARY_INTEGER`, `PLS_INTEGER` | `BigDecimal`, `Integer`, `Long`, `Double`, and their primitives |
| `BINARY_DOUBLE`, `BINARY_FLOAT`, `FLOAT` | `Double`, `Float`, `BigDecimal` |
| `VARCHAR2`, `CHAR`, `NVARCHAR2`, `ROWID` | `String`, an enum by its `name()`, `UUID` |
| `CLOB` | `String`, `java.sql.Clob` |
| `BLOB`, `RAW` | `byte[]`, and `java.sql.Blob` for a `BLOB` |
| `DATE` | `LocalDate`, `LocalDateTime` |
| `TIMESTAMP` | `LocalDateTime` |
| `TIMESTAMP WITH TIME ZONE`, `TIMESTAMP WITH LOCAL TIME ZONE` | `OffsetDateTime`, `ZonedDateTime`, `Instant` |
| `INTERVAL DAY TO SECOND`, `INTERVAL YEAR TO MONTH` | `Duration`, `Period` |
| `BOOLEAN` | `boolean`, `Boolean` |

- **`NULL`** crosses as `null`. A primitive rejects `NULL` with a `PlsqlMappingException`, instead of making up a
  `false` or a `0`.
- **A value that does not fit,** such as `300` in a `byte` or a fraction in a `BigInteger`, is a
  `PlsqlMappingException`, instead of being rounded.
- **`Optional<T>`** is accepted as a return value, empty on `NULL`. As a parameter it is rejected: a parameter that
  may be absent is `null`.
- **Large values:** a `CLOB` above 32 KB goes in a temporary LOB, freed when the call ends. A `Clob` or `Blob` read
  from the database is a copy in memory, which does not depend on the connection.

For a type the library does not know, such as a value object of your domain, write a [converter](converters.md).

### Booleans

A Java `boolean` follows the type in the dictionary: `BOOLEAN` goes natively, `NUMBER` uses `1`/`0` and `VARCHAR2`
uses `Y`/`N`. If your schema stores other literals, declare `@PlsqlBoolean(trueValue = "S", falseValue = "N")` on
the attribute, on the parameter or, for the return value, on the method. Without it, reading an `S` fails, but
writing has no way to fail: the database receives `Y`.

### Dates and time zones

A `TIMESTAMP` with a time zone stores an instant, so only `OffsetDateTime`, `ZonedDateTime` or `Instant` fit: a
`LocalDateTime` is rejected, because it would lose the time zone without any error. An `Instant` is stored in UTC,
and that is how the routine sees it in a `TO_CHAR` or `EXTRACT`. For plain `DATE` and `TIMESTAMP`, the opposite
applies: `Instant`, `OffsetDateTime` and `ZonedDateTime` are rejected, because the type has nowhere to store the
time zone. The rule also applies to the elements of a collection. To drop the time zone on purpose, declare
`LocalDateTime` and convert in Java, as in `value.toLocalDateTime()`.

The result does not depend on the machine. A `LocalDateTime` or `LocalDate` crosses the driver as a local date and
time, never through the JVM's time zone, so a time that zone skips when daylight saving time starts, such as 02:30,
arrives unchanged; and the session's language and territory, which the driver takes from the JVM locale, do not
change any value. `java.util.Date`, `java.sql.Date` and `java.sql.Timestamp` are the exception: they hold an instant
in the JVM's time zone, so prefer `java.time`.

## Composite types

An OBJECT type, a package RECORD and a table's `%ROWTYPE` map to a Java `record` or to a JavaBean. No type name is
written: the dictionary gives it.

```java
// CREATE TYPE ADDRESS_T AS OBJECT (street VARCHAR2(100), num NUMBER)
public record Address(String street, Integer num) {
}

@PlsqlPackage("CUSTOMERS_PKG")
public interface Customers {
    // FUNCTION normalize(p_address ADDRESS_T) RETURN ADDRESS_T
    Address normalize(Address address);
}
```

- **Matched by name, never by position:** each attribute goes to the property with the same name, by the
  camelCase → SNAKE_CASE convention, whatever the order of the Java fields. Matching by position would silently swap
  two attributes of the same type.
- **Other names:** `@PlsqlAttr("FULL_NAME")` on a component or property gives the Oracle name. It accepts more than
  one, for a Java type that shows up with different spellings, such as a RECORD field and a cursor column.
- **Strict:** an Oracle attribute without a Java property is a `PlsqlMappingException`. Extra Java properties are
  allowed.
- **Nesting:** attributes can be other composites and collections, at any depth. OBJECT inheritance (`UNDER`) works
  too.
- **`@PlsqlType("ADDRESS_T")`** on the class is optional for a routine, and must agree with the dictionary if
  present. It is required in an [anonymous block](anonymous-blocks.md), which has no dictionary signature, and it
  breaks a tie between two overloads that take different OBJECT types.
- **JavaBeans** need a no-argument constructor and setters to be read; inherited properties count.
- **`null`:** a whole OBJECT can be `null`. A RECORD cannot be null in PL/SQL, so it comes back with null fields.

## Collections

A nested table or `VARRAY`, declared in the schema or in a package spec, maps to a `List<T>`. The element can be a
scalar, a composite or another collection.

```java
// CREATE TYPE NAME_LIST_T AS TABLE OF VARCHAR2(100)
// FUNCTION active_names(p_prefixes NAME_LIST_T) RETURN NAME_LIST_T
List<String> activeNames(List<String> prefixes);
```

- **As a parameter:** `List<T>`, `Set<T>`, `T[]` or a primitive array.
- **As a return value:** `List<T>`. A `NULL` collection comes back as an empty list.
- **Collections of collections:** `List<List<T>>`, or `T[][]` as a parameter, at any depth.
- **`INDEX BY` tables** can be read into a `List<T>`, in index order, but the indexes are lost. Writing one is rejected,
  because the driver numbers from 0. See the [roadmap](roadmap.md#writing-index-by-and-its-keys).

## Cursors

A `SYS_REFCURSOR` or a strongly typed `REF CURSOR` returned by a routine is read whole into a `List<T>`. Each row
becomes a `record` or a JavaBean, matched by column name as for a composite:

```java
public record Item(Long id, String description, BigDecimal quantity, LocalDate registeredAt) {
}

// FUNCTION items(p_count NUMBER) RETURN SYS_REFCURSOR
List<Item> items(int count);
```

- **Columns without a property** are ignored, since a query often brings more than one screen needs. A row type with
  no column in common is an error.
- **A cursor of a single column** can be read into a list of a scalar, as in `List<String>`.
- **An OBJECT column** can be read into a composite property.
- **`OUT` cursors** are a `PlsqlOut<List<T>>`.
- The cursor is read and closed during the call, so the list does not depend on the connection.
