# Changelog

All notable changes to this project are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/): while in `0.x`, a minor version may change the public API.

## [Unreleased]

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

[Unreleased]: https://github.com/rponte/penitent/compare/v0.21.0...HEAD
[0.21.0]: https://github.com/rponte/penitent/releases/tag/v0.21.0
