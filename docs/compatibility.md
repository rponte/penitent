# Compatibility

Status of the code on `main`, the next version after `0.22.0`, measured on Java 17 and 21, with Oracle 23ai
(`gvenzl/oracle-free:23.26.3`) and `ojdbc17`; the whole suite also runs with `ojdbc11` and `ojdbc8`, version
23.26.3, and with driver 21.24.0.0, as [Driver versions](#driver-versions) details. Versions up to `0.22.0` need
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

The Oracle JDBC driver has a version of its own, apart from the database's: driver 21 connects to Oracle 23ai too.
The number in the artifact, `ojdbc17`, `ojdbc11` or `ojdbc8`, is the Java version the jar is built for, not the
driver version.

| Driver | Artifacts | Status |
|---|---|---|
| 23.26.3 | `ojdbc17`, `ojdbc11`, `ojdbc8` | Validated: the whole suite |
| 21.24.0.0 | `ojdbc11`, `ojdbc8` | Validated: the whole suite, with the differences below |
| 19.x and older | | Not measured |

Before driver 23, the driver does some things differently. The library works around them, so that the same code
gives the same result on both versions, except for one case the driver cannot do at all:

| Before driver 23, the driver… | What the library does | Status |
|---|---|---|
| converts `LocalDateTime` and `LocalDate` through the JVM's time zone, in both directions: 02:30 on the day the clocks jump becomes 03:30 | Builds and reads the bytes of `DATE` and `TIMESTAMP` itself, without a time zone | Validated |
| has no `java.time` methods for intervals | Builds and reads the bytes of `INTERVAL` itself | Validated |
| fails with `ClassCastException` reading a `TIMESTAMP WITH LOCAL TIME ZONE` behind a pool | Hands the driver its own connection | Validated |
| refuses the standard `Types.BOOLEAN` ("Invalid column type: 16"), and reads a `null` `BOOLEAN` as `false` | Binds `BOOLEAN` as `OracleTypes.PLSQL_BOOLEAN` on every version, and asks the driver whether the value was `null` | Validated |
| does not resolve `SCHEMA.TABLE%ROWTYPE`, because it puts the whole name in quotes | Binds `"SCHEMA".TABLE%ROWTYPE`, which every version resolves | Validated |
| cannot read a `BOOLEAN` column in a cursor: it fails inside, and leaves the connection unusable | Discards the connection, so that the pool replaces it, and fails with a `PlsqlExecutionException` that says so. Convert the column in the query, as in `CASE WHEN flag THEN 'Y' ELSE 'N' END`, into a `Boolean` | Not supported, with a clear error |
| writes its error messages without the `ORA-` number and without the link to the error's page | Nothing: the messages reach you as the driver writes them. `getErrorCode()` is the same on both versions | Validated |

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
| Session `NLS_LANGUAGE` and `NLS_TERRITORY` of `AMERICA` and `BRAZIL`, as the driver derives them from the JVM locale | Dates, timestamps with and without time zone, and intervals cross unchanged, also as attribute, element and cursor column | Validated |
| `BOOLEAN` as return value | `boolean`, `Boolean` | Validated |
| `BOOLEAN` as parameter, including `null` | `boolean`, `Boolean` | Validated |
| `BOOLEAN` coming back `null`, as return value, `OUT` or `IN OUT` | `Boolean` `null`, not `false` | Validated |
| `NUMBER` as parameter | `boolean`, `Boolean`, as `1`/`0` or the numeric literals of `@PlsqlBoolean` | Validated |
| `NUMBER` as return value | `boolean`, `Boolean`, as `1`/`0` or the literals of `@PlsqlBoolean` on the method. Any other value is a `PlsqlMappingException` | Validated |
| `VARCHAR2` as parameter and return value | `boolean`, `Boolean`, by the literals of `@PlsqlBoolean` on the parameter or on the method | Validated |
| `BOOLEAN` with `@PlsqlBoolean` | The annotation has no effect | Validated |

`null` crosses as `null` for scalars, and a primitive rejects `NULL` with `PlsqlMappingException`, instead of making up a `false` or a `0` (validated).

## Composite types

| Form | Status |
|---|---|
| OBJECT, package RECORD and `%ROWTYPE`, as parameter and return value | Validated |
| RECORD containing RECORD, OBJECT and collections | Validated |
| OBJECT containing OBJECT and collections | Validated |
| Mapping to `record` and to JavaBean, matched by attribute name | Validated |
| JavaBean with inherited properties or extra properties | Validated |
| OBJECT inheritance (`UNDER`) | Validated |
| `null` in place of a whole OBJECT | Validated |
| `null` in place of a RECORD or `%ROWTYPE` | Validated: a RECORD is never null in PL/SQL, and comes back with null fields |
| Driver addressing, probed without the library: RECORD as `PACKAGE.TYPE`, `%ROWTYPE` as `TABLE%ROWTYPE`, package collection as `PACKAGE.TYPE`, and the names that fail (`ORA-17060`, `ORA-17074`) | Validated |
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
| Code not listed in `PlsqlFailure`, during execution | `PlsqlRoutineRaisedException`. Includes infrastructure errors the list does not anticipate | Validated by the rule, without triggering the error |
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

## Not measured

- Oracle before 23ai. Native `BOOLEAN` depends on 23ai.
- Driver 19.x and older.
- A Jakarta EE container's `DataSource` inside a JTA transaction, with `create` or with `PlsqlPackages.using`.
- `XMLTYPE`, `JSON`, `LONG`, `REF` and nested cursors.
- Synonyms with a `db link`, which are currently ignored during resolution.
- A `default` method of a non-public interface on the module path: the package must be open to the library, and
  otherwise the call is a `PlsqlMappingException` saying so.
- A block type (`@PlsqlType` on a parameter, `resultType`) in another schema or reached through a synonym: it is
  resolved in the session's schema only.
- A collection column in a cursor, and a collection of collections as an attribute, beyond the clear error.
- `isX()` getters and `@PlsqlBoolean` on JavaBeans.
- A signature change in the database after the proxy has cached the metadata.
- `java.util.Date`, `java.sql.Date` and `java.sql.Timestamp` for a local time that the JVM's time zone skips: they
  hold an instant in that zone, so they cannot represent it. Declare `LocalDateTime` or `LocalDate`.
