# Penitent

**Type-safe calls to Oracle PL/SQL from Java interfaces, checked against the database's own signatures.**

You describe a package as a Java interface, and Penitent builds every call from the routines' signatures in the
database dictionary: the compiler checks your calls, the database checks your interface, and Oracle type names and
parameter order are never written by hand.

## A package, an interface, a call

Take a PL/SQL package:

<!-- snippet: fixture -->
```sql
CREATE OR REPLACE PACKAGE orders_pkg AS
    FUNCTION  calculate_freight(p_origin VARCHAR2, p_destination VARCHAR2) RETURN NUMBER;
    PROCEDURE place_order(p_customer_id NUMBER, p_total NUMBER, p_order_id OUT NUMBER);
    PROCEDURE cancel_order(p_order_id NUMBER);
    FUNCTION  order_status(p_order_id NUMBER) RETURN VARCHAR2;
END orders_pkg;
```

Describe it as a Java interface, with plain Java types:

<!-- snippet: getting-started/interface -->
```java
@PlsqlPackage("ORDERS_PKG")
public interface Orders {

    BigDecimal calculateFreight(String origin, String destination);

    void placeOrder(long customerId, BigDecimal total, PlsqlOut<Long> orderId);

    void cancelOrder(long orderId);

    String orderStatus(long orderId);
}
```

And call it like any other Java object:

<!-- snippet: getting-started/create-and-call -->
```java
Orders orders = PlsqlPackages.create(Orders.class, dataSource);

BigDecimal freight = orders.calculateFreight("SP", "CE");   // 25.5
```

<!-- snippet: getting-started/out-parameter -->
```java
PlsqlOut<Long> orderId = new PlsqlOut<>();
orders.placeOrder(42, new BigDecimal("100.00"), orderId);

orders.orderStatus(orderId.get());   // "OPEN"
```

No `CallableStatement`, no `registerOutParameter`, no positions to keep in order. Curious what that same call takes
without Penitent? [See how much boilerplate plain JDBC needs](coming-from-jdbc.md).

## Why Penitent

- **Type-safe.** Each routine is a Java method: the compiler checks every argument, your IDE completes them, and a
  refactoring finds every caller.
- **Checked against the database.** Penitent matches each method to its routine, parameter by parameter and type by
  type, and [Validation](validation.md) checks every interface at startup, so a package that changed is noticed
  before the first request.
- **Rich types, no ceremony.** An OBJECT, a RECORD or a `%ROWTYPE` is a Java `record`, a collection is a `List<T>`,
  and a cursor is a `List<T>` of records, all matched by name. Dates cross without the JVM's time zone getting in the
  way. See [Types](types.md).
- **Plain Java or Spring.** The core needs no framework. With Spring, calls join your transactions; with Spring Boot,
  every interface is a bean, ready to inject. See [Spring and Spring Boot](spring.md).
- **Every call in the log.** The SQL Penitent built, the values it bound and the `OUT` values it read, by parameter
  name. See [Logging](logging.md).
- **Tested against a real Oracle.** Every feature in the [compatibility table](compatibility.md) runs in the build,
  against a real database, and so do the Java and SQL examples on these pages.

## Install

Penitent is on [Maven Central](https://central.sonatype.com/namespace/io.github.rponte). Pick the module that fits
your application, `penitent-core` for plain Java, `penitent-spring` for Spring, or `penitent-spring-boot-starter` for
Spring Boot, and add the Oracle driver your application uses:

<!-- snippet: untested -->
```xml
<dependency>
    <groupId>io.github.rponte</groupId>
    <artifactId>penitent-core</artifactId>
    <version>0.24.0</version>
</dependency>
<dependency>
    <groupId>com.oracle.database.jdbc</groupId>
    <artifactId>ojdbc17</artifactId>
    <version>23.26.3.0.0</version>
</dependency>
```

## Runs on

Java 17 and 21, and these combinations of Oracle JDBC driver and database, tested on every change:

| Oracle JDBC driver | Oracle Database 23ai | 21c | 19c |
|---|:---:|:---:|:---:|
| **23.x**: `ojdbc17`, `ojdbc11`, `ojdbc8` | ✅ | ✅ ² | ❔ |
| **21.x**: `ojdbc11`, `ojdbc8` | ✅ ¹ | ✅ | ❔ |
| **19.x**: `ojdbc10`, `ojdbc8` | ✅ ¹ | ✅ | ❔ |
| **18.x** and older | ❔ | ❔ | ❔ |

✅ every build runs the whole suite with it · ❔ not measured yet, on the [roadmap](roadmap.md)<br>
¹ except a `BOOLEAN` column in a cursor, which drivers before 23 cannot read: see
[Driver versions](compatibility.md#driver-versions).<br>
² except a `null` `BOOLEAN` that comes back, which driver 23 reads as `true` or `false` before 23ai: Penitent warns, and
[Database versions](compatibility.md#database-versions) shows the ways around it.

## Start here

| Page | What it covers |
|---|---|
| [Getting started](getting-started.md) | From a PL/SQL package to a Java call, in plain Java and in Spring Boot, in about ten minutes |
| [How Penitent works](concepts.md) | The terms (routine, container, dictionary) and what happens in a call |
| [Coming from JDBC or Spring's `SimpleJdbcCall`](coming-from-jdbc.md) | The same call in each style, and what each one leaves to you |

Every page uses the same orders package, so the examples build on each other.

## I want to…

| Goal | Where |
|---|---|
| call a function or a procedure | [Calling routines](routines.md#functions-and-procedures) |
| leave out a `DEFAULT` parameter | [Calling routines](routines.md#default-values) |
| read an `OUT` or `IN OUT` parameter | [Calling routines](routines.md#out-and-in-out-parameters) |
| bind parameters by name | [Calling routines](routines.md#binding-by-name) |
| call a routine outside a package | [Calling routines](routines.md#routines-outside-a-package) |
| map an OBJECT, a RECORD or a `%ROWTYPE` | [Types](types.md#composite-types) |
| send or receive a collection | [Types](types.md#collections) |
| read a cursor into a list | [Types](types.md#cursors) |
| use booleans stored as `S`/`N` | [Types](types.md#booleans) |
| handle dates and time zones | [Types](types.md#time-zones) |
| call an OBJECT member method, or run a block | [Anonymous blocks](anonymous-blocks.md) |
| use my own types, or enums stored by code | [Converters](converters.md) |
| handle an error raised by the routine | [Errors](errors.md#errors-raised-by-the-routine) |
| turn errors into my own exceptions | [Errors](errors.md#your-own-exceptions) |
| run several calls, and my own SQL, in one transaction | [Transactions and connections](transactions.md) |
| run a transaction without Spring | [`PlsqlTransactions`](transactions.md#without-spring-plsqltransactions) |
| configure every interface at once | [Settings](settings.md) |
| check every interface against the database | [Validation](validation.md) |
| use Spring transactions, or inject interfaces as beans | [Spring and Spring Boot](spring.md) |
| see the SQL and the values of each call | [Logging](logging.md) |
| understand an error message | [Troubleshooting](troubleshooting.md) |

## Reference

| Page | What it covers |
|---|---|
| [Compatibility](compatibility.md) | Every Oracle and Java type pair and behavior, and whether a test covers it |
| [Roadmap](roadmap.md) | What comes next, what waits for a use case, and what is left out by design |
| [Javadoc](https://rponte.github.io/penitent/api/) | The public API of the three modules, on the site; also the `-javadoc.jar` of each module, on Maven Central |

## The examples are tested

Every block on these pages comes from somewhere the build checks, so a page cannot drift from the library without
failing it:

- **Java code** is a region of a test in `penitent-core`, one of the `Docs*Test` classes, which compiles and runs it.
  `DocsPagesTest` fails when the page and the region differ.
- **Error messages and log output** are read from the page by the test that produces them, and compared with what
  the library printed.
- **SQL** is made of lines of the test fixtures, from `V15__documentation_examples.sql` on.
- **Configuration and comparisons,** such as a Maven dependency, Spring configuration or a `SimpleJdbcCall`, are
  marked as untested on the page, so the exception is visible in review.
