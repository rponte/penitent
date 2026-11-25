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

No `CallableStatement`, no `registerOutParameter`, no positions to keep in order. Penitent read the routines'
signatures from the database, and checked that your methods agree with them. Curious what that same call takes
without Penitent? [See how much boilerplate plain JDBC needs](coming-from-jdbc.md).

## What else you get

The same interface style covers the rest of what a PL/SQL API returns. A few of the things it does for you:

### Rows, objects and lists come back as plain Java

A PL/SQL API rarely returns only numbers and texts. A function may give back a whole row of a table, an object with
attributes, a list of values or the rows of a query. Penitent maps each of them to a Java `record` or a `List`,
matched by name, so your code never touches a `ResultSet`, a `Struct` or an `Array`:

| In Oracle | What it is | In Java |
|---|---|---|
| `customers%ROWTYPE` | a row of a table | a `record` |
| OBJECT type | a type of the schema, with attributes | a `record` |
| RECORD | a type of a package, with fields | a `record` |
| nested table, `VARRAY` | many values of one type | a `List` |
| `SYS_REFCURSOR` | the rows of a query | a `List` of `record`s |

The simplest is a row of a table. `customers%ROWTYPE` has one field per column, and a `record` with the same names,
in camelCase, receives it:

<!-- snippet: fixture -->
```sql
FUNCTION find_customer(p_id NUMBER) RETURN customers%ROWTYPE;
```

<!-- snippet: types/rowtype -->
```java
public record Customer(Long id, String name, String taxId, String tier,
                       @PlsqlBoolean(trueValue = "S", falseValue = "N") boolean vip,
                       LocalDate bornOn, LocalDateTime createdAt) {
}

Customer findCustomer(long id);
```

<!-- snippet: types/rowtype-call -->
```java
customers.findCustomer(1);
// Customer[id=1, name=Ana Lima, taxId=12345678901, tier=G, vip=true, bornOn=1990-04-12, createdAt=2025-01-15T09:30]
```

And the rows of a query, which a function opens as a cursor, are a list of `record`s, read whole and closed during
the call:

<!-- snippet: fixture -->
```sql
FUNCTION orders_of(p_customer_id NUMBER) RETURN SYS_REFCURSOR;
-- OPEN l_cursor FOR SELECT id, total, status FROM orders WHERE customer_id = p_customer_id ORDER BY id;
```

<!-- snippet: types/cursor -->
```java
public enum OrderStatus { OPEN, CANCELLED }

public record OrderRow(Long id, BigDecimal total, OrderStatus status) {
}

List<OrderRow> ordersOf(long customerId);
```

<!-- snippet: types/cursor-call -->
```java
customers.ordersOf(201);
// [OrderRow[id=1, total=100, status=OPEN], OrderRow[id=2, total=40, status=CANCELLED]]
```

An OBJECT type, a RECORD and a collection work the same way, one step at a time in
[Getting started](getting-started.md#7-records-and-lists-not-resultsets).

### A routine's error is an exception you can catch

<!-- snippet: getting-started/routine-error -->
```java
orders.cancelOrder(orderId.get());

try {
    orders.cancelOrder(orderId.get());
} catch (PlsqlRoutineRaisedException e) {
    e.getCode();     // 20001, without the sign
    e.getReason();   // "order 1 is not open"
}
```

A `RAISE_APPLICATION_ERROR` keeps its code and its text, apart from infrastructure failures: see [Errors](errors.md).

### With Spring Boot, just inject it

Add `penitent-spring-boot-starter`, and every interface becomes a bean that takes part in your `@Transactional`
methods:

<!-- snippet: untested -->
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

See [Spring and Spring Boot](spring.md).

### Stub a routine in a test, as with Mockito

With `penitent-test`, a test makes a routine answer what it expects, from the same interface the code calls. The stub
is real PL/SQL, compiled only for the test, so it also answers other PL/SQL that calls the routine:

<!-- snippet: testing/spring -->
```java
@SpringBootTest
@EnablePlsqlMocks
class CheckoutServiceTest {

    @Autowired PlsqlMocks mocks;
    @Autowired CheckoutService checkout;

    @Test
    void adds_the_freight_to_the_subtotal() {
        // arrange
        Freights freights = mocks.mock(Freights.class);
        when(freights.calculate("SP", "CE")).thenReturn(new BigDecimal("24.90"));

        // act
        BigDecimal total = checkout.total(new BigDecimal("100.00"), "SP", "CE");

        // assert
        assertThat(total).isEqualByComparingTo("124.90");
    }
}
```

See [Testing](testing.md).

## Why Penitent

- **Type-safe.** Each routine is a Java method: the compiler checks every argument, your IDE completes them, and a
  refactoring finds every caller.
- **Checked against the database.** Penitent matches each method to its routine, parameter by parameter and type by
  type, and [Validation](validation.md) checks every interface at startup, so a package that changed is noticed
  before the first request.
- **Rich types, no ceremony.** A row of a table, an object or a package's record is a Java `record`; a list of values
  and the rows of a query are a `List`; all matched by name. Dates cross without the JVM's time zone getting in the
  way. See [Types](types.md).
- **Plain Java or Spring.** The core needs no framework. With Spring, calls join your transactions; with Spring Boot,
  every interface is a bean, ready to inject. See [Spring and Spring Boot](spring.md).
- **Every call in the log.** The SQL Penitent built, the values it bound and the `OUT` values it read, by parameter
  name. See [Logging](logging.md).
- **Tested against a real Oracle.** Every feature in the [compatibility table](compatibility.md) runs in the build,
  against a real database, and so do the Java and SQL examples on these pages.

## What Penitent does not do

Penitent does one thing: call PL/SQL from Java. What it leaves to others, on purpose:

- **Plain SQL queries.** It calls routines and [anonymous blocks](anonymous-blocks.md), not tables: keep your
  `JdbcTemplate`, jOOQ or ORM for queries. They can share the same `DataSource` and transaction.
- **Connections.** It takes a connection from your `DataSource` for each call and gives it back: the pool is yours,
  such as HikariCP.
- **Transactions.** It never commits or rolls back on its own: the transaction is yours, Spring's, or a minimal
  [`PlsqlTransactions`](transactions.md) block, not a transaction manager.
- **Retries.** A call that failed is never repeated: only your application knows whether its work can run twice.
- **Guesses.** A conversion that would lose information, such as a `NULL` turned into `0` or a time zone dropped, is
  refused with a message, not done quietly.

The reasons for each are in the [roadmap](roadmap.md#by-design).

## Install

Penitent is on [Maven Central](https://central.sonatype.com/namespace/io.github.rponte). Pick the module that fits
your application, `penitent-core` for plain Java, `penitent-spring` for Spring, or `penitent-spring-boot-starter` for
Spring Boot, and add the Oracle driver your application uses:

<!-- snippet: untested -->
```xml
<dependency>
    <groupId>io.github.rponte</groupId>
    <artifactId>penitent-core</artifactId>
    <version>0.32.0</version>
</dependency>
<dependency>
    <groupId>com.oracle.database.jdbc</groupId>
    <artifactId>ojdbc17</artifactId>
    <version>23.26.3.0.0</version>
</dependency>
```

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
| get a row of a table, an object or a record as a Java `record` (`%ROWTYPE`, OBJECT, RECORD) | [Types](types.md#composite-types) |
| send or receive a list of values (a nested table or a `VARRAY`) | [Types](types.md#collections) |
| read the rows of a query (a `SYS_REFCURSOR`) into a list | [Types](types.md#cursors) |
| send or receive a JSON document, as text or as a `Map` | [Types](types.md#json) |
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
| change PL/SQL while the application runs, or understand `ORA-04068` | [Changing PL/SQL while the application runs](deploying.md) |
| use Spring transactions, or inject interfaces as beans | [Spring and Spring Boot](spring.md) |
| test code that calls PL/SQL, or stub a routine as with Mockito | [Testing](testing.md) |
| replace a view or a package in a test, with a script | [Testing](testing.md#replacing-a-view-or-a-package) |
| see the SQL and the values of each call | [Logging](logging.md) |
| understand an error message | [Troubleshooting](troubleshooting.md) |

## Runs on

Java 17 and 21, and these combinations of Oracle JDBC driver and database, tested on every change:

| Oracle JDBC driver | Oracle Database 23ai | 21c | 19c |
|---|:---:|:---:|:---:|
| **23.x**: `ojdbc17`, `ojdbc11`, `ojdbc8` | ✅ | ✅ ² | ✅ ² |
| **21.x**: `ojdbc11`, `ojdbc8` | ✅ ¹ | ✅ | ✅ |
| **19.x**: `ojdbc10`, `ojdbc8` | ✅ ¹ | ✅ | ✅ |
| **18.x** and older | ❔ | ❔ | ❔ |

✅ every build runs the whole suite with it · ❔ not measured yet, on the [roadmap](roadmap.md)

<div class="admonition note" markdown>
<p class="admonition-title">Two differences, both about BOOLEAN</p>

¹ Drivers before 23 cannot read a `BOOLEAN` column in a cursor: see [Driver versions](compatibility.md#driver-versions).
² Driver 23 reads a `null` `BOOLEAN` that comes back as `true` or `false` before 23ai: Penitent warns, and
[Database versions](compatibility.md#database-versions) shows the ways around it.

</div>

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
