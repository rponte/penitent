# Penitent

[![CI](https://github.com/rponte/penitent/actions/workflows/ci.yml/badge.svg)](https://github.com/rponte/penitent/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.rponte/penitent-core)](https://central.sonatype.com/namespace/io.github.rponte)

Penitent maps Oracle PL/SQL packages to Java interfaces. You write the interface; Penitent reads the routine
signatures and type attributes from the database dictionary and builds the calls, so Oracle type names and parameter
order are never written by hand.

```java
@PlsqlPackage("ORDERS_PKG")
public interface Orders {
    // FUNCTION calculate_freight(p_origin VARCHAR2, p_destination VARCHAR2) RETURN NUMBER
    BigDecimal calculateFreight(String origin, String destination);
}

Orders orders = PlsqlPackages.create(Orders.class, dataSource);
BigDecimal freight = orders.calculateFreight("SP", "CE");   // 25.5
```

- **The database is the source of truth.** The dictionary says which routine, parameters and types exist, and the
  interface only has to agree with it. OBJECT, RECORD, `%ROWTYPE`, collections and cursors map to Java records and
  JavaBeans without naming a type.
- **Strict.** A method that does not match the database fails with a message that says what was looked up and what
  exists, never with a guess or a silently swapped value. `PlsqlPackages.validate` checks a whole interface up front.
- **No framework required.** `penitent-core` depends only on the JDK, SLF4J and the Oracle driver. `penitent-spring`
  adds Spring transactions and exceptions, and the Spring Boot starter registers the interfaces as beans.

## Status

Version `0.23.0`. The library is complete enough to use, and every feature listed in the
[compatibility table](docs/compatibility.md) has a test against a real Oracle. Keep in mind:

- It is measured on **Java 17 and 21, drivers 23.x (`ojdbc17`, `ojdbc11`, `ojdbc8`) and 21.x (`ojdbc11`, `ojdbc8`),
  and Oracle 23ai**. Older drivers and databases are on the [roadmap](docs/roadmap.md).
- It is in `0.x`, so the public API may still change between minor versions.

## Installation

The library is on Maven Central. Add the module that fits your application. The driver is `provided`: your
application chooses its version.

| Application | Module |
|---|---|
| Plain Java | `penitent-core` |
| Spring, without Boot | `penitent-spring` |
| Spring Boot | `penitent-spring-boot-starter` |

```xml
<dependency>
    <groupId>io.github.rponte</groupId>
    <artifactId>penitent-spring-boot-starter</artifactId>
    <version>0.23.0</version>
</dependency>
<dependency>
    <groupId>com.oracle.database.jdbc</groupId>
    <artifactId>ojdbc17</artifactId>
    <!-- managed by Spring Boot; without it, give a version such as 23.26.3.0.0 -->
</dependency>
```

## Quick start

For a step-by-step walkthrough, with the PL/SQL package and every call, see
[Getting started](docs/getting-started.md).

### Plain Java

```java
Orders orders = PlsqlPackages.create(Orders.class, dataSource);
```

Each call takes a connection from the `DataSource`, uses it and gives it back. A database failure arrives as a
`PlsqlExecutionException`, and an error raised by the routine as its subclass `PlsqlRoutineRaisedException`, with the
`ORA-` code.

### Spring

```java
Orders orders = SpringPlsqlPackages.create(Orders.class, dataSource);
```

The call takes part in the current transaction, and database failures arrive as `DataAccessException`, as with a
`JdbcTemplate`. To register the interfaces as beans instead, use `@EnablePlsqlPackages("com.example.plsql")`.

### Spring Boot

With the starter there is nothing to configure: every interface in the application's packages becomes a bean, and a
service injects it.

```java
@Service
class CheckoutService {

    private final Orders orders;

    CheckoutService(Orders orders) {
        this.orders = orders;
    }
}
```

```yaml
penitent:
  bind: BY_NAME          # optional: bind parameters by name instead of position
  validation:
    enabled: true        # optional: check every interface against the database at startup
```

## Features

- [Calling routines](docs/routines.md): package and standalone routines, `DEFAULT`, overloads, synonyms, `OUT` and
  `IN OUT` parameters with `PlsqlOut<T>`, and binding by position or by name.
- [Types](docs/types.md): scalars, booleans by literal, time zones, OBJECT, RECORD, `%ROWTYPE`, collections at any
  depth and cursors.
- [Anonymous blocks](docs/anonymous-blocks.md) with `@PlsqlBlock`, for member methods and anything else that is not a
  `package.routine` call.
- [Converters](docs/converters.md) for your own types, and enums stored by code.
- [Settings](docs/settings.md) shared by every interface, without static state.
- [Errors](docs/errors.md) that separate the routine's own errors from infrastructure failures.
- [Transactions and connections](docs/transactions.md): with Spring, with `PlsqlTransactions` in the core alone, or
  on a connection you manage, with `PlsqlPackages.using`.
- [Validation](docs/validation.md) of every interface up front, with every mismatch in one message.
- [Spring and Spring Boot](docs/spring.md) integration.
- [Logging](docs/logging.md) of each call, with the SQL and, separately, the values.

New to it? [How Penitent works](docs/concepts.md) explains the terms and what happens in a call,
[Coming from JDBC](docs/coming-from-jdbc.md) compares it with `SimpleJdbcCall`, and
[Troubleshooting](docs/troubleshooting.md) goes through the common error messages. The
[documentation index](docs/README.md) lists every page, by goal.

## Packages

| Package | What it holds |
|---|---|
| `io.github.rponte.penitent` | What you write every day: `PlsqlPackages`, the annotations (`@PlsqlPackage`, `@PlsqlSchema`, `@PlsqlRoutine`, `@PlsqlBlock`, `@PlsqlParam`, `@PlsqlType`, `@PlsqlAttr`, `@PlsqlBoolean`), `PlsqlOut`, `PlsqlBind` and `PlsqlSettings` |
| `io.github.rponte.penitent.convert` | Conversion: `PlsqlConverter`, `PlsqlConverters`, `PlsqlEnumCodeConverter` and `@PlsqlConvert` |
| `io.github.rponte.penitent.spring` | The `penitent-spring` module: `SpringPlsqlPackages`, `@EnablePlsqlPackages`, `PlsqlPackagesScanner` and `PlsqlPackagesValidator` |
| `io.github.rponte.penitent.boot` | The `penitent-spring-boot-starter` module: `PlsqlAutoConfiguration`, `PlsqlProperties` and `PlsqlSettingsCustomizer` |
| `io.github.rponte.penitent.exception` | Errors and their translation: `PlsqlMappingException`, `PlsqlExecutionException`, `PlsqlRoutineRaisedException`, `PlsqlFailure`, `PlsqlExceptionTranslator` and `DefaultPlsqlExceptionTranslator` |

`io.github.rponte.penitent.internal` and its subpackages are not part of the API: they may change in any version, without
notice.

## Requirements

- Java 17 or later, and Oracle Database; `0.22.0` and earlier need Java 21. The code is
  tested on Java 17 and 21, against Oracle 23ai.
- `ojdbc17`, `ojdbc11` or `ojdbc8`, version 23.x, or `ojdbc11` or `ojdbc8`, version 21.x, on the classpath of the
  application using the library (`provided` dependency). The tests run with each of them. Driver 21 cannot read a
  `BOOLEAN` column in a cursor: see [Driver versions](docs/compatibility.md#driver-versions).
- An SLF4J implementation, to see the log, such as the Logback that Spring Boot already ships. Without one, SLF4J
  warns once and discards the log.
- For `@PlsqlBlock`, and for binding by name without `@PlsqlParam`: compile with `-parameters`. The Spring Boot parent
  already turns it on.

## Build

```
./mvnw verify    # requires Docker: the tests start their own Oracle through Testcontainers
./mvnw install   # installs into ~/.m2, to try a change in an application before it is released
```

Each module also builds a sources jar and a Javadoc jar. The Javadoc covers only the API: packages under
`io.github.rponte.penitent.internal` are left out, because they may change in any version. Doclint runs on every build and
fails it on any problem except missing `@param`/`@return` tags.

Every change of behavior updates the [compatibility table](docs/compatibility.md) in the same commit. See
[CONTRIBUTING.md](CONTRIBUTING.md) for the rules and how to propose a change, and [CHANGELOG.md](CHANGELOG.md) for what
changed in each version.

## License

[Apache License 2.0](LICENSE).
