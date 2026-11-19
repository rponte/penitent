# Changelog

All notable changes to this project are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/): while in `0.x`, a minor version may change the public API.

## [Unreleased]

### Added

- Oracle's `JSON` type, from 21c on, as a `String` holding the JSON text: an `IN`, `OUT` or `IN OUT` parameter, a
  return value and a cursor column, with driver 21 or later. It failed with `PLS-00306` or `PLS-00382`, and a cursor
  column with `ORA-18722`. The text that comes back is the database's, which keeps a number's value but not its
  written form. A collection of `JSON`, a RECORD with a `JSON` field and a `%ROWTYPE` of a table with a `JSON`
  column, which no driver can describe, are refused with a clear message, and so is the type with driver 19, before
  the call. To map the text to an object, use a converter with the application's JSON library.

## [0.31.0]

Changing PL/SQL under a running application, and stubs of everything a call sends. A routine or a type that changed
since a proxy described it now says so, and a new page explains what a deploy does to the calls, and how a pool
recovers. A block's types resolve as PL/SQL resolves them. The mocks stub standalone routines, records, `%ROWTYPE` and
`INDEX BY`, write and match every scalar type, and are measured on Oracle 21c and 19c, besides 23ai.

### Upgrading from 0.30.0

1. `penitent-test`: apply `penitent-mock-schema.sql` again, now version 2, or call `PlsqlMocks.prepare`. Until then,
   `begin()` refuses the schema, saying so.
2. `penitent-test`: to stub a standalone function or procedure, a DBA runs `ALTER USER <schema> ENABLE EDITIONS FOR
   FUNCTION` and `FOR PROCEDURE`, as `penitent-mock-admin.sql` now does. Stubs of packages need nothing.
3. `penitent-test`: `thenRaise("NO_DATA_FOUND")` becomes `thenRaise(PredefinedError.NO_DATA_FOUND)`, and
   `when(() -> m.p(...))` becomes `doNothing().when(m).p(...)`.
4. A JavaBean with both `isX()` and `getX()` for one property is refused: keep one of them.
5. With `penitent-spring`, a routine that changed since the proxy described it fails with a `PlsqlMappingException`,
   no longer a `BadSqlGrammarException`.

### Added

- A block's `@PlsqlType` and `resultType` resolve a type as PL/SQL does: of another schema, as `OTHER.TYPE_T`,
  `OTHER.PKG.RECORD_T` or `OTHER.TABLE%ROWTYPE`, and through a private or public synonym of a type, a package or a
  table. They were looked up in the session's schema only, and `OTHER.TYPE_T` was read as a type of a package `OTHER`.
- `penitent-test`: a stub matches, and `verify` counts, an argument by value for every scalar type a stub writes:
  text of any length and LOBs, compared with `DBMS_LOB.COMPARE`, bytes, time zones, intervals and the binary
  numbers, and an enum, a `UUID`, a `@PlsqlBoolean`, a `java.util.Date` and a converter, through the parameter's
  mapping. `verify` binds the value matched, rather than writing it in the query.
- `penitent-test`: a stub returns, and sets as an `OUT`, time zones, intervals, a `RAW`, a `BLOB` up to 16 KB and a
  `CLOB` above 32 KB, which were refused or did not compile. They are written as `TIMESTAMP` literals with an offset
  or a region, `TO_DSINTERVAL` and `TO_YMINTERVAL`, `HEXTORAW`, and `TO_CLOB` pieces joined with `||`.
- `penitent-test`: a stub's value, returned or set as an `OUT`, goes through the application's mapping, as a call
  would send it: an enum, a `UUID`, a `java.util.Date`, a `java.sql.Timestamp`, an `Optional` and a converter on the
  method or the parameter, which were refused or not applied, now work.
- `penitent-test`: a stub can return a `RECORD` of a package, a `%ROWTYPE` and an `INDEX BY PLS_INTEGER`, and set
  one as an `OUT`, which were refused for having no constructor in PL/SQL. They are written as qualified expressions:
  an `INDEX BY` with named indexes, and a `%ROWTYPE` as a `RECORD` of the table's columns that the mocks declare in
  the test's edition, which Oracle assigns to it. A collection of records, which was refused too, works as well.
- `penitent-test`: the mocks are measured on Oracle 21c and 19c, besides 23ai. On 19c, the Autonomous Database Free
  needs no change: its `ADMIN` runs `penitent-mock-admin.sql`. The module's tests, skipped on both until now, pass
  there.
- `penitent-test`: stubs of standalone functions and procedures, outside any package, from an interface with
  `@PlsqlSchema`, with the same API as a routine of a package. The whole routine is rewritten in the test's edition.
- `penitent-test`: `PlsqlError`, the exception a stub raises: an Oracle predefined exception, from the enum
  `PredefinedError`, with its code, or an exception a package declares in its spec, from `PlsqlError.declared`, which
  the PL/SQL that calls the routine catches by name. A name that does not exist is reported as the likely mistake.
- `penitent-test`: the `doXxx()` family, as in Mockito, for stubs of procedures: `doNothing()`, `doThrow(code,
  message)` and `doRaise(error)`, as in `doNothing().when(freights).quote(eq("CE"), out(value))`; and
  `doReturn(value)` for functions. The answer comes before the call, so a stub that raises compiles the package once.
- `penitent-test`: `anyInt()`, `anyLong()`, `anyShort()`, `anyByte()`, `anyDouble()`, `anyFloat()` and `anyBoolean()`
  match any value of a parameter of a primitive type, in a stub and in `verify`. `any()` returns `null`, which Java
  unboxes before the call reaches the mock, and failed with a `NullPointerException`.

### Changed

- A routine or a type that changed in the database after a proxy described it, as a deploy under a running
  application does, is described again when a call fails because of it. A call that failed with `PLS-00306`,
  `PLS-00222` or `PLS-00302`, or with `ORA-03101` or 21c's `ORA-00600` for a composite, now throws a
  `PlsqlMappingException` that says the routine or the type changed since the proxy described it, and what no longer
  fits, as a new proxy would, rather than Oracle's raw error until a restart. `ORA-17049` stays a
  `PlsqlExecutionException`, with its code, and says that the driver keeps the old type in each connection until it is
  renewed. The call is never repeated. With `penitent-spring`, such a failure was a `BadSqlGrammarException`, from
  Oracle's `ORA-06550`; it is now a `PlsqlMappingException`, which no `PlsqlExceptionTranslator` sees, as every
  mapping error, and as a new proxy would throw.
- A JavaBean with both `isX()` and `getX()` for one property is refused, naming both, where the library used one of
  them without saying which: they may answer differently, as a `boolean` and a `Boolean` that is `null`.
- `penitent-test`: a script that replaces a package spec without its body compiles the body in the test's edition at
  its end, and is refused, with the body's errors, when the body no longer fits the spec, which would otherwise fail
  with `ORA-04063` at the first call.
- `penitent-test`: a script refused because the schema does not edition its type, as a synonym in a schema prepared by
  `penitent-mock-admin.sql`, also warns that the `ALTER USER` it asks for fails with `ORA-38819` when an object without
  editions depends on one of them, and that `FORCE` would leave that object `INVALID`.
- `penitent-test`: `penitent-mock-schema.sql` is version 2: `PENITENT_MOCK_CALLS.ARGS` and the argument of
  `PENITENT_MOCK.record` are `CLOB`s. A schema prepared by version 1 is refused by `begin()`, asking for the script to
  be applied again, which `PlsqlMocks.prepare` does; the script drops the table of version 1, which holds only the
  calls of tests, and creates it again. It finds that table in the session's current schema, so another user may
  apply it with the current schema set to the owner, as a migration tool such as Flyway does.
- `penitent-test`: `penitent-mock-admin.sql` also enables editions for functions and procedures, each in a statement
  of its own, for stubs of standalone routines. A schema prepared before keeps working for stubs of packages; a stub of
  a standalone routine there is refused, saying what a DBA has to run.

### Removed

- The call log's `Type names from` line, which named the internal class that gives types other names in a test with
  mocks. It showed up once, at the first call that built a composite value, as if it were about that call; each call
  that binds a type by another name still shows it, with `-> type name`.
- `penitent-test`: `thenRaise(String)`, replaced by `thenRaise(PlsqlError)`: `thenRaise("NO_DATA_FOUND")` becomes
  `thenRaise(PredefinedError.NO_DATA_FOUND)`.
- `penitent-test`: `when(Runnable)`, the lambda that stubbed a procedure, replaced by the `doXxx()` family:
  `when(() -> m.p(...))` becomes `doNothing().when(m).p(...)`, and `when(() -> m.p(...)).thenThrow(code, message)`
  becomes `doThrow(code, message).when(m).p(...)`.

### Fixed

- `NaN` and the infinities sent to a `BINARY_DOUBLE` or a `BINARY_FLOAT` parameter failed in the driver, with a
  `NumberFormatException` or an overflow, since the value was bound as a `NUMBER`. Such a parameter is bound with its
  type now, and a block binds a `NaN` or an infinity as a `BINARY_DOUBLE`. Sent to a `NUMBER`, which holds neither,
  the value is refused before the call, naming the parameter.
- A `%ROWTYPE` of a table with an invisible column failed to bind, asking the Java type to cover that column, which
  PL/SQL leaves out of a `%ROWTYPE`. It is left out now.
- A `%ROWTYPE` with a column of an object type or a collection failed to bind, with `ORA-17059` when sending it and a
  conversion error when reading it: its columns were all taken as scalars. Such a column now binds as the attribute
  of an object type does.
- An OBJECT, a package RECORD, a `%ROWTYPE` or a collection whose name needs quotes for more than its case, such as
  `CREATE TYPE "Two Words_T"`, failed with `ORA-17060` or `ORA-17074`: on driver 23 for every form, and on drivers 21
  and 19 for a `%ROWTYPE`. Such a name is now bound with every part in quotes, the owner included, which every driver
  resolves. A mixed-case name, such as `"Mixed"`, already bound, since the driver reads a name with its exact case.
- `penitent-test`: a stub that returns an object type with a `TIMESTAMP WITH TIME ZONE` or `WITH LOCAL TIME ZONE`
  attribute, refused as "a oracle.sql.TIMESTAMPTZ cannot be written as PL/SQL", and one with a `BOOLEAN` attribute on
  Oracle 23ai, whose body did not compile with `PLS-00306`, since the driver gives the attribute as a number.
- `penitent-test`: a stub of a composite type whose quoted name holds a dot, such as `"Dot.T"`, or of a type of a
  package whose name holds one, did not compile: the driver gives such a name without its quotes, and the mocks cut it
  at every dot. They now read it in the dictionary, and refuse, naming both, the rare name the driver gives two types.
- `penitent-test`: dropping the test's edition at `end()` failed now and then on Oracle 19c, on the Autonomous
  Database, with `ORA-00060`, a deadlock, or `ORA-00604` over `ORA-04043`. The drop is tried again on those errors, up
  to 3 times, with a warning in the log each time; any other error still fails at once.
- `penitent-test`: a call to a stubbed routine failed with `ORA-40478` when its arguments, recorded for `verify`,
  went above 4,000 bytes, such as a `VARCHAR2` of 5,000 characters or a large `CLOB`. The arguments are recorded in a
  `CLOB` now, and the message of a call no stub matches shows their first 1,500 characters.
- `penitent-test`: a stub that returned a `Boolean` for a routine mapped with `@PlsqlBoolean`, or as `1` or `0`,
  generated a body that did not compile, writing `TRUE` where a `VARCHAR2` or a `NUMBER` was due. A stub that
  returned `NaN` or an infinity failed with a `NumberFormatException`. Both are written as a call would send them now.
- `penitent-test`: a routine stubbed in one test through two interfaces that bind it differently, by position and by
  name or in another order, lost the stubs of the first interface without a word. It is now refused with a clear
  message, and the stubs made before it still answer.
- `penitent-test`: a stub of a routine bound by name failed to find the routine in the body, when the Java method
  declared its parameters in another order than the routine, or left out one with a `DEFAULT`.
- `penitent-test`: on Oracle 21c, a stub of a routine with a `BOOLEAN` parameter failed to compile, with
  `PLS-00382`: the call was recorded through SQL, which takes a `BOOLEAN` only from 23ai on. It is now recorded as
  `'true'` or `'false'`, and `verify` compares it as that text, on every version.

## [0.30.0]

The mocks reach further. A stub answers with object types and collections, a routine that takes or returns a
`%ROWTYPE` works in a test with mocks, and a script replaces a view, a package, a standalone routine or a synonym in
the test's edition, with `applyScript` or `@PlsqlMockScript`. Measured on Oracle 23ai.

### Added

- `penitent-test`: `applyScript` and `applyScriptText` apply a script in the test's edition, to replace a view, a
  package, its body, a function, a procedure or a synonym that only the test sees. The script is read as SQL*Plus
  reads it, and every statement is checked before any runs: anything that would reach the database outside the
  edition is refused. The admin script now enables editions for views too, in a statement of its own.
  `@PlsqlMockScript` applies them from the test class and method, with the JUnit 5 extension and with
  `@EnablePlsqlMocks`.
- `penitent-test`: a stub can return an object type or a collection, and set one as an `OUT`, with `thenReturn` and
  `out(value)`, mapped as the application maps them. The value is written as a PL/SQL constructor, which Java and
  PL/SQL calling the routine both get. A `RECORD`, a `%ROWTYPE` and an `INDEX BY` have no constructor, and are
  refused before compiling.

### Changed

- `penitent-test`: `penitent-mock-admin.sql` enables editions for views too, in a statement apart from the packages'.
  A schema prepared with 0.29.0 keeps working for stubs; to replace a view with a script, a DBA runs
  `ALTER USER <schema> ENABLE EDITIONS FOR VIEW`, which cannot be undone.

### Fixed

- `penitent-test`: a stub of a routine that takes an object type, a collection or a record no longer fails to
  compile with `ORA-00939`. Such a parameter is matched with `any()`, and another matcher on it is refused with a
  clear message.
- `penitent-test`: a routine that takes or returns a `%ROWTYPE`, or a collection of them, can be called in a test with
  mocks, stubbed or not. It failed with `ORA-17060`, since Oracle cannot describe `TABLE%ROWTYPE` to the driver in the
  test's edition; the record now binds there by a subtype of it, compiled into the edition. The call log shows the
  name, at `DEBUG`.

## [0.29.0]

Mocking PL/SQL in tests. A new module, `penitent-test`, stubs a routine of a package in an integration test, in the
style of Mockito, from the same interface the application calls it through, and a new Testing page shows how to test
code that calls PL/SQL, with and without mocks. Measured on Oracle 23ai.

### Added

- `penitent-test`, a new module that stubs a routine of a package in an integration test, in the style of Mockito,
  from the same interface the application calls it through: the stub is real PL/SQL, compiled into an edition of the
  test's own, so Java and other PL/SQL calling the routine see it, while the rest of the package stays real. It works
  with JUnit 5 (`PlsqlMockExtension`), with Spring (`@EnablePlsqlMocks`) and on its own. Measured on Oracle 23ai; the new Testing page
  shows how to test code that calls PL/SQL, with and without mocks, and architecture decision 0001 has the design.
  The mocks log what they do under `io.github.rponte.penitent.mock`.

## [0.28.0]

Interfaces Spring wraps. An interface with `@Transactional`, `@Validated`, Resilience4j or advice of the application
now works with validation at startup, `validate` and the views of `using`, and the Spring page shows Bean Validation,
Resilience4j and `@Transactional` on interfaces, all measured.

### Fixed

- An interface that Spring wraps in a proxy of its own, for `@Transactional`, `@Validated`, Resilience4j or advice of
  the application, no longer breaks validation at startup, `PlsqlPackages.validate`, `tx.using` or
  `PlsqlPackages.using` with "is not a proxy created by PlsqlPackages". A view keeps every layer Spring put around the
  bean, in the same order, and runs on its connection; inside a `PlsqlTransactions` block, a `@Transactional` method of
  the view is refused, as any Spring transaction there is.

### Added

- The Spring page shows `@Transactional` on an interface's methods, measured: alone, inside another transaction,
  with `REQUIRES_NEW`, and refused inside a `PlsqlTransactions` block.
- The Spring page shows Bean Validation and Resilience4j on interfaces, both measured: constraints on parameters are
  checked before the call reaches the database, `@Valid` cascades into records, and constraints on the return value
  are checked after the call; `@Retry` and `@CircuitBreaker` work as on any bean, with advice on what to retry and
  where, inside a transaction.

## [0.27.0]

One transaction model per application. In a Spring application, `PlsqlTransactions` and Spring's transactions
refuse to overlap, instead of splitting the work in two, and the transactions page shows how to move to Spring's.

### Changed

- In a Spring application, `PlsqlTransactions` and Spring's transactions no longer split the work in two without a
  word. With `penitent-spring`, a block inside a Spring transaction, a Spring transaction inside a block, and an
  interface of `penitent-spring` called inside a block without `tx.using` each fail with an `IllegalStateException`
  that says what to do, before anything runs. A block outside Spring's transactions keeps working as before. The
  transactions page shows how to move from `PlsqlTransactions` to Spring's transactions, one construct at a time.

## [0.26.0]

Oracle 19c. The whole suite runs against Oracle 19c as well as 21c and 23ai, with every driver from 19 to 23,
covering every Oracle Database release Oracle still supports.

### Changed

- Oracle 19c is supported: every build also runs the whole suite against it, with every driver from 19 to 23, on
  Oracle's Autonomous Database Free, the 19c image Oracle publishes for anyone to pull. It behaves as Oracle 21c, with
  no change in the library: `BOOLEAN` exists only in PL/SQL, and driver 23 reads a `null` `BOOLEAN` that comes back as
  `true` or `false`, with the same warning and the same ways around it.

## [0.25.0]

Oracle 21c. The whole suite runs against Oracle 21c as well as 23ai, with every driver from 19 to 23, and the
compatibility page has a new section on database versions.

### Changed

- Oracle 21c is supported: every build also runs the whole suite against it, with every driver from 19 to 23. Before
  23ai, `BOOLEAN` exists only in PL/SQL, where it works as on 23ai. Driver 23 reads a `null` `BOOLEAN` that comes back
  from such a database as `true` or `false`; Penitent cannot read it right, so it logs a warning, once per method, and
  the compatibility page lists the ways around it, such as the driver of the database's version or the value handed
  back as `VARCHAR2`, which still reads into a `Boolean`.

## [0.24.0]

Driver 19. Every Oracle JDBC driver from 19 to 23 now gives the same results against Oracle 23ai: where driver 19
lacks the `java.time` conversions of the two types with a time zone, or gets one of them wrong, the library reads and
builds their bytes itself. The README and the documentation's home page are rewritten to show what Penitent saves over
plain JDBC and Spring's `SimpleJdbcCall`.

### Changed

- Driver 19 is supported: every build also runs the whole suite with `ojdbc10` and `ojdbc8` 19.33.0.0, against Oracle
  23ai. Driver 19 has no `java.time` methods for the two types with a time zone, and stores a `TIMESTAMP WITH LOCAL
  TIME ZONE` attribute or collection element at the value's local time without its offset, three hours off for
  10:30-03:00; the library reads and builds those bytes itself. As with driver 21, the one thing it cannot do is read
  a `BOOLEAN` column in a cursor.
- The README, the documentation's home page and the comparison with JDBC are rewritten: type-safe calls checked
  against the database, a quick start with a package, its interface and the log of a call, and what plain JDBC and
  Spring's `SimpleJdbcCall` each leave to you.

## [0.23.0]

Java 17, and the drivers before 23. The library is compiled for Java 17 and tested on it and on Java 21, with every
artifact of driver 23, `ojdbc17`, `ojdbc11` and `ojdbc8`, and with driver 21, against Oracle 23ai. Where driver 21
behaves differently, the library works around it, with the same result on both; the
[compatibility table](https://rponte.github.io/penitent/compatibility/#driver-versions) lists each difference, and
the one it cannot work around. On the way, a negative `Duration` of whole seconds now reaches the database as itself,
which driver 23 got wrong.

### Fixed

- Reading a `TIMESTAMP WITH LOCAL TIME ZONE` no longer depends on the driver unwrapping the pool's connection by
  itself: before driver 23, it failed with a `ClassCastException` behind a pool such as Hikari's.
- A `LocalDateTime` or `LocalDate` no longer depends on the driver version to cross a `DATE` or `TIMESTAMP` without
  the JVM's time zone: the library builds and reads the bytes itself. Before driver 23, a local time that zone skips,
  such as 02:30 on the day daylight saving time starts, became 03:30 without any error, and a day without midnight
  got 01:00.
- A negative `Duration` of whole seconds, such as -1 second, reaches the database as itself. Driver 23 wrote it
  with one second less and -1,000,000,000 nanoseconds, which `to_char` and `extract` read as written: -1 second
  became `-0 00:00:00`, and the seconds of -1 minute became -60.
- A native `BOOLEAN` binds on every driver version: it goes as `OracleTypes.PLSQL_BOOLEAN` instead of
  `Types.BOOLEAN`, which drivers before 23 refuse with "Invalid column type: 16". A `null` that comes back is `null`
  on every version too: driver 21 reads it as `false`.
- A `%ROWTYPE` binds on every driver version, with the owner in quotes: before driver 23, the driver put the whole
  name in quotes, and `SCHEMA.TABLE%ROWTYPE` failed with "Unable to resolve type".
- Before driver 23, a cursor with a `BOOLEAN` column made the driver fail with an `AssertionError` and left the
  connection unusable in the pool, so the next call on it failed with "Protocol violation". The call now fails with a
  `PlsqlExecutionException` that explains the cause and the way around it, and the connection is discarded.

### Changed

- Java 17 is the minimum version, instead of 21: the library is compiled for Java 17, and every build runs the tests
  on Java 17 and on Java 21.
- The `ojdbc11` and `ojdbc8` flavors of the Oracle driver are tested too, not only `ojdbc17`: every build runs the
  whole suite with each of them, at the driver version of the Spring Boot BOM.
- Driver 21 is supported: every build also runs the whole suite with `ojdbc11` and `ojdbc8` 21.24.0.0, against Oracle
  23ai. The one thing it cannot do is read a `BOOLEAN` column in a cursor; the compatibility table lists what differs
  and what the library does about it.
- A `Period` with days, which `INTERVAL YEAR TO MONTH` does not store, is refused with `PlsqlMappingException`
  before the call, instead of the driver's `IllegalArgumentException`.

## [0.22.0]

Transactions without Spring, and error messages with one shape. `PlsqlTransactions` runs a block in one transaction
in the core alone, `PlsqlPackages.using` binds an interface to a connection you manage, and every mapping error of a
method now starts with the method and its parameter types. The documentation is published at
[rponte.github.io/penitent](https://rponte.github.io/penitent/), with the Javadoc at
[/api](https://rponte.github.io/penitent/api/).

### Added

- `PlsqlPackages.using(proxy, connection)`: a view of an interface bound to a connection of the application's, so
  that its calls take part in a transaction the application, or its framework, controls. It shares the interface's
  dictionary cache and settings, and never closes, commits or rolls back the connection. See
  [Transactions and connections](https://github.com/rponte/penitent/blob/main/docs/transactions.md).
- `PlsqlTransactions`, a minimal and explicit transaction for the core alone: `run` and `runWithResult` commit when
  the block returns and roll back when it throws, keeping the exception as thrown. Interfaces take part through
  `tx.using`, plain JDBC through a `tx.connection()` that refuses to end the transaction, and every way of leaving the
  transaction unnoticed is refused.
- `PlsqlFailure.Phase.TRANSACTION`, for a failure to begin, commit or roll back a `PlsqlTransactions` block.

### Changed

- Every mapping error of a method has one shape: `method(types): detail`, as in
  `countOrders(long, String): parameter customerId looks for P_CUSTOMER, which …`. The parameter types tell overloads
  apart, and a parameter is named with its routine, as in `P_ORDER_ID of SHOP.ORDERS_PKG.PLACE_ORDER`. Code that
  matches the text of these messages needs to follow.
- The routines listed after `Available:` appear once each, instead of once per overload, and the versions of an
  overloaded routine are shown as `(P_VALUE NUMBER) RETURN VARCHAR2`, without a `null` for the return value.

## [0.21.0]

The first public release, and the first on Maven Central. Penitent maps Oracle PL/SQL packages to Java interfaces,
driven by the database dictionary: see the [README](https://github.com/rponte/penitent#readme) and the [documentation](https://github.com/rponte/penitent/tree/main/docs).

### Fixed

- A `LocalDateTime` or `LocalDate` no longer depends on the JVM's time zone. In a zone with daylight saving time, a
  local time that the zone skips, such as 02:30 on the day the clocks jump, used to move an hour without any error,
  and a day without midnight was stored at 01:00.
- A `TIMESTAMP WITH TIME ZONE` attribute of an OBJECT, read into a `ZonedDateTime`, keeps the region instead of only
  the offset.

### Changed

- Spring Boot 4.1.2 for the versions the library is built and tested with.

[Unreleased]: https://github.com/rponte/penitent/compare/v0.31.0...HEAD
[0.31.0]: https://github.com/rponte/penitent/compare/v0.30.0...v0.31.0
[0.30.0]: https://github.com/rponte/penitent/compare/v0.29.0...v0.30.0
[0.29.0]: https://github.com/rponte/penitent/compare/v0.28.0...v0.29.0
[0.28.0]: https://github.com/rponte/penitent/compare/v0.27.0...v0.28.0
[0.27.0]: https://github.com/rponte/penitent/compare/v0.26.0...v0.27.0
[0.26.0]: https://github.com/rponte/penitent/compare/v0.25.0...v0.26.0
[0.25.0]: https://github.com/rponte/penitent/compare/v0.24.0...v0.25.0
[0.24.0]: https://github.com/rponte/penitent/compare/v0.23.0...v0.24.0
[0.23.0]: https://github.com/rponte/penitent/compare/v0.22.0...v0.23.0
[0.22.0]: https://github.com/rponte/penitent/compare/v0.21.0...v0.22.0
[0.21.0]: https://github.com/rponte/penitent/releases/tag/v0.21.0
