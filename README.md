# Penitent

[![CI](https://github.com/rponte/penitent/actions/workflows/ci.yml/badge.svg)](https://github.com/rponte/penitent/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.rponte/penitent-core)](https://central.sonatype.com/namespace/io.github.rponte)

Penitent gives you type-safe calls to Oracle PL/SQL from Java interfaces, checked against the database's own
signatures.

You describe a package as a Java interface, and Penitent builds every call from the routines' signatures in the
database dictionary: the compiler checks your calls, the database checks your interface, and Oracle type names and
parameter order are never written by hand.

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
- **Checked, not guessed.** Each method is matched to its routine parameter by parameter and type by type, and
  `PlsqlPackages.validate` checks every interface at startup, so a package that changed is noticed before the first
  request. Every mapping in the [compatibility table](docs/compatibility.md) is tested against a real Oracle.
- **No framework required.** `penitent-core` depends only on the JDK, SLF4J and the Oracle driver. Spring and Spring
  Boot support live in their own modules.

The version is `0.26.0`. Every feature in the [compatibility table](docs/compatibility.md) has a test against a real
Oracle, but the library is in `0.x`, so the public API may still change between minor versions. The documentation is
at [rponte.github.io/penitent](https://rponte.github.io/penitent/).

## Installation

Pick the module that fits your application. The Oracle driver is a `provided` dependency, so your application chooses
it:

| Application | Module |
|---|---|
| Plain Java | `penitent-core` |
| Spring, without Boot | `penitent-spring` |
| Spring Boot | `penitent-spring-boot-starter` |

```xml
<dependency>
    <groupId>io.github.rponte</groupId>
    <artifactId>penitent-spring-boot-starter</artifactId>
    <version>0.26.0</version>
</dependency>
<dependency>
    <groupId>com.oracle.database.jdbc</groupId>
    <artifactId>ojdbc17</artifactId>
    <!-- managed by Spring Boot; without it, give a version such as 23.26.3.0.0 -->
</dependency>
```

## Quick start

Take a PL/SQL package like this one:

```sql
CREATE OR REPLACE PACKAGE orders_pkg AS
    FUNCTION  calculate_freight(p_origin VARCHAR2, p_destination VARCHAR2) RETURN NUMBER;
    PROCEDURE place_order(p_customer_id NUMBER, p_total NUMBER, p_order_id OUT NUMBER);
    FUNCTION  list_orders_by_customer_id(p_customer_id NUMBER) RETURN SYS_REFCURSOR;
END orders_pkg;
```

Describe it as a Java interface, with plain Java types. A method named after its routine in camelCase needs nothing
else; `@PlsqlRoutine` gives a method a name of your own:

```java
@PlsqlPackage("ORDERS_PKG")
public interface Orders {

    BigDecimal calculateFreight(String origin, String destination);

    void placeOrder(long customerId, BigDecimal total, PlsqlOut<Long> orderId);

    @PlsqlRoutine("LIST_ORDERS_BY_CUSTOMER_ID")
    List<OrderRow> ordersOf(long customerId);
}

public record OrderRow(Long id, BigDecimal total, String status) {
}
```

Create it once, and call it like any other Java object:

```java
Orders orders = PlsqlPackages.create(Orders.class, dataSource);

BigDecimal freight = orders.calculateFreight("SP", "CE");   // 25.5

PlsqlOut<Long> orderId = new PlsqlOut<>();
orders.placeOrder(42, new BigDecimal("100.00"), orderId);   // orderId.get() is the new order's id

List<OrderRow> rows = orders.ordersOf(42);                   // the cursor, already read into records
```

No `registerOutParameter`, no Oracle type names, no `ResultSet` loop. The mapping comes from the database itself:
Penitent reads each routine's signature from the dictionary once, matches your method to it, parameter by parameter
and type by type, and from then on every call binds and converts its values the same way. With the
[log](docs/logging.md) on, you see what it found and what it ran:

```text
DEBUG Read dictionary     : SHOP.ORDERS_PKG.PLACE_ORDER in 35 ms
DEBUG Calling routine     : SHOP.ORDERS_PKG.PLACE_ORDER <- Orders.placeOrder
DEBUG -> SQL              : {call SHOP.ORDERS_PKG.PLACE_ORDER(?, ?, ?)}
TRACE -> with bind values : P_CUSTOMER_ID=42, P_TOTAL=100.00
TRACE <- OUT values       : P_ORDER_ID=1
DEBUG <- completed        : 3 ms
```

Curious what that same call takes without Penitent?
[See how much boilerplate plain JDBC needs](docs/coming-from-jdbc.md): every type registered by hand, every
parameter in the right position, and every mistake found only when the call runs.

### With Spring Boot

Building a Spring Boot application? Penitent gets even simpler. Add the starter and there is no `create` to call:
every interface in your application's packages becomes a bean, ready to inject, and its calls take part in your
`@Transactional` methods.

```java
@Service
class CheckoutService {

    private final Orders orders;

    CheckoutService(Orders orders) {
        this.orders = orders;
    }

    @Transactional
    long checkout(long customerId, BigDecimal total) {
        PlsqlOut<Long> orderId = new PlsqlOut<>();
        orders.placeOrder(customerId, total, orderId);
        return orderId.get();
    }
}
```

One property, `penitent.validation.enabled: true`, checks every interface against the database at startup. With
Spring but without Boot, `@EnablePlsqlPackages` registers the beans. [Getting started](docs/getting-started.md) walks
through it all step by step.

## Compatibility

Which Oracle JDBC driver works with which Oracle Database, as the build tests them on every change:

| Oracle JDBC driver | Oracle Database 23ai | 21c | 19c |
|---|:---:|:---:|:---:|
| **23.x**: `ojdbc17`, `ojdbc11`, `ojdbc8` | ✅ | ✅ ² | ✅ ² |
| **21.x**: `ojdbc11`, `ojdbc8` | ✅ ¹ | ✅ | ✅ |
| **19.x**: `ojdbc10`, `ojdbc8` | ✅ ¹ | ✅ | ✅ |
| **18.x** and older | ❔ | ❔ | ❔ |

✅ every build runs the whole suite with it · ❔ not measured yet, on the [roadmap](docs/roadmap.md)<br>
¹ except a `BOOLEAN` column in a cursor, which drivers before 23 cannot read: [Driver versions](docs/compatibility.md#driver-versions)
lists each difference from driver 23, how the library works around it, and the way around that one.<br>
² except a `null` `BOOLEAN` that comes back, which driver 23 reads as `true` or `false` before 23ai: Penitent warns, and
[Database versions](docs/compatibility.md#database-versions) shows the ways around it. Before 23ai, `BOOLEAN` exists
only in PL/SQL.

- **Java 17 and 21.** The build runs on both, on Java 17 with `ojdbc17`. `0.22.0` and earlier need Java 21.
- **The driver has a version of its own**, apart from the database's: drivers 21 and 19 talk to Oracle 23ai too.
  The number in the artifact, as in `ojdbc11`, is the Java version the jar is built for, not the driver version;
  `ojdbc17` exists only from driver 23 on, and `ojdbc10` only in driver 19.

You also need an SLF4J implementation to see the log, such as the Logback that Spring Boot ships, and, for
`@PlsqlBlock` and for binding by name without `@PlsqlParam`, to compile with `-parameters`, which the Spring Boot
parent turns on.

## Documentation

- [Calling routines](docs/routines.md): package and standalone routines, `DEFAULT`, overloads, synonyms, `OUT` and
  `IN OUT` parameters, and binding by position or by name.
- [Types](docs/types.md): scalars, booleans, time zones, OBJECT, RECORD, `%ROWTYPE`, collections and cursors.
- [Anonymous blocks](docs/anonymous-blocks.md) with `@PlsqlBlock`, for anything that is not a `package.routine` call.
- [Converters](docs/converters.md) for your own types, and enums stored by code.
- [Transactions and connections](docs/transactions.md): with Spring, with `PlsqlTransactions` in the core alone, or
  on a connection you manage.
- [Errors](docs/errors.md), [Validation](docs/validation.md), [Settings](docs/settings.md),
  [Logging](docs/logging.md) and [Spring and Spring Boot](docs/spring.md).

New to it? [How Penitent works](docs/concepts.md) explains the terms, [Coming from JDBC](docs/coming-from-jdbc.md)
compares it with `SimpleJdbcCall`, and [Troubleshooting](docs/troubleshooting.md) goes through the common errors.
The [Javadoc](https://rponte.github.io/penitent/api/) covers the public API, which is everything outside
`io.github.rponte.penitent.internal`.

## Contributing

```
./mvnw verify    # requires Docker: the tests start their own Oracle through Testcontainers
```

[CONTRIBUTING.md](CONTRIBUTING.md) has the rules, how to run the build with another driver, and how to propose a
change. [CHANGELOG.md](CHANGELOG.md) lists what changed in each version.

## License

[Apache License 2.0](LICENSE).
