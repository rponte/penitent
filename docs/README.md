# Penitent

Penitent maps Oracle PL/SQL packages to Java interfaces. You write the interface; Penitent reads the routines'
signatures and types from the database dictionary, builds the calls, and refuses any method that does not match the
database, with a message that says what exists.

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

<!-- snippet: getting-started/create-and-call -->
```java
Orders orders = PlsqlPackages.create(Orders.class, dataSource);

BigDecimal freight = orders.calculateFreight("SP", "CE");   // 25.5
```

It runs on Java 21 with plain JDBC, Spring or Spring Boot, and is on
[Maven Central](https://central.sonatype.com/namespace/io.github.rponte) as `io.github.rponte:penitent-core`. New to
it? Start with [Getting started](getting-started.md): in about ten minutes, it calls a real PL/SQL package from plain
Java and from Spring Boot. Every page uses the same orders package, so the examples build on each other.

## Start here

| Page | What it covers |
|---|---|
| [Getting started](getting-started.md) | From a PL/SQL package to a Java call, in plain Java and in Spring Boot |
| [How Penitent works](concepts.md) | The terms (routine, container, dictionary) and what happens in a call |
| [Coming from JDBC or `SimpleJdbcCall`](coming-from-jdbc.md) | The same call in each style, and what changes |

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
| Javadoc | The `-javadoc.jar` of each module, on Maven Central |

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
