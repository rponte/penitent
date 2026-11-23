# Compatibility

Status of version `0.31.0`, measured on Java 17 and 21, with Oracle 23ai
(`gvenzl/oracle-free:23.26.3`) and `ojdbc17`; the whole suite also runs with `ojdbc11` and `ojdbc8`, version
23.26.3, and with drivers 21.24.0.0 and 19.33.0.0, as [Driver versions](#driver-versions) details, and against
Oracle 21c and Oracle 19c with every one of those drivers, as [Database versions](#database-versions) details. Versions up to `0.22.0` need
Java 21.

| Status | Meaning |
|---|---|
| **Validated** | Has an active test in the library's build. A regression breaks the build. |
| **Not supported** | Fails with an exception. The message does not always explain the cause. |

The rows marked "with a clear error" are deliberate rejections: the rejection is the current behavior, and an active
test guarantees the message. There are no `@Disabled` tests left; the few plain "Not supported" rows are gaps without
a test.

For what each feature does, see the [documentation](README.md).

## Driver versions

The Oracle JDBC driver has a version of its own, apart from the database's: drivers 21 and 19 connect to Oracle 23ai
too. The number in the artifact, `ojdbc17`, `ojdbc11`, `ojdbc10` or `ojdbc8`, is the Java version the jar is built
for, not the driver version, and each driver version comes in its own set: `ojdbc17` only from 23 on, `ojdbc10` only
in 19.

| Driver | Artifacts | Status |
|---|---|---|
| 23.26.3 | `ojdbc17`, `ojdbc11`, `ojdbc8` | Validated: the whole suite |
| 21.24.0.0 | `ojdbc11`, `ojdbc8` | Validated: the whole suite, with the differences below |
| 19.33.0.0 | `ojdbc10`, `ojdbc8` | Validated: the whole suite, with the differences below |
| 18.x and older | | Not measured |

Before driver 23, the driver does some things differently. The library works around them, so that the same code
gives the same result on every version, except for one case the driver cannot do at all. These apply to drivers 21
and 19:

| Before driver 23, the driver… | What the library does | Status |
|---|---|---|
| converts `LocalDateTime` and `LocalDate` through the JVM's time zone, in both directions: 02:30 on the day the clocks jump becomes 03:30 | Builds and reads the bytes of `DATE` and `TIMESTAMP` itself, without a time zone | Validated |
| has no `java.time` methods for intervals | Builds and reads the bytes of `INTERVAL` itself | Validated |
| fails with `ClassCastException` reading a `TIMESTAMP WITH LOCAL TIME ZONE` behind a pool | Reads the bytes itself (see driver 19 below), and asks the driver's own connection, not the pool's proxy, for the time zones | Validated |
| refuses the standard `Types.BOOLEAN` ("Invalid column type: 16"), and reads a `null` `BOOLEAN` as `false` | Binds `BOOLEAN` as `OracleTypes.PLSQL_BOOLEAN` on every version, and asks the driver whether the value was `null` | Validated |
| does not resolve `SCHEMA.TABLE%ROWTYPE`, because it puts the whole name in quotes | Binds `"SCHEMA".TABLE%ROWTYPE`, which every version resolves | Validated |
| cannot read a `BOOLEAN` column in a cursor opened in PL/SQL: it fails inside, before the library sees the data, and leaves the connection unusable. The database converts the column to `NUMBER` for such a driver only in a plain query, which a cursor is not | Cannot make it work, because the query is yours. It discards the connection, so that the pool replaces it, and fails with a `PlsqlExecutionException` that says so. Convert the column in the query, as in `CASE WHEN flag THEN 'Y' WHEN NOT flag THEN 'N' END`, which keeps `null`, and read it into a `Boolean` | Not supported, with a clear error; the conversion in the query is Validated |
| writes its error messages without the `ORA-` number and without the link to the error's page | Nothing: the messages reach you as the driver writes them. `getErrorCode()` is the same on every version | Validated |

Driver 19 also differs in the two types with a time zone. The library reads their bytes itself on every driver, as
driver 23 does, and builds them where driver 19 gets them wrong:

| Driver 19… | What the library does | Status |
|---|---|---|
| has no `toZonedDateTime()` for a `TIMESTAMP WITH TIME ZONE`, only methods that give an `OffsetDateTime` and lose the region | Reads the instant and the time zone from the bytes, and the region's name from the driver's `ZONEIDMAP`, the same table on every version | Validated |
| has no `zonedDateTimeValue` for a `TIMESTAMP WITH LOCAL TIME ZONE`, and converts it through `java.util.Calendar`, with a fixed offset of the session | Reads the database's local time from the bytes, and moves it to the session's time zone | Validated |
| stores a `TIMESTAMP WITH LOCAL TIME ZONE` attribute of an OBJECT, or element of a collection, at the value's local time without its offset: 10:30-03:00 became 10:30 UTC, three hours off, without any error | Builds the bytes itself, at the value's local time in the database's time zone. A parameter does not have the problem | Validated |

## Database versions

Penitent reads the same dictionary views and binds the same way on every database version, so nothing changes in your
code from one to another. The whole suite runs against each database below, with every driver above:

| Database | Image | Status |
|---|---|---|
| Oracle 23ai | `gvenzl/oracle-free:23.26.3-slim-faststart` | Validated: the whole suite |
| Oracle 21c | `gvenzl/oracle-xe:21-slim-faststart` (21.3) | Validated: the whole suite, with the differences below |
| Oracle 19c | `ghcr.io/oracle/adb-free:latest` (19.33) | Validated: the whole suite, with the differences below |

Oracle 19c is measured on Oracle's Autonomous Database Free, the 19c image Oracle publishes for anyone to pull. It is
Enterprise Edition with a recent release update and the same PL/SQL as any other 19c. The tests run as a regular
user with the usual grants, and none of them depends on a feature of the Autonomous Database. Oracle 19c and 21c
behave the same in every test.

Before 23ai, `BOOLEAN` exists only in PL/SQL. In PL/SQL it works as on 23ai: a parameter, a return value, an `OUT`,
the field of a RECORD and a block's `:result`, with `true`, `false` and `null`.

| Before Oracle 23ai… | What the library does | Status |
|---|---|---|
| the database has no SQL `BOOLEAN`: no `BOOLEAN` attribute of an OBJECT, no `BOOLEAN` column, no `select true` | Nothing to do: the database refuses the DDL or the query. Map the attribute or the column as `VARCHAR2` or `NUMBER`, which the library reads into a `Boolean`, by `Y`/`N` or `1`/`0` | Not supported by the database |
| with driver 23 or later, a `null` `BOOLEAN` that comes back, as a return value, an `OUT`, an `IN OUT` or a block's `:result`, reads as `true` or `false`. `true` and `false` themselves read right, and so does a `BOOLEAN` that goes in, `null` included | Warns, once per method, in the `io.github.rponte.penitent.call` log, at `WARN`. It cannot read the value right: see [below](#a-null-boolean-from-driver-23-before-23ai) | Not supported by the driver, with a warning |

### A null BOOLEAN from driver 23 before 23ai

Driver 23 talks to a database before 23ai in a format of its own for a `null` `BOOLEAN`, and then reads the answer
by its length alone, without the null indicator the database sends: the `null` arrives as `true` or `false`, with
nothing to tell it apart, and `wasNull()` says it was not null. Drivers 21 and 19 read the same answer right. It
happens in versions 23.26.3 and 23.26.4, whatever the connection properties, and nothing on the JDBC side avoids it.
A test in the build pins the driver's behavior, so a fixed driver will show up there.

Any of these keeps the `null`:

1. **Use the driver of your database's version**, such as 21 against Oracle 21c or 19 against Oracle 19c: the whole
   suite passes with them, `null` included, and nothing else changes.
2. **Hand the value back as `VARCHAR2` or `NUMBER`.** Penitent reads both into a `Boolean`, `Y`/`N` and `1`/`0`
   by default, or the literals of `@PlsqlBoolean`, and keeps the `null`, so the Java method stays the same. A
   function of its own leaves the original one as it is for the PL/SQL that calls it:

   <!-- snippet: fixture -->
   ```sql
   FUNCTION is_active_yn(p_id NUMBER) RETURN VARCHAR2 IS
     l_active BOOLEAN := is_active(p_id);
   BEGIN
     RETURN CASE WHEN l_active THEN 'Y' WHEN NOT l_active THEN 'N' END;
   END;
   ```

   <!-- snippet: compatibility/boolean-as-text -->
   ```java
   @PlsqlRoutine("IS_ACTIVE_YN")
   Boolean isActive(long id); // null stays null
   ```

   Keep both `WHEN`s: with `ELSE 'N'`, a `null` would become `false`. Declare `Boolean`, not `boolean`, where the
   value can be `null`.
3. **Move to Oracle 23ai**, where driver 23 reads the native `BOOLEAN` right.

Where the PL/SQL guarantees the value is never `null`, driver 23 works as it is.

## Scalar types as parameters and return values

| Oracle type | Java type | Status |
|---|---|---|
| `NUMBER` | `BigDecimal`, `Integer`, `Long`, `Double`, `int` | Validated |
| `NUMBER` | `Object` (receives the driver's value) | Validated |
| `NUMBER` | `Float`, `float` | Validated |
| `NUMBER` | `Short`, `short`, `Byte`, `byte`, `BigInteger`: a value that does not fit, or a fraction in `BigInteger`, is `PlsqlMappingException` instead of being rounded | Validated |
| `BINARY_INTEGER`, `PLS_INTEGER` | `Integer` | Validated |
| `FLOAT`, `BINARY_DOUBLE`, `BINARY_FLOAT` as return value | `BigDecimal`, `Double`, `Float` | Validated, with `NLS_TERRITORY` `AMERICA` and `BRAZIL` |
| `BINARY_DOUBLE_NAN` as return value | `Double` | Validated |
| `NaN` and the infinities sent to a `BINARY_DOUBLE` or a `BINARY_FLOAT`: as `IN`, `IN OUT`, an attribute of an object type, an element of a collection and a parameter of a block, bound through the driver's `setBinaryDouble` and `setBinaryFloat` | `Double`, `Float` | Validated, on drivers 23, 21 and 19 |
| `NaN` or an infinity sent to a `NUMBER` | `Double`, `Float` | Rejected with `PlsqlMappingException`, before the call, naming the parameter: `NUMBER` holds neither |
| A number at `NUMBER`'s edges, a magnitude of `1E-130` and of `9.` followed by 40 nines `E+125`, positive and negative, and 0 | `BigDecimal`, `BigInteger`, `Double` | Arrives exactly | Validated |
| A number beyond `NUMBER`'s range, too large or too small, as a parameter, `IN OUT`, an attribute, an element or a block's parameter. Before #141's fix, the driver refused it while binding with a message of its own: `ORA-17001`, SQLState `72000`, with driver 23, "Internal Error", SQLState `99999`, with drivers 21 and 19, `ORA-17059` for an attribute, and a bare `IllegalArgumentException` for a `Double` | `BigDecimal`, `BigInteger`, `Double`, `Float` | Rejected with `PlsqlMappingException`, before the call, naming the value and where it is, the same on every driver. A `BINARY_DOUBLE` or `BINARY_FLOAT` takes it | Validated |
| A number with more digits than `NUMBER` holds | `BigDecimal` | Digits beyond 40 dropped, with no error, as Oracle does | Validated |
| `VARCHAR2` | `String`, enum by `name()`, `Boolean` by `Y`/`N` | Validated |
| `VARCHAR2` up to 32 KB | `String` | Validated |
| `VARCHAR2` with a value other than `Y` and `N` | `Boolean` | Validated: `PlsqlMappingException` citing the value |
| `VARCHAR2` | `char`, `Character`: a text with more than one character is `PlsqlMappingException` | Validated |
| `VARCHAR2` | `UUID`, as the 36-character text | Validated |
| Return value | `Optional<T>` of any supported type: empty on `NULL` | Validated |
| Parameter | `Optional<T>`: rejected, a parameter that may be absent is `null` | Not supported, with a clear error |
| `CHAR`, `NVARCHAR2` | `String` | Validated |
| `ROWID` | `String` | Validated |
| Short `CLOB` | `String` | Validated |
| `CLOB` above 32 KB, as parameter, return value and `OUT`: the value goes in a temporary LOB, freed when the call ends | `String` | Validated |
| `CLOB` | `java.sql.Clob`, read as a copy in memory that does not depend on the connection | Validated |
| `BLOB`, including above 32 KB | `byte[]`, `java.sql.Blob` (a copy in memory) | Validated |
| `RAW` | `byte[]` | Validated |
| `NULL` in `CLOB` and `BLOB` | `null` | Validated |
| `DATE` | `LocalDate` | Validated |
| `DATE` | `LocalDateTime`, `java.sql.Date` | Validated |
| `DATE` | `java.util.Date`, `java.sql.Timestamp`, keeping the time. Like `LocalDateTime`, they are rejected in a type with a time zone | Validated |
| `TIMESTAMP` | `LocalDateTime` | Validated |
| `TIMESTAMP WITH TIME ZONE` | `OffsetDateTime`, `ZonedDateTime` (keeps the region), `Instant` (stored in UTC) | Validated |
| `TIMESTAMP WITH LOCAL TIME ZONE` | `OffsetDateTime`, `ZonedDateTime`, `Instant`, in the session time zone | Validated |
| Type with time zone in `LocalDateTime`, `LocalDate`, `java.util.Date`, `java.sql.Date` or `java.sql.Timestamp` | Rejected with `PlsqlMappingException`, before the call | Validated |
| `DATE` or `TIMESTAMP` in `Instant`, `OffsetDateTime` or `ZonedDateTime` | Rejected with `PlsqlMappingException`, before the call | Validated |
| Overload between `TIMESTAMP` and `TIMESTAMP WITH TIME ZONE` | The Java type picks the version | Validated |
| `INTERVAL DAY TO SECOND` | `Duration`, negative included | Validated |
| `INTERVAL YEAR TO MONTH` | `Period` | Validated |
| `Period` with days | Rejected with `PlsqlMappingException`, before the call: `INTERVAL YEAR TO MONTH` does not store them | Validated |
| `DATE` and `TIMESTAMP` with the JVM in a time zone with daylight saving time: a local time that zone skips (02:30 when the clocks jump) or repeats, and a day without midnight | `LocalDateTime`, `LocalDate`, unchanged as parameter, return value, `OUT`, block, cursor column, attribute and collection element | Validated |
| `DATE`, `TIMESTAMP` and the types with a time zone at the edges of Oracle's range, January 1, 4712 BC (`LocalDate.of(-4711, 1, 1)`) and December 31, 9999, as parameter, `IN OUT`, attribute, element and a block's bind | Arrive exactly, and come back | Validated |
| A date beyond Oracle's range, as `LocalDate`, `LocalDateTime`, `OffsetDateTime`, `ZonedDateTime`, `Instant`, `java.util.Date` or `java.sql.Timestamp`; a type with a time zone also beyond in UTC only, as `9999-12-31T23:00-03:00`. Before #142's fix, the year 10000 was stored as bytes Oracle cannot format, and the year 25620, whose century wraps around a byte, as the year 20; drivers 21 and later stored a type with a time zone beyond invalid too | Rejected with `PlsqlMappingException`, before the call, naming the value and the parameter, the attribute, the element or the block's parameter. The same on every driver | Validated |
| A date before October 15, 1582, which Oracle counts in the Julian calendar | `LocalDate` and `LocalDateTime` keep their fields both ways; a `java.util.Date`, Julian before 1582 too, crosses by its instant, so the same day arrives with other fields: `1500-03-01` as `1500-02-20` | Validated |
| Session `NLS_LANGUAGE` and `NLS_TERRITORY` of `AMERICA` and `BRAZIL`, as the driver derives them from the JVM locale | Dates, timestamps with and without time zone, and intervals cross unchanged, also as attribute, element and cursor column | Validated |
| `BOOLEAN` as return value | `boolean`, `Boolean` | Validated |
| `BOOLEAN` as parameter, including `null` | `boolean`, `Boolean` | Validated |
| `BOOLEAN` coming back `null`, as return value, `OUT` or `IN OUT` | `Boolean` `null`, not `false` | Validated |
| `NUMBER` as parameter | `boolean`, `Boolean`, as `1`/`0` or the numeric literals of `@PlsqlBoolean` | Validated |
| `NUMBER` as return value | `boolean`, `Boolean`, as `1`/`0` or the literals of `@PlsqlBoolean` on the method. Any other value is a `PlsqlMappingException` | Validated |
| `VARCHAR2` as parameter and return value | `boolean`, `Boolean`, by the literals of `@PlsqlBoolean` on the parameter or on the method | Validated |
| `BOOLEAN` with `@PlsqlBoolean` | The annotation has no effect | Validated |
| `JSON`, from Oracle 21c on, with driver 21 or later: as `IN`, `OUT`, `IN OUT` and return value, `null` included, above 32 KB, a scalar at the top, bound by name; the text is the database's, and a number keeps its value, not its written form; a record through a converter of the application's own, with Jackson | `String`, the JSON text | Validated, on 23ai and 21c |
| `JSON` with driver 19, which does not know the type: an `IN`, as `JSON(?)`, and a return value, through `JSON_SERIALIZE(... RETURNING CLOB)`, converted in the database; `null` included, above 32 KB, by position and by name, on 23ai | `String`, the JSON text | Validated |
| `JSON` with driver 19: an `OUT` or an `IN OUT`, which takes only a bind of the JSON type | Refused before the call, naming the way through a `@PlsqlBlock` | Not supported, with a clear error |
| `JSON` column of a cursor with driver 19: the driver fails while the call runs (`ud.udsoac.oacdty: 0`), before any column is seen; the connection is discarded, and the message, for a driver before 21 and a database of 21c or later, names the likely type and the way around it; on 23ai. On 21c, measured in plain JDBC, the connection hung instead | `JSON_SERIALIZE(doc RETURNING CLOB)` in the query | Not supported by the driver, with a clear error |
| `JSON` through a `@PlsqlBlock` that converts in the database, with `JSON(...)` and `JSON_SERIALIZE`: `IN`, return value and `OUT`, with drivers 23, 21 and 19, on 23ai and 21c; a document of any size going in; one above 32,767 bytes coming back fails with `ORA-06502`, since a block's `:result` is a `VARCHAR2` | `String` | Validated |
| A collection of `JSON`, a RECORD with a `JSON` field, a `%ROWTYPE` of a table with a `JSON` column, which no driver can describe (`ORA-17074`, `ORA-17060`) | Refused, naming the field or the column | Not supported, with a clear error |
| Two versions of a routine, one taking `JSON` and one `VARCHAR2`, for a `String` | Refused as ambiguous | Not supported, with a clear error |
| A JSON text that is not JSON, by the specification, strictly: `{bad`, truncated, trailing text, a leading zero, `NaN`, single quotes, a comment, a trailing comma, `True`, a raw tab in a string, a byte order mark; with drivers 19, 21 and 23, on 23ai and 21c. Before #140, drivers 21 and 23 refused it with errors of their own, and with driver 19 the database took much of it and corrected it | `PlsqlMappingException` before the call: `the text is not JSON: ` and the parser's first sentence, with the line and the column, the same everywhere | Validated |
| A JSON text that is empty or blank; a key twice in the same object, at the top or nested; a lone UTF-16 surrogate in a value or a key | `PlsqlMappingException` before the call, naming where, as in `$.o has the key "a" twice`; the same everywhere. Before #140, an empty string was `NULL` with driver 19, and drivers 21 and 23 stored a lone surrogate as `?` | Validated |
| `JSON` as a `Map<String, Object>` for an object, a `List<Object>` for an array, a number or a `Boolean` for a scalar at the top, with drivers 23, 21 and 19: `IN` and return value, `null` included, above 32 KB, nested objects and arrays, the keys' order; `OUT` and `IN OUT` with driver 21 or later | `LinkedHashMap`, `ArrayList`; every number inside a `BigDecimal`, with the value Oracle keeps | Validated |
| `JSON` as a `Map`: a value JSON has no form for, such as a `LocalDate`, or `NaN`; a document that does not fit the declared type; a `Map` or a `List` with other type arguments than `Object`, or a `HashMap` | Refused with a clear message, naming where the value is, as in `$.items[0].when`, before the call when it can be | Not supported, with a clear error |
| `JSON` with a number beyond the range of `NUMBER`, such as `1e200` or `1e-200`, as a `String` or a `Map` | `PlsqlMappingException` before the call, naming where, as in `$.big is 1E+200`, the same everywhere. Before #140, driver 19 on 23ai took it, on 21c refused `1e200` and stored `1e-200` as 0 | Validated |
| `JSON` beyond a limit of the binary form, which the driver and the database set by version: a key of 300 bytes, of 70,000 bytes, 10,000 levels of nesting | With drivers 21 and 23, the driver's refusal while binding, even its bare `ArrayIndexOutOfBoundsException`, as a `PlsqlMappingException` with its message: a key above 256 bytes with driver 21, above 65,535 with driver 23. With driver 19, the database's `ORA-40476` or `ORA-40475`, as a `PlsqlRoutineRaisedException`. Driver 23 takes 10,000 levels; driver 19 on 23ai takes a key of 300 bytes | Validated, as each driver and database does |

`null` crosses as `null` for scalars, and a primitive rejects `NULL` with `PlsqlMappingException`, instead of making up a `false` or a `0` (validated).

## Composite types

| Form | Status |
|---|---|
| OBJECT, package RECORD and `%ROWTYPE`, as parameter and return value | Validated |
| `%ROWTYPE` of a table with a virtual column, which it keeps, and an invisible one, which it leaves out as PL/SQL does; and with an OBJECT, a nested table and a `VARRAY` as columns, `null` included, in and back, in a collection and from a block; a cursor over such a table, `SELECT *` leaving the invisible column out and a query naming it having it | Validated |
| RECORD containing RECORD, OBJECT and collections | Validated |
| OBJECT containing OBJECT and collections | Validated |
| Mapping to `record` and to JavaBean, matched by attribute name | Validated |
| JavaBean with inherited properties or extra properties | Validated |
| JavaBean `boolean` and `Boolean` properties read through `isX()`, in a `VARCHAR2(1)` and a `NUMBER(1)`, both ways; `@PlsqlBoolean` on the field, the `isX()` getter or the setter | Validated |
| JavaBean with both `isX()` and `getX()` for one property | Not supported, with a clear error |
| OBJECT inheritance (`UNDER`) | Validated |
| `null` in place of a whole OBJECT | Validated |
| `null` in place of a RECORD or `%ROWTYPE` | Validated: a RECORD is never null in PL/SQL, and comes back with null fields |
| Driver addressing, probed without the library: RECORD as `PACKAGE.TYPE`, `%ROWTYPE` as `TABLE%ROWTYPE`, package collection as `PACKAGE.TYPE`, and the names that fail (`ORA-17060`, `ORA-17074`) | Validated |
| Names that need quotes, created as `CREATE TABLE "Mixed"` or `CREATE TYPE "Two Words_T"`: a `%ROWTYPE`, an OBJECT, a package RECORD, a RECORD of a package with such a name, a schema collection and a package collection of a `%ROWTYPE`, through an interface and a block's `@PlsqlType`, each next to an unquoted namesake of another shape, which they do not reach | Validated: such a name is bound with every part in quotes, the one spelling drivers 23, 21 and 19 all resolve |
| Names whose quotes hold a dot, created as `CREATE TYPE "Dot.T"`: a `%ROWTYPE`, an OBJECT and a collection of it, a package RECORD, a package collection of a `%ROWTYPE` and a RECORD of a package with such a name, through an interface and a block's `@PlsqlType`, on drivers 23, 21 and 19 | Validated |
| Names whose quotes hold a double quote | Not supported by the database: Oracle refuses a double quote in any name, quoted or not, with `ORA-25716` on 23ai ([Database Object Names and Qualifiers](https://docs.oracle.com/en/database/oracle/oracle-database/19/sqlrf/Database-Object-Names-and-Qualifiers.html)) |
| JavaBean without a default constructor, when reading | Not supported, with a clear error |
| JavaBean without a setter, when reading | Not supported, with a clear error |

## Attributes of composite types

| Attribute type | Java type | Status |
|---|---|---|
| `NUMBER`, `VARCHAR2`, `DATE`, enum | Types from the scalar table | Validated |
| `VARCHAR2` | `Boolean` by `Y`/`N`, or by the literals of `@PlsqlBoolean`, such as `S`/`N` | Validated |
| `NUMBER` | `Boolean` as `1`/`0`, or by the literals of `@PlsqlBoolean` | Validated |
| `CHAR(1)` in a `%ROWTYPE` | `boolean` by the literals of `@PlsqlBoolean` on the record component | Validated |
| `TIMESTAMP` | `LocalDateTime` | Validated |
| `TIMESTAMP WITH TIME ZONE` | `OffsetDateTime`, `ZonedDateTime` (keeps the region), `Instant` | Validated |
| `TIMESTAMP WITH LOCAL TIME ZONE` | `OffsetDateTime`, `Instant` | Validated |
| Type with time zone in `LocalDateTime` | Rejected with `PlsqlMappingException` | Validated |
| `DATE` or `TIMESTAMP` in `Instant`, `OffsetDateTime` or `ZonedDateTime` | Rejected with `PlsqlMappingException` | Validated |
| `RAW` | `byte[]` | Validated |
| `CHAR`, `NVARCHAR2` | `String` | Validated |
| `BINARY_DOUBLE` | `Double` | Validated |
| `BOOLEAN` in RECORD or OBJECT | `Boolean`, in both directions, including `null`. `@PlsqlBoolean` has no effect | Validated |
| `CLOB` when reading | `String` | Validated |
| `CLOB` when writing | `String`, in a temporary LOB | Validated |
| `BLOB` | `byte[]`, in a temporary LOB when writing | Validated |

## Collections

| Form | Status |
|---|---|
| Schema-level `TABLE` and `VARRAY` of scalars | Validated |
| Schema-level `TABLE` of OBJECT | Validated |
| Package-level `TABLE` of scalars, RECORD or `%ROWTYPE` | Validated |
| Empty collection and `null` element | Validated |
| `NULL` return value becomes an empty list | Validated |
| `List<T>`, `T[]` or primitive array parameter | Validated |
| `List<Boolean>` as parameter and return value, with `@PlsqlBoolean` on the parameter or on the method | Validated |
| `Set<T>` parameter | Validated |
| `List<T>` or `T[]` attribute | Validated |
| `VARRAY` attribute | Validated |
| `List<T>` return value | Validated |
| `ArrayList<T>` return value | Validated |
| `T[]`, `Set<T>` or `Collection<T>` return value | Not supported, with a clear error |
| `null` in place of the whole collection | Validated: a `NULL` return value becomes an empty list |
| `TABLE` and `VARRAY` of `TIMESTAMP` with time zone, in a `List` or array of `OffsetDateTime`, `ZonedDateTime` (keeps the region) or `Instant`, including in `OUT` and attributes | Validated |
| `LocalDateTime` element in a collection with time zone, or an element with time zone in a plain `TIMESTAMP` collection: rejected before the call | Validated |
| Collection of collections (`TABLE OF NAME_LIST_T`) in `List<List<T>>` or `T[][]`, in both directions, in the schema or in a package spec, at any depth, including `null` inner elements | Validated |
| Java element that does not match the levels of the collection, such as `List<String>` for a collection of collections: rejected before the call | Validated |
| Collection of collections as an attribute of an OBJECT or RECORD | Not supported, with a clear error |
| `INDEX BY PLS_INTEGER` as return value, `OUT` and RECORD field, in `List<T>`: the values, in index order, and the indexes are lost | Validated |
| `INDEX BY PLS_INTEGER` as an `IN` or `IN OUT` parameter, or written to a RECORD field: rejected, because the driver numbers from 0, not from 1 | Not supported, with a clear error |
| `INDEX BY VARCHAR2`: rejected, because the driver does not write it and, when reading, loses the keys. No `Map` | Not supported, with a clear error |
| `INDEX BY` read into `List<ZonedDateTime>`: rejected, because the driver would deliver nulls. Use `OffsetDateTime` or `Instant` | Not supported, with a clear error |

## Cursors

| Form | Status |
|---|---|
| `SYS_REFCURSOR` as return value, in a `List<T>` of `record` or JavaBean | Validated |
| Columns without a property are ignored; no column matching is an error | Validated |
| Empty cursor becomes an empty list | Validated |
| Numeric column in `Boolean`, as `1`/`0`. Any other value is a `PlsqlMappingException` | Validated |
| `BOOLEAN` column in `Boolean`, alone or in a row, including `null`, from driver 23 on | Validated |
| `BOOLEAN` column before driver 23: see [Driver versions](#driver-versions) | Not supported, with a clear error |
| Strongly typed `REF CURSOR` | Validated |
| `Set<T>` or `Collection<T>` return value | Not supported, with a clear error |
| `CLOB` column in `String` | Validated |
| `JSON` column in `String`, `null` included, from Oracle 21c on, with driver 21 or later | Validated |
| `JSON` column in a `Map<String, Object>`, a `List<Object>`, a number or a `Boolean` of a record or a JavaBean, `null` included, with driver 21 or later; a `Map` or a `List` with other type arguments is refused | Validated |
| `TIMESTAMP` column in `LocalDateTime` | Validated |
| Column with time zone in `OffsetDateTime`, `ZonedDateTime` or `Instant` | Validated |
| Column with time zone in `LocalDateTime`, or `TIMESTAMP` in `Instant`: rejected when reading, with the column in the message | Validated |
| OBJECT column in a `record` or JavaBean property; a `NULL` one becomes `null` | Validated |
| Collection column in a `List<T>` property: select its elements with `TABLE()`, or return it inside an OBJECT column | Not supported, with a clear error |
| `BLOB` or OBJECT column in `String` | Not supported, with a clear error |
| Column matching a property without a setter | Not supported, with a clear error |
| Cursor of a scalar (`List<String>`, `List<BigDecimal>`, or any type of the scalar table, or with a converter on the element), read from its only column | Validated |
| Cursor of a scalar with more than one column: `PlsqlMappingException` naming the columns | Validated, with a clear error |

## Routines

| Form | Status |
|---|---|
| Package function and procedure | Validated |
| Name by the camelCase → SNAKE_CASE convention, or by `@PlsqlRoutine` | Validated |
| Session schema or `@PlsqlPackage(schema=...)` | Validated |
| Parameter with `DEFAULT` at the end of the list, omitted: one Java method per way of calling, including through overloads with the same name | Validated |
| `null` in a parameter with `DEFAULT`: goes as `NULL`, and Oracle does not replace it with the default | Validated |
| Omitting a parameter with `DEFAULT` in the middle of the list | With `BY_NAME`, validated. By position, not supported |
| Binding by name (`bind = BY_NAME`): by Java name or by `@PlsqlParam`, in any order, with `OUT` and `IN OUT` | Validated |
| `BY_NAME`: Java parameter with no match, duplicate name, or required parameter with no match | Not supported, with a clear error |
| `BY_NAME`: overload that differs only in a parameter name | Validated |
| `SYS` packages | Validated |
| `OUT` and `IN OUT` with `PlsqlOut<T>`: scalar, `BOOLEAN`, OBJECT, RECORD, `%ROWTYPE`, collection and, for `OUT`, cursor | Validated |
| Function with an `OUT` parameter | Validated |
| `@PlsqlBoolean` on `PlsqlOut<Boolean>`, in both directions | Validated |
| `PlsqlOut` picks between the `IN` and `OUT` versions of an overload | Validated |
| `OUT` parameter without `PlsqlOut`, `PlsqlOut` on an `IN` parameter, or untyped `PlsqlOut` | Not supported, with a clear error |
| If the call fails, the `PlsqlOut` keeps its previous value | Validated |
| Overloaded routine, with the version picked by the Java method's signature | Validated |
| Overload with `boolean`: the most restrictive version wins, `BOOLEAN`, then `NUMBER`, then `VARCHAR2` | Validated |
| Overload with `@PlsqlBoolean` on the parameter: the literals pick text or number, and `BOOLEAN` comes last | Validated |
| Overload with more than one version fitting the method and none more restrictive in every parameter | Not supported, with a clear error. For OBJECT, `@PlsqlType` breaks the tie |
| Interface that inherits methods from another | Validated |
| Package accessed through a private, public or chained synonym | Validated |
| Package and types in another schema | Validated |
| Package with a quoted name (lower case), with `@PlsqlPackage("\"name\"")`; unquoted, the name is read in upper case | Validated |
| Standalone function and procedure, with `@PlsqlSchema`: by position and `BY_NAME`, with `OUT`, omitted `DEFAULT`, no parameters, through a synonym, with an explicit schema, and next to `@PlsqlBlock` methods | Validated |
| `@PlsqlPackage` and `@PlsqlSchema` on the same interface, or `@PlsqlPackage("")` | Not supported, with a clear error when the proxy is created |

## Anonymous blocks (`@PlsqlBlock`)

| Form | Status |
|---|---|
| Scalar parameters matched by name, including repeated ones | Validated |
| `@PlsqlParam` | Validated |
| Scalar or `BOOLEAN` `:result`, or composite with `resultType` | Validated |
| Collection `:result`, with `resultType` naming the collection: scalars, OBJECT or collections; `NULL` becomes an empty list | Validated |
| Cursor `:result`, opened with `OPEN :result FOR` or declared with `resultType = "SYS_REFCURSOR"`, of `record`, JavaBean or scalar | Validated |
| `List` `:result` without `resultType` and without `OPEN :result FOR` (in a comment or literal it does not count) | Not supported, with a clear error |
| Cursor or collection `:result` declared as something other than `List<T>`, or composite `resultType` declared as a `List` | Not supported, with a clear error |
| Block without output | Validated |
| Block inconsistent with the method signature | Validated, with a clear error |
| `PlsqlOut` in a block: the only output is `:result` | Not supported, with a clear error |
| `null` as parameter | Validated |
| `boolean` or `Boolean` parameter, including `null`: goes as a PL/SQL `BOOLEAN` | Validated |
| `@PlsqlBoolean` on the parameter or on the method: replaces `BOOLEAN` with the literal | Validated |
| Composite or collection parameter, with `@PlsqlType` on the parameter or on the composite's class: OBJECT, collection of scalars, of OBJECT or of collections, and `null` | Validated |
| A block's `@PlsqlType` and `resultType` naming a type of another schema, `SCHEMA.TYPE`, `SCHEMA.PACKAGE.TYPE` and `SCHEMA.TABLE%ROWTYPE`, or reached through a private synonym of an OBJECT, a collection, a package or a table, resolved as PL/SQL resolves them; one that does not exist, refused naming it; on 23ai, 21c and 19c | Validated |
| Composite or collection parameter without `@PlsqlType` | Not supported, with a clear error |
| `:` inside a string literal, including `q'[...]'`, a comment or a quoted identifier: not a placeholder | Validated |
| `?` inside a string literal and a comment | Validated |

## Error handling

| Situation | Behavior | Status |
|---|---|---|
| Nonexistent routine, package or standalone routine, wrong arity, procedure × function | `PlsqlMappingException` saying what the database expects | Validated |
| Attribute without a property, mismatched `@PlsqlType`, duplicate Oracle name | `PlsqlMappingException` naming the problem | Validated |
| Any mapping error of a method, in a call or in `validate` | The message starts with `method(types): `, once, and the detail does not repeat the method; the routines that exist are listed once each, overloads included | Validated |
| `RAISE_APPLICATION_ERROR` | `PlsqlRoutineRaisedException` with Oracle's message in `getMessage()`, the unsigned number in `getCode()` and the text in `getReason()`, including multi-line text. The `SQLException` is kept as the cause | Validated |
| `RAISE` of an exception with `EXCEPTION_INIT` in the `-20000` to `-20999` range | `PlsqlRoutineRaisedException` with the code, and an empty `getReason()`: nobody supplied a text | Validated |
| A value the driver does not accept in an attribute or element, such as text in a `NUMBER` | `PlsqlMappingException` with the Oracle type and the attributes, instead of a driver exception | Validated |
| Native error during execution: `NO_DATA_FOUND`, `TOO_MANY_ROWS`, `ZERO_DIVIDE`, `VALUE_ERROR`, number and date conversion, user-defined exception without `EXCEPTION_INIT`, null collection and object, `CASE_NOT_FOUND`, constraints, value too large, invalid dynamic SQL and `ORA-21000` | `PlsqlRoutineRaisedException`, with the native code | Validated |
| Code not listed in `PlsqlFailure`, during execution, sent by the database | `PlsqlRoutineRaisedException`. Includes infrastructure errors the list does not anticipate | Validated by the rule, without triggering the error |
| An error the driver raises itself, in the client, during execution, whatever its code: `ORA-17001`, `ORA-17004`, driver 23's `ORA-18722`; and a failure of the driver itself, which Penitent wraps with code 0 after discarding the connection. Only an error the database sent carries `oracle.jdbc.OracleDatabaseException` as its cause, measured with drivers 19, 21 and 23 | `PlsqlExecutionException`, never `PlsqlRoutineRaisedException`. Before #141's fix, a code outside the driver's old range, and the failure with code 0, were the routine's | Validated |
| A constraint the routine declares on a formal parameter, broken at entry: `POSITIVE`, `NATURAL` or a `RANGE` below or above, `SIMPLE_INTEGER` or a `NOT NULL` subtype with `null`, `PLS_INTEGER` beyond its range; and on its return value | `PlsqlRoutineRaisedException`, codes `6502` and `1426`, the stack only at `line 1`: the routine's contract | Validated |
| A size or a precision on a formal parameter: a `VARCHAR2(3)` or `NUMBER(3)` subtype, a column's `%TYPE` | Not checked, as Oracle documents: any value arrives, and fails only where the routine stores it, as the routine's error. A `VARCHAR2(3)` subtype on a return value is not checked either; a `NUMBER(3)` one is | Validated |
| `PlsqlFailure` codes actually triggered: `ORA-00054`, `ORA-04063`, `ORA-06550` (also in dynamic SQL), `ORA-17004` | `PlsqlExecutionException` | Validated |
| Other `PlsqlFailure` codes and ranges, such as `ORA-00060`, `ORA-01013` and `ORA-03113` | `PlsqlExecutionException` | Validated by the rule, without triggering the error |
| Failure reading the dictionary or preparing the call | `PlsqlExecutionException`, even with a code that would belong to the routine | Validated |
| Error reading a cursor returned by the routine, such as a division by zero in the query | `PlsqlRoutineRaisedException`, with the native code. When reading, the same `PlsqlFailure` list applies | Validated |
| Custom translator (`PlsqlExceptionTranslator`) | Decides every exception, and receives the `PlsqlFailure` | Validated |
| Failure obtaining the connection | Goes through the same translator | Validated |
| Package with an invalid body | Exception with `ORA-04063` as the cause | Validated |
| Value that does not convert (enum, number, decimal or `Integer` overflow) | `PlsqlMappingException` with the routine, the attribute or the column, and the value. The original cause is preserved | Validated |

## Converters

| Situation | Status |
|---|---|
| `@PlsqlConvert` on parameter, return value, `OUT`, `IN OUT`, attribute, cursor column and block | Validated |
| `PlsqlConverters` for the type across the whole interface, with the annotation winning | Validated |
| `List<J>` and `J[]`, per element, as parameter, return value and attribute | Validated |
| `NULL` and null elements go through the converter; a whole null collection does not | Validated |
| `ofNullable` keeps `null` without calling the function | Validated |
| Converter failure: `PlsqlMappingException` with the converter, the value and the point | Validated |
| Converter returning `null` for a primitive: `PlsqlMappingException` | Validated |
| Time zone validation and overload selection by `D` | Validated |
| Converter on an OBJECT, RECORD or collection of composites: rejected | Validated |
| Converter on the element of a cursor (`List<J>`): a cursor of that scalar, read from its only column | Validated |
| With `penitent-spring` | Validated |
| Enum by code (`PlsqlConverter.enumByCode`, `PlsqlEnumCodeConverter`): text or numeric code, per element, with `@PlsqlConvert`, and a code padded by `CHAR` | Validated |
| Enum by code: duplicate code, constant without a code, unknown code | Not supported, with a clear error |
| One-way converter, or converter as a Spring bean | Not supported |

## Transactions and connections

| Situation | Status |
|---|---|
| A connection per call from the `DataSource`, given back after a call, a routine error, a mapping error, a cursor read and a validation | Validated |
| The library never commits, rolls back or changes auto-commit | Validated |
| Pool in auto-commit: each call commits on its own; a failed call undoes only its own work | Validated |
| Pool without auto-commit and nothing that commits: the call succeeds and its work is lost when the pool takes the connection back | Validated |
| On the application's connection: calls and plain JDBC share the transaction; `OUT` and cursors read uncommitted work; a failed call leaves the rest pending | Validated |
| `PlsqlPackages.using`: every call and `validate` on the given connection, routines and blocks; the connection is never closed, committed or rolled back | Validated |
| `PlsqlPackages.using`: shares the dictionary cache both ways, the settings and the translator; reads nothing to be created; one view per transaction in a loop reads the dictionary once in all | Validated |
| `PlsqlPackages.using`: a view of a view; a closed connection fails as a translated connection failure; `null` and a foreign object rejected | Validated |
| `PlsqlPackages.using`: an unreferenced view is collected while the interface lives on | Validated |
| `PlsqlPackages.using` on an interface of `SpringPlsqlPackages`: Spring's exceptions kept (`BadSqlGrammarException`, `UncategorizedSQLException`, `PessimisticLockingFailureException`), routine errors as `PlsqlRoutineRaisedException`; inside a Spring transaction, the view runs on its own connection, not the transaction's | Validated |
| A routine's own `COMMIT` or `ROLLBACK` ends the caller's transaction, with and without Spring; a `ROLLBACK` erases savepoints, and a failing Spring nested transaction then reports `ORA-01086` | Validated |
| An autonomous transaction commits only its own work; a cursor opened before the routine's `COMMIT` is read whole | Validated |
| With `penitent-spring`: routines and `JdbcTemplate` committed and rolled back together; a routine error caught inside the transaction undoes only that call | Validated |
| `PlsqlTransactions`: commit when the block returns; rollback on a `RuntimeException`, a checked exception, an `Error` or `setRollbackOnly`; the exception comes out as thrown; `runWithResult` returns the value | Validated |
| `PlsqlTransactions`: a failed commit (`ORA-02091`) is rolled back and translated, with the `TRANSACTION` phase; a connection that cannot be obtained is translated and the block does not run; rollback and close failures are suppressed into the block's failure | Validated |
| `PlsqlTransactions`: the connection goes back to the pool in every case, with auto-commit restored by the block itself, and left off when it was off | Validated |
| `PlsqlTransactions`: `tx.connection()` refuses `commit`, `rollback()`, `setAutoCommit`, `close` and `abort`, allows savepoints and `unwrap` | Validated |
| `PlsqlTransactions`: refuses a proxy of the same `DataSource` without `tx.using`, a view or the connection after the block or from another thread, and a nested block; a proxy of another `DataSource`, or of a wrapper of the same one, runs on its own, since the `DataSource` is compared by identity | Validated |
| `PlsqlTransactions`: a routine's own `COMMIT` commits the block's work so far | Validated |
| `PlsqlTransactions` with `penitent-spring`: a block inside a Spring transaction is refused before it takes a connection, and Spring's rollback still undoes the rest; a block outside any Spring transaction runs side by side with them | Validated |
| `PlsqlTransactions` with `penitent-spring`: a Spring transaction that begins inside a block is refused before it takes a connection, and the block rolls back; `@EnablePlsqlPackages` and the starter add the refusal once to each transaction manager in the context, the one Spring Boot creates included, also when both are present | Validated |
| `PlsqlTransactions` with the starter: a `@Transactional` method, through the proxy Spring Boot creates, is refused inside a block before it takes a connection, and a block inside one is refused before it takes its own | Validated |
| `PlsqlTransactions` with `penitent-spring`: an interface of `SpringPlsqlPackages` of the block's `DataSource` is refused without `tx.using`, and runs in the block's session through it; one of another `DataSource` runs on its own | Validated |
| An object type changed under a pool of HikariCP: the connection that bound it keeps the old type in the driver (`ORA-17049`), which HikariCP keeps by itself; `softEvictConnections()`, or an `SQLExceptionOverride` returning `MUST_EVICT` for 17049 and 3101, gives a connection that reads the new type; on 23ai, 21c and 19c | Validated |
| A routine changed after the proxy described it, as a deploy does: a parameter added, renamed when binding by name, a function turned into a procedure, or the routine removed: the call that fails says the routine changed since the proxy described it, with what no longer fits, and the calls after it are refused before they run; a `PLS-00306` raised inside the routine, when nothing changed, stays as Oracle says it; on 23ai, 21c and 19c; with `penitent-spring`, a `PlsqlMappingException`, not the `BadSqlGrammarException` of `ORA-06550` | Validated |
| An object type changed after the proxy described it: the call says the type changed, with the attributes the Java type lacks, or, on 21c, whose `ORA-00600` closes the connection, the next call does; `ORA-17049` keeps its code and says to renew the connections; on 23ai, 21c and 19c | Validated |
| A package body with state recompiled: `ORA-04068` once on the connection, as a `PlsqlExecutionException` with `getCode()` 4068, and the new body on the next call; on 23ai, 21c and 19c | Validated |
| `SESSION_EXIT_ON_PACKAGE_STATE_ERROR` set with HikariCP's `connectionInitSql`: `ORA-00028` instead of `ORA-04068`, the transaction's earlier work undone, and a new connection with the new body, on 23ai; on an Autonomous Database 19c, `ORA-01031: insufficient privileges` | Validated on 23ai; measured by hand on 19c |

## Interface behavior

| Situation | Status |
|---|---|
| Concurrent calls on the same proxy (40 calls on 4 threads) | Validated |
| Single-connection pool: the dictionary is read on the call's connection | Validated |
| With `penitent-spring`: same session and same rollback as the current transaction | Validated |
| With `penitent-spring`: `PessimisticLockingFailureException`, `BadSqlGrammarException` and `UncategorizedSQLException` for what is not a routine error | Validated |
| With `penitent-spring`: routine error as `PlsqlRoutineRaisedException`, with rollback, including duplicate key | Validated |
| `default` method in a `public` interface | Validated |
| `default` method in a non-public interface, on the classpath | Validated |
| Exception thrown by a `default` method: comes out as it is, without a wrapper | Validated |
| `equals`, by proxy identity | Validated |
| With `@EnablePlsqlPackages`: interfaces found by annotation and by `@PlsqlBlock`, in subpackages; others ignored; nested only with `considerNested` | Validated |
| With `@EnablePlsqlPackages`: one proxy per interface, injected by type; transaction; `DataSource` by name; mapping error at startup | Validated |
| `PlsqlSettings`: default `bind` for interfaces that declare none, and the declared one wins in both directions | Validated |
| `PlsqlSettings`: converters for every interface, `@PlsqlConvert` over them, and `PlsqlConverters.with` to add | Validated |
| `PlsqlSettings`: exception translator, with and without `penitent-spring`; a `PlsqlSettings` bean applies to registered interfaces | Validated |
| `PlsqlPackages.validate`: every mismatch in one exception, nested composites and collection elements, cursor not in a `List`, block placeholders, database failure translated | Validated |
| `PlsqlPackages.validate` finds without calling the same mismatches that the calls find | Validated |
| After `validate`, calls read nothing from the dictionary | Validated |
| Dictionary queries on the first call: 5 for a scalar, 6 for an OBJECT, 12 for a collection of OBJECT, 16 for nested composites; none after | Validated |
| With `@EnablePlsqlPackages(validateOnStartup = true)`: a mismatch stops the context and names every interface; off by default | Validated |
| Starter: interfaces of the application's packages, nested ones skipped; nothing without a `DataSource`; backs off with two and no primary | Validated |
| Starter: an `@EnablePlsqlPackages` wins and the starter does not scan | Validated |
| Starter: `penitent.bind`, `DEFAULT` refused at startup; `PlsqlSettingsCustomizer` in `@Order` | Validated |
| Starter: a `PlsqlSettings` bean of the application wins, with a warning if `penitent.bind` is set | Validated |
| Starter: `penitent.validation.enabled` off by default, and covering the interfaces of an `@EnablePlsqlPackages` | Validated |
| Spring's method validation on an interface: constraints on parameters refused before the call takes a connection, `@Valid` into a record's fields, and on the return value after the call, as `ConstraintViolationException` | Validated |
| Resilience4j on Spring Boot, on an interface's methods: `@Retry` calls again up to the attempts configured; `@CircuitBreaker` opens after the failures configured and refuses without calling | Validated |
| `@Transactional` on an interface's methods: alone, a transaction of its own, committed on return; inside another, part of it; `REQUIRES_NEW` commits on its own; inside a `PlsqlTransactions` block, refused | Validated |
| An interface Spring wraps (`@Transactional`, `@Validated`, the application's advice, one or more layers): `validateOnStartup` and `PlsqlPackages.validate` reach it; a view of `tx.using` or `PlsqlPackages.using` keeps every layer, runs on its connection and stops with the block; inside a block, a `@Transactional` method of the view is refused | Validated |

## Mocking PL/SQL in tests

With `penitent-test`, measured on Oracle 23ai, 21c and 19c, the last on Oracle's Autonomous Database Free, whose
`ADMIN` enables editions and grants what `penitent-mock-admin.sql` asks for, as [Testing](testing.md#mocking-a-routine)
shows. A `BOOLEAN`
argument goes into SQL only from 23ai on, so a call records it as `'true'` or `'false'`, and `verify` compares it as
that text, on every version.

| Situation | Status |
|---|---|
| A stub of a function returns its value, `null` included; of a date argument, matches it by value | Validated |
| A stub with a date before Christ, from a `LocalDate` and a `LocalDateTime`, returned and matched by value, the year 0 (1 BC) and Oracle's first day included: the same day the core sends. Before #142's fix, the literal was a year later, the year 0 did not compile, and a `TIMESTAMP` lost its era. A stub's date beyond Oracle's range is rejected with `PlsqlMappingException` | Validated |
| A stub's number at `NUMBER`'s edges, returned and matched; beyond its range, returned or matched, rejected with the core's `PlsqlMappingException` | Validated |
| Oracle's `JSON` type, on 23ai and 21c, with drivers 19, 21 and 23: a stub returns a document, a `Map`, `null` and a document above 32 KB, and PL/SQL calling it gets it; matches by document equality, `JSON_EQUAL`, ignoring an object's member order, spacing and `1` against `1.0`, but not an array's order; matches a `Map`, a scalar at the top, and SQL `NULL` apart from the JSON `null`; sets a `JSON` `OUT` with driver 21 or later; `verify` counts by document equality, a scalar at the top included, and tells SQL `NULL` from the JSON `null`. A stub's text that is not JSON, or with a key twice, or empty, is refused with the core's message | Validated |
| A stub of a `JSON` `OUT` with driver 19 | Refused when the stub is made, with the message a call would give: driver 19 cannot bring such an `OUT` back | Validated |
| A stub raises an application error or a predefined exception, which arrives as `PlsqlRoutineRaisedException` | Validated |
| A stub of a procedure sets its `OUT` value; matchers `eq`, `any` and `isNull`, the last stub that matches winning; a call mixing matchers and values refused | Validated |
| `anyInt()`, `anyLong()`, `anyShort()`, `anyByte()`, `anyDouble()`, `anyFloat()` and `anyBoolean()` for parameters of primitive types, in a stub of a function and of a procedure, and in `verify` | Validated |
| A `BOOLEAN` argument, on 23ai, 21c and 19c: a stub matching it by value and with `anyBoolean()`, and `verify` counting its calls by value | Validated |
| `thenRaise` and `doRaise` of each of the 23 predefined exceptions of `STANDARD`, with its Oracle code, five of them as `PlsqlExecutionException`; of an exception declared in the spec of the mocked package and of another, caught by name; uncaught, as `ORA-06510`; a declared exception that does not exist, reported naming it | Validated |
| A stub of a standalone function and of a standalone procedure, from an interface with `@PlsqlSchema`: values, matchers, `OUT` values, errors and `verify`; PL/SQL calling them gets the stub, other sessions the real routine; gone with the edition; a script replacing one; a stub and a script on the same routine, one of another schema and a wrapped one, refused | Validated |
| `doNothing()`, `doThrow()`, `doRaise()` and `doReturn()`: a procedure setting its `OUT` values or raising, compiled once; a function returning, the last stub that matches winning; `verify` after them; `doNothing()` on a function, `doReturn()` on a procedure, a `doXxx().when(mock)` with no call after it, and something that is not a mock, refused | Validated |
| A routine taking a collection of an object type or an object type: its stub compiles, `any()` matches it in the stub and in `verify`, and the stub answers; a value or `isNull()` on such a parameter refused; a routine taking a `%ROWTYPE`: its stub compiles | Validated |
| A `%ROWTYPE` in the test's edition, through a subtype compiled there: as an argument, of a real routine and of a stubbed one; as a return value; a collection of them, in and back; in Spring's transaction; again in the next test's edition, with nothing left after it; of a table of another schema, without editions, with nothing created in it; a package per table, numbered, created once, also for a block that writes the name in lowercase; with drivers 23, 21 and 19 | Validated |
| A stub returns an object type, with a `DATE` from a `LocalDate` or a `LocalDateTime` and a `TIMESTAMP` with its fraction, a collection of object types, of text and a `VARRAY` of numbers, an object type holding an object type and collections, empty collections and `null` attributes and elements; PL/SQL calling the routine gets it; a procedure's stub sets an `OUT` of an object type; a Java type that does not fit, refused before compiling | Validated |
| Dropping the test's edition on a transient error, injected: `ORA-00060`, a deadlock, and `ORA-00604` over `ORA-04043`, tried again up to 3 times, each one logged; an error on every attempt fails `end()`, saying so, and the next `begin()` drops the edition left; any other error fails at once. Oracle 19c on the Autonomous Database showed both errors now and then in the CI, and 400 drops did not provoke them on purpose ([#91](https://github.com/rponte/penitent/issues/91)) | Validated |
| A stub matching an argument by value, and `verify` counting the calls by value, for a `VARCHAR2` of 5,000 characters, a `CLOB` of 40,000 with quotes and accents, a `BLOB`, a `RAW`, a `TIMESTAMP WITH TIME ZONE`, a `WITH LOCAL TIME ZONE` from an `Instant`, both `INTERVAL`s, a `BINARY_DOUBLE`, and, through the parameter's mapping, an enum, a `UUID`, a boolean by the literals of `@PlsqlBoolean`, a `java.util.Date` with its time and a converter; on 23ai, 21c and 19c | Validated |
| A stub's value through the application's mapping, returned and as an `OUT`: an enum by its name, a `UUID` as text, a `Boolean` by the literals of `@PlsqlBoolean` on a `VARCHAR2` and as `1` or `0` on a `NUMBER`, a `java.util.Date` and a `java.sql.Timestamp` with their time, an `Optional`, its value or `NULL`, `NaN` and the infinities of a `BINARY_DOUBLE` and a `BINARY_FLOAT`, and a converter on the method and on the parameter; PL/SQL calling the routines gets them; on 23ai, 21c and 19c | Validated |
| A stub returns, and sets as an `OUT`, a `TIMESTAMP WITH TIME ZONE` with an offset and with a region, a `WITH LOCAL TIME ZONE` from an `Instant`, both `INTERVAL`s, negative and with a fraction, a `RAW` and a `BLOB` from bytes, and a `CLOB` above 32 KB from a `String` and a `java.sql.Clob`, with quotes and accents, which PL/SQL reads whole; bytes above 16 KB refused, naming the limit ([#93](https://github.com/rponte/penitent/issues/93)); on 23ai, 21c and 19c | Validated |
| A stub of a function returns a `BOOLEAN`, `true`, `false` and `null`, which PL/SQL calling it also gets, and a `TIMESTAMP` and a `DATE` from a `LocalDateTime`, with the fraction and the time, and `null`; on 23ai, 21c and 19c. A `null` `BOOLEAN` read with driver 23 from a database before 23ai comes back as `true` or `false`, the driver's defect in [Database versions](#database-versions) | Validated |
| A stub returns a `RECORD` of a package, in its order whatever the order of the Java fields, a `RECORD` holding a `RECORD`, a nested table of them, a `%ROWTYPE`, also of a table whose column needs quotes, of tables whose names need quotes (mixed case, and a space), which also bind as arguments in the test's edition, of a view, and of two tables in one test, and an `INDEX BY` of text, of numbers and of records, each with its empty case where it has one; sets a `RECORD` and a `%ROWTYPE` as an `OUT`, also bound by name, with the Java arguments in another order or a parameter with a `DEFAULT` left out; PL/SQL calling the routines gets them; an `INDEX BY VARCHAR2` refused by the core, as a call would be; on Oracle 23ai, 21c and 19c | Validated |
| A stub of a `RECORD` holding an `INDEX BY` | Not supported, with the core's clear error: a call refuses it too, since the driver would write the `INDEX BY` from index 0 |
| One routine stubbed in one test through two interfaces that bind it differently, by position and by name or in another order | Not supported, with a clear error; the stubs made before it still answer ([#87](https://github.com/rponte/penitent/issues/87)) |
| A stub returns a `%ROWTYPE` of a table with a virtual column and an invisible one, which it leaves out, and of a table with an object type and a `VARRAY` as columns, on 23ai, 21c and 19c | Validated |
| A stub returns an object type with `TIMESTAMP WITH TIME ZONE` and `WITH LOCAL TIME ZONE` attributes, and one with `RAW`, `BLOB` and `CLOB` attributes, the `CLOB` above 32 KB; collections of `TIMESTAMP WITH TIME ZONE` and of `RAW` elements; a nested table, a `VARRAY` and a table of object types declared in a package spec; a collection of collections, an inner one empty; on 23ai, 21c and 19c | Validated |
| A stub returns an object type with a `BOOLEAN` attribute, `TRUE` and `NULL`, on 23ai, the only version whose SQL types take one | Validated |
| A stub returns a `%ROWTYPE`, an OBJECT, a collection of it, a package RECORD and a package collection of a `%ROWTYPE` whose names' quotes hold a dot, and a RECORD of a package with such a name; `any()` matches and `verify` counts such an OBJECT as an argument; on drivers 23, 21 and 19, which give such a name without its quotes, as `PLSQL_TEST.Dot.T`, so the mocks read it in the dictionary | Validated |
| A stub of a type the driver names as it names another, such as an OBJECT `"Twin.Box_T"` next to a package `"Twin"` declaring `"Box_T"`, both `PLSQL_TEST.Twin.Box_T` | Not supported, with a clear error |
| A script in the test's edition, from the classpath, `file:`, a `Path`, an `InputStream` or text: a view and a package body replaced, seen only in the edition and gone with it; nothing of the test's transaction committed; a table, DML, `DROP`, `ALTER`, another schema and a type the schema does not edition refused before any statement runs; compilation errors reported; a package with stubs and a script refused, in either order | Validated |
| A script that replaces a package spec with its body, seen only in the edition; a spec alone with the same declarations, whose body compiles again in the edition; a private synonym pointed to another package, followed by Java and by PL/SQL in the edition, in a schema whose editions keep synonyms; on 23ai, 21c and 19c | Validated |
| A script that replaces a package spec alone, declaring what the body lacks | Not supported, with a clear error when applied, rather than `ORA-04063` at the first call |
| `@PlsqlMockScript` with the JUnit 5 extension, also without Spring, and with `@EnablePlsqlMocks` in a transactional test: the class's scripts in every test, then the method's, repeated in their order, before `@BeforeEach`; found on a superclass, on the class enclosing a `@Nested` one and as a meta-annotation; one with no script, and one with different `value` and `scripts`, refused; in Spring without `@EnablePlsqlMocks`, refused | Validated |
| Several stubs of the same routine: different arguments each answered; the same arguments twice, the later one wins; a second answer on the same stub replaces the first | Validated |
| A call no stub matches fails with `ORA-20999`, naming the routine and showing the arguments | Validated |
| The rest of the package stays real: other routines, private ones, other overloads; a real routine calling the stubbed one, in the same package or another, gets the mock; outside the test's edition, the routine is real | Validated |
| `verify` counts the calls of a stubbed routine, from Java and from PL/SQL, with `times` and `never`; refused for a routine with no stub | Validated |
| A call on the mock itself that is neither stubbed nor verified fails, at the next call on a mock or at `end()`, which still drops the edition | Validated |
| A wrapped body refused; prerequisites missing reported all at once, naming the scripts; `prepare` twice harmless | Validated |
| An edition per test, dropped after it, also after a test that fails or a run that died; a second mocks on the same database refused | Validated |
| A stub made after the package was used fails the next call once with `ORA-04068`, and the one after sees it | Validated |
| A pool with no connection to spare: a clear message | Validated |
| JUnit 5: `PlsqlMockExtension`, stubs in `@BeforeEach`, `PlsqlMocks` as a parameter of `@Test`, `@BeforeEach` and `@AfterEach`; refused in `@BeforeAll` | Validated |
| Spring: `PlsqlMocks` as a parameter with `@Autowired`; without it, not resolved | Validated |
| Spring: `@EnablePlsqlMocks` in a transactional test, rolled back as usual, the stub never committing it; without a transaction; several `DataSource`s; the context cached; Spring Boot's `DataSource` | Validated |
| The core and the JUnit 5 extension without Spring on the classpath | Validated |
| The log of the mocks, under `io.github.rponte.penitent.mock`: the edition, stubs, verifies and the subtype of each `%ROWTYPE` at `DEBUG`, the generated PL/SQL at `TRACE`, a leftover edition and a drop of the edition tried again at `WARN`, nothing at `INFO`; a record bound by its subtype's name, in the call log | Validated |

## Not measured

- Oracle 18c and older, such as 12c ([on demand](roadmap.md#on-demand)).
- A stub of a standalone routine in a schema that does not edition its type, refused saying what to run: not run by a
  test, since editions cannot be undone in the test database; nor a standalone routine with `AUTHID`, `DETERMINISTIC`,
  `RESULT_CACHE`, `PIPELINED` or `PARALLEL_ENABLE`, which IslandSQL parsed in a one-off probe
  ([#67](https://github.com/rponte/penitent/issues/67)).
- `ENABLE EDITIONS FOR VIEW` with a materialized view reading one of the schema's views: `ORA-38819`, and with `FORCE`
  the materialized view left `INVALID`, measured once by hand, not by a test that runs
  ([#68](https://github.com/rponte/penitent/issues/68)).
- What a stub with a large value costs, measured once by hand, not by a test that runs: a collection of 1,000 object
  types compiled in under 1 s and one of 5,000 in about 3 s on 23ai Free; one of 20,000 did not compile, with
  `ORA-04036`, the instance's PGA limit, reported as a body that does not compile
  ([#130](https://github.com/rponte/penitent/issues/130)).
- Drivers 18.x and older ([on demand](roadmap.md#on-demand)).
- Resilience4j's `@Retry` on a `@Transactional` method, `@Bulkhead` and `@RateLimiter`
  ([#72](https://github.com/rponte/penitent/issues/72)).
- A Jakarta EE container's `DataSource` inside a JTA transaction, with `create` or with `PlsqlPackages.using`
  ([#73](https://github.com/rponte/penitent/issues/73)).
- Oracle types the library does not map yet, each with its own issue, grouped in
  [#74](https://github.com/rponte/penitent/issues/74): `XMLTYPE` ([#122](https://github.com/rponte/penitent/issues/122)),
  the `JSON` type of 21c and later ([#123](https://github.com/rponte/penitent/issues/123)), `LONG`
  ([#124](https://github.com/rponte/penitent/issues/124)), the object-relational `REF`, not `REF CURSOR`
  ([#125](https://github.com/rponte/penitent/issues/125)), and a nested cursor column, `CURSOR(...)`
  ([#121](https://github.com/rponte/penitent/issues/121)).
- Synonyms with a `db link`, which are currently ignored during resolution
  ([#75](https://github.com/rponte/penitent/issues/75)).
- A `default` method of a non-public interface on the module path: the package must be open to the library, and
  otherwise the call is a `PlsqlMappingException` saying so ([#77](https://github.com/rponte/penitent/issues/77)).
- A collection column in a cursor, and a collection of collections as an attribute, beyond the clear error
  ([#79](https://github.com/rponte/penitent/issues/79)).
- A `%ROWTYPE` whose table changed after the proxy described it, as a deploy does: the database closes the
  connection, with `ORA-03138`, or `No more data to read from socket` with driver 19, measured once, by hand, on 23ai
  and 19c, not by a test that runs. It cannot be told apart from a network failure, and is left to the pool
  ([#118](https://github.com/rponte/penitent/issues/118)). A changed routine or object type is Validated above.
- Two parameters of the same type that swap places in the routine, after a deploy: binding by position, each value
  goes to the other parameter, with no error, measured by hand on 23ai and 19c; binding by name gives the right
  result. A limit of binding by position, not a gap in the tests: see
  [Changing PL/SQL while the application runs](deploying.md).
- `java.util.Date`, `java.sql.Date` and `java.sql.Timestamp` for a local time that the JVM's time zone skips: they
  hold an instant in that zone, so they cannot represent it. Declare `LocalDateTime` or `LocalDate`. A limit of those
  Java types, not a gap in the tests.
