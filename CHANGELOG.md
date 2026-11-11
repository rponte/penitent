# Changelog

All notable changes to this project are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/): while in `0.x`, a minor version may change the public API.

## [Unreleased]

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

[Unreleased]: https://github.com/rponte/penitent/compare/v0.24.0...HEAD
[0.24.0]: https://github.com/rponte/penitent/compare/v0.23.0...v0.24.0
[0.23.0]: https://github.com/rponte/penitent/compare/v0.22.0...v0.23.0
[0.22.0]: https://github.com/rponte/penitent/compare/v0.21.0...v0.22.0
[0.21.0]: https://github.com/rponte/penitent/releases/tag/v0.21.0
