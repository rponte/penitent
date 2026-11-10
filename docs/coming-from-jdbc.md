# Coming from JDBC or Spring's `SimpleJdbcCall`

If you call PL/SQL from Java today, you probably write a `CallableStatement` by hand, or use Spring's
`SimpleJdbcCall`. Both work, and both leave a lot to you, often more than it seems. This page writes the same call in
each style, and shows what each one asks you to get right.

## The same call, three ways

The call is to `place_order`, in the `ORDERS_PKG` package. It takes a customer and a total, inserts the order, and
returns its new id through an `OUT` parameter:

<!-- snippet: fixture -->
```sql
CREATE OR REPLACE PACKAGE orders_pkg AS
    PROCEDURE place_order(p_customer_id NUMBER, p_total NUMBER, p_order_id OUT NUMBER);
END orders_pkg;
```

### Plain JDBC

<!-- snippet: jdbc/callable-statement -->
```java
try (Connection connection = dataSource.getConnection();
     CallableStatement call = connection.prepareCall("{call ORDERS_PKG.PLACE_ORDER(?, ?, ?)}")) {
    call.setLong(1, 42);
    call.setBigDecimal(2, new BigDecimal("100.00"));
    call.registerOutParameter(3, Types.NUMERIC);
    call.execute();
    long orderId = call.getLong(3);
}
```

Eight lines for one call, and everything in them is yours to get right:

- **Every position.** `setLong(1, …)` and `setBigDecimal(2, …)` must follow the routine's parameter order. Swap the
  customer and the total, both `NUMBER`, and the call still runs: it places an order for customer 100 with a total
  of 42, without any error.
- **Every type code.** Each `OUT` needs `registerOutParameter` with the right code: `Types.NUMERIC` here,
  `OracleTypes.CURSOR` for a cursor, and the type's name, in a string, for an OBJECT.
- **Every name, in a string.** `"ORDERS_PKG.PLACE_ORDER"` is text the compiler cannot check. A typo, or a package that
  changed, shows up only when that line runs, in whatever environment runs it first.
- **Every composite and cursor.** An OBJECT is a `Struct` built from an `Object[]` in the type's attribute order, a
  collection needs the driver's own API, and a cursor is a `ResultSet` you loop over, convert and close.
- **Every date.** A `Timestamp` holds an instant in the JVM's time zone: in a zone with daylight saving time, 02:30 on
  the day the clocks jump turns into 03:30, without any error.
- **Every driver version.** Some of this changes with the driver: before driver 23, `Types.BOOLEAN` is refused, and
  the driver's own date types convert `java.time` values through the JVM's time zone. See
  [Driver versions](compatibility.md#driver-versions).

### Spring's `SimpleJdbcCall`

<!-- snippet: untested -->
```java
SimpleJdbcCall placeOrder = new SimpleJdbcCall(jdbcTemplate)
        .withCatalogName("ORDERS_PKG")
        .withProcedureName("PLACE_ORDER");

Map<String, Object> out = placeOrder.execute(new MapSqlParameterSource()
        .addValue("P_CUSTOMER_ID", 42)
        .addValue("P_TOTAL", new BigDecimal("100.00")));

long orderId = ((Number) out.get("P_ORDER_ID")).longValue();
```

A real step up: it reads the parameters from the database's metadata, so names replace positions and simple types
need no codes, and it joins Spring's transactions and exceptions. What it still leaves to you:

- **Untyped values, both ways.** The input is a `Map` of strings, and so is the output. A misspelled key or a value
  of the wrong type compiles fine, and the result is an `Object` to cast: `out.get("P_ORDERID")` is just `null`.
- **No help from your tools.** Your IDE cannot complete a parameter name, and a refactoring cannot find the callers
  of a routine: they are all strings.
- **Composites and cursors, still by hand.** A cursor has to be declared with a `RowMapper`, through
  `returningResultSet`, and an OBJECT or a collection needs your own conversion code.
- **Checked only when it runs.** The metadata is read on the first call, so a package that changed is noticed by
  whichever request happens to call it first.

### Penitent

<!-- snippet: jdbc/penitent-interface -->
```java
@PlsqlPackage("ORDERS_PKG")
public interface Orders {
    void placeOrder(long customerId, BigDecimal total, PlsqlOut<Long> orderId);
}
```

<!-- snippet: jdbc/penitent-call -->
```java
PlsqlOut<Long> orderId = new PlsqlOut<>();
orders.placeOrder(42, new BigDecimal("100.00"), orderId);
long id = orderId.get();
```

One method, and a call that reads like any other Java call. What you get:

- **A typed method.** The compiler checks every argument, your IDE completes them, a refactoring finds every caller,
  and the result is a `Long`, not an `Object` to cast.
- **The database checks your interface.** Penitent reads the routine's signature from the dictionary and matches
  your method to it: the routine, the number of parameters, their types and directions, and the composite types.
  [Validation](validation.md) does it for every interface at startup, or in a test, so a changed package is caught
  before it reaches a request.
- **Composites by name, cursors as lists.** An OBJECT, a RECORD or a `%ROWTYPE` is a Java `record` matched by
  attribute name, a collection is a `List<T>`, and a `SYS_REFCURSOR` is a `List<T>` of records, read and closed for
  you. See [Types](types.md).
- **No type codes, no type names.** There is no `Types.NUMERIC` to register and no `"POSTAL_ADDRESS_T"` in your
  code: the dictionary has them.
- **Dates and drivers, handled.** `LocalDateTime` and `LocalDate` cross without the JVM's time zone, and `BOOLEAN` and
  `%ROWTYPE` bind the same way on every driver version the build tests.
- **Every call in the log.** The SQL Penitent built, the values it bound and the `OUT` values it read, by parameter
  name. See [Logging](logging.md).

## What stays the same

- **Your `DataSource` and your pool.** Penitent takes a connection for each call and gives it back, and never
  commits: the transaction is yours, or Spring's. See [Transactions and connections](transactions.md).
- **Spring transactions and exceptions.** With [`penitent-spring`](spring.md), a call takes part in the current
  transaction, and infrastructure failures are the same `DataAccessException` classes a `JdbcTemplate` throws.
- **The SQL Oracle sees.** The call is a plain `{call PACKAGE.ROUTINE(?, ?)}`, which you can see in the
  [log](logging.md).

## Moving over

You do not need to move everything at once: Penitent and `JdbcTemplate` can share the same `DataSource` and, with
`penitent-spring`, the same transaction. A practical order:

1. Write the interface for one package, and run [`PlsqlPackages.validate`](validation.md) on it in a test.
2. Replace the calls of that package, one at a time.
3. Keep the validation test: it now guards the package against changes.

Penitent only calls PL/SQL: routines and [anonymous blocks](anonymous-blocks.md). For plain SQL queries, keep your
`JdbcTemplate` or your ORM.
