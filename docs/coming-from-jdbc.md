# Coming from JDBC or `SimpleJdbcCall`

If you call PL/SQL from Java today, you probably write a `CallableStatement` by hand, or use Spring's
`SimpleJdbcCall`. This page shows the same call in each style, and what changes when you move to Penitent.

## The same call, three ways

`place_order`, from [Getting started](getting-started.md), takes a customer and a total, and returns the new order's
id through an `OUT` parameter.

**JDBC:**

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

**`SimpleJdbcCall`:**

```java
SimpleJdbcCall placeOrder = new SimpleJdbcCall(jdbcTemplate)
        .withCatalogName("ORDERS_PKG")
        .withProcedureName("PLACE_ORDER");

Map<String, Object> out = placeOrder.execute(new MapSqlParameterSource()
        .addValue("P_CUSTOMER_ID", 42)
        .addValue("P_TOTAL", new BigDecimal("100.00")));
long orderId = ((Number) out.get("P_ORDER_ID")).longValue();
```

**Penitent:**

```java
@PlsqlPackage("ORDERS_PKG")
public interface Orders {
    void placeOrder(long customerId, BigDecimal total, PlsqlOut<Long> orderId);
}

PlsqlOut<Long> orderId = new PlsqlOut<>();
orders.placeOrder(42, new BigDecimal("100.00"), orderId);
```

## What changes

- **The call has a type.** The signature is a Java method, so the compiler checks the arguments, your IDE completes
  them, and a refactoring finds every caller. With a `Map<String, Object>`, a wrong key or a wrong type only fails at
  run time, if at all.
- **The database is checked, not trusted.** The JDBC version works as long as nobody changes the package. Penitent
  reads the routine's signature from the dictionary and refuses a call that does not match it, with a message that
  says what exists. [Validation](validation.md) checks every method in a test, so a changed package fails the
  build.
- **Composites stop being positional.** With JDBC, an OBJECT is a `Struct` built from an `Object[]` in the type's
  attribute order, and a collection needs the driver's own API. With Penitent, an OBJECT, a RECORD or a `%ROWTYPE` is
  a Java `record` matched by attribute name, and a collection is a `List<T>`. See [Types](types.md).
- **No type codes.** There is no `Types.NUMERIC` to register, no `OracleTypes.CURSOR`, no type name such as
  `"POSTAL_ADDRESS_T"` in Java: the dictionary has them.
- **Cursors are lists.** A `SYS_REFCURSOR` becomes a `List<T>` of records, read and closed during the call.
- **Errors are sorted.** An error raised by the routine is a `PlsqlRoutineRaisedException` with its code and text,
  apart from infrastructure failures. See [Errors](errors.md).

## What stays the same

- **Your `DataSource` and your pool.** Penitent takes a connection for each call and gives it back.
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
