# Errors

The library throws three exceptions, all unchecked, in `io.github.rponte.penitent.exception`:

| Exception | When |
|---|---|
| `PlsqlMappingException` | The interface and the database do not match, or a value does not convert. The message says what was looked up and what exists. |
| `PlsqlRoutineRaisedException` | The routine failed while running. |
| `PlsqlExecutionException` | Any other database failure. It is the base class of `PlsqlRoutineRaisedException`. |

With [`penitent-spring`](spring.md), every database failure except a routine error arrives as a Spring
`DataAccessException` instead.

## Routine errors

An error while the routine runs arrives as `PlsqlRoutineRaisedException`: a `RAISE_APPLICATION_ERROR` as well as a
`NO_DATA_FOUND` or a violated constraint. This also applies to an error in the query of a cursor returned by the
routine, which only shows up when it is read.

```java
try {
    orders.process(order);
} catch (PlsqlRoutineRaisedException e) {
    if (e.getCode() == 20200) { ... } // RAISE_APPLICATION_ERROR(-20200, ...)
    if (e.getCode() == 1403) { ... }  // NO_DATA_FOUND
}
```

`getCode()` is the `ORA-` number, **without the sign**: the routine's `-20200` is `20200` here. `getReason()` is only
the error text, and `getMessage()` is Oracle's message, with the `ORA-` prefix and the stack.

## Other database failures

Other database failures arrive as `PlsqlExecutionException`, the base class, which also has `getCode()`. They are
whatever happens before execution (connecting, reading the dictionary or preparing the call) and the codes that say
nothing about the routine's logic, listed in `PlsqlFailure`: a call that does not compile (`ORA-06550`), an invalid
package (`ORA-04063`), concurrency (`ORA-00054`, `ORA-00060`, `ORA-08177`), connection, driver and system errors. A
code outside the list, even an unknown one, is a routine error.

In every case, the `SQLException` is kept as the cause. To decide differently, pass a `PlsqlExceptionTranslator` to
`PlsqlPackages.create`: it receives a `PlsqlFailure`, with the statement, the cause and the point where the failure
happened.
