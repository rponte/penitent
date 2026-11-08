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
