# Errors

When a call fails, the first question is whose fault it is. Did the routine reject the request, as business logic
does? Did the database or the network fail? Or does the interface not match the package? Penitent answers with three
exceptions, all unchecked, in `io.github.rponte.penitent.exception`:

| Exception | When | What to do |
|---|---|---|
| `PlsqlRoutineRaisedException` | The routine failed while running: a `RAISE_APPLICATION_ERROR`, a `NO_DATA_FOUND`, a violated constraint | Handle it, by its code |
| `PlsqlExecutionException` | Any other database failure: connecting, a package that does not compile, a deadlock | Usually let it propagate |
| `PlsqlMappingException` | The interface does not match the database, or a value does not convert | Fix the code. The message says what was looked up and what exists |

`PlsqlRoutineRaisedException` extends `PlsqlExecutionException`, so catching the base class catches both. In every
case, the original `SQLException` is the cause.

With [`penitent-spring`](spring.md), every database failure except a routine error arrives as a Spring
`DataAccessException` instead of a `PlsqlExecutionException`.

## Errors raised by the routine

`cancel_order`, from [Getting started](getting-started.md), raises an error for an order that is not open:

<!-- snippet: fixture -->
```sql
RAISE_APPLICATION_ERROR(-20001, 'order ' || p_order_id || ' is not open');
```

<!-- snippet: errors/catch -->
```java
try {
    orders.cancelOrder(orderId);
} catch (PlsqlRoutineRaisedException e) {
    e.getCode();      // 20001
    e.getReason();    // "order 7 is not open"
    e.getMessage();   // "ORA-20001: order 7 is not open\nORA-06512: at \"SHOP.ORDERS_PKG\", line 18\n..."
}
```

- **`getCode()`** is the `ORA-` number **without the sign**: the routine's `-20001` is `20001` here.
- **`getReason()`** is only the error text, ready to show or to log.
- **`getMessage()`** is Oracle's whole message, with the `ORA-` prefix and the PL/SQL stack, which is what you want
  in a log.

Errors that Oracle raises inside the routine count too, since they come from the routine's logic:

<!-- snippet: errors/no-data-found -->
```java
orders.orderStatus(-1);          // SELECT ... INTO finds no row: getCode() is 1403, NO_DATA_FOUND
```

<!-- snippet: errors/primary-key -->
```java
customers.register(1, "Ana");    // the INSERT breaks the primary key: getCode() is 1
```

That includes an error in the query of a cursor the routine returns, which only shows up when the cursor is read.

## Other database failures

What happens before the routine runs, and the errors that say nothing about its logic, arrive as
`PlsqlExecutionException`, which also has `getCode()`:

- connecting, reading the dictionary or preparing the call;
- a call that does not compile (`ORA-06550`) or a package that is invalid (`ORA-04063`);
- concurrency: `ORA-00054` (resource busy), `ORA-00060` (deadlock) and `ORA-08177` (serialization);
- connection, driver and system errors.

The full list of codes is in `PlsqlFailure`. A code outside the list, even one Penitent does not know, is treated as
the routine's error.

## Your own exceptions

To turn an error into an exception of your domain, give Penitent a `PlsqlExceptionTranslator`. It receives a
`PlsqlFailure`, with the statement, the `SQLException` and the point of the call where it failed, and returns the
exception to throw. Delegate everything else to the default:

<!-- snippet: errors/translator -->
```java
PlsqlExceptionTranslator translator = failure -> failure.cause().getErrorCode() == 20001
        ? new OrderNotOpenException(failure.cause())
        : new DefaultPlsqlExceptionTranslator().translate(failure);

Orders orders = PlsqlPackages.create(Orders.class, dataSource,
        PlsqlSettings.defaults().withExceptionTranslator(translator));
```

Now a second `cancelOrder` throws `OrderNotOpenException`, and every other failure arrives as before. To use the
translator in every interface, see [Settings](settings.md).

## See also

- [Validation](validation.md): find every `PlsqlMappingException` up front, instead of one call at a time.
- [Spring and Spring Boot](spring.md): how failures map to `DataAccessException`.
