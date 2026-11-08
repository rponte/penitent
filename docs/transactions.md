# Transactions and connections

Penitent does one thing with connections: for each call, it takes a connection from the `DataSource`, runs the call
on it and closes it. It never commits, never rolls back and never changes auto-commit. So whether two calls are one
transaction, or two, depends only on the `DataSource` you give it.

This page shows the three setups you are likely to have, and what happens in each one.

| Setup | Two calls are… | Who commits |
|---|---|---|
| [Spring, inside `@Transactional`](#with-spring) | one transaction, with your `JdbcTemplate` work | Spring |
| [A pool in auto-commit](#a-pool-in-auto-commit) | two transactions | each call, as it ends |
| [A connection you manage](#a-transaction-you-manage) | one transaction, with your plain JDBC work | you |

## With Spring

With [`penitent-spring`](spring.md), a call inside a `@Transactional` method uses the transaction's connection:

<!-- snippet: untested -->
```java
@Transactional
public long checkout(long customerId, BigDecimal total) {
    PlsqlOut<Long> orderId = new PlsqlOut<>();
    orders.placeOrder(customerId, total, orderId);                                 // a routine
    jdbc.update("update orders set total = total * 0.9 where id = ?", orderId.get());   // plain SQL
    return orderId.get();
}
```

- **Both are committed together,** when the method returns.
- **An exception that leaves the method rolls both back,** a `PlsqlRoutineRaisedException` included.
- **A call outside a transaction commits on its own,** as with a `JdbcTemplate`.

The `penitent-spring` tests cover each of these, and the next sections explain what happens underneath.

## A pool in auto-commit

Without a transaction manager, the usual pool hands out connections in auto-commit. Each call is then a transaction
of its own:

<!-- snippet: transactions/auto-commit -->
```java
PlsqlOut<Long> orderId = new PlsqlOut<>();
orders.placeOrder(601, new BigDecimal("100.00"), orderId);   // committed when the call ends
orders.cancelOrder(-1);                                      // fails, and the order above stays
```

Every call gives its connection back to the pool, whether it succeeds, raises an error, or fails a mapping check
before reaching the database. Two calls in a row may also run in different sessions, so session state, such as a
package variable or `DBMS_APPLICATION_INFO`, does not carry from one call to the next.

> **A pool that is not in auto-commit loses your work.** If the pool hands out connections with auto-commit off, and
> nothing commits, a call succeeds without any error, and then the pool rolls the work back when it takes the
> connection back. Use auto-commit, a transaction manager such as Spring's, or the setup below.

## A transaction you manage

To put several calls, and your own JDBC work, in one transaction without Spring, give Penitent a `DataSource` that
always hands out your connection, and commit or roll back yourself:

<!-- snippet: transactions/transaction -->
```java
try (Connection connection = dataSource.getConnection()) {
    connection.setAutoCommit(false);
    try {
        Orders orders = PlsqlPackages.create(Orders.class, SingleConnection.of(connection));

        PlsqlOut<Long> orderId = new PlsqlOut<>();
        orders.placeOrder(602, new BigDecimal("100.00"), orderId);
        try (PreparedStatement discount = connection.prepareStatement(
                "update orders set total = total * 0.9 where id = ?")) {
            discount.setLong(1, orderId.get());
            discount.executeUpdate();
        }

        connection.commit();
    } catch (RuntimeException | SQLException e) {
        connection.rollback();
        throw e;
    } finally {
        connection.setAutoCommit(true);
    }
}
```

Penitent has no such `DataSource` yet, so here is one. It hands out the same connection on every call, and ignores
the `close` that Penitent sends at the end of each call:

<!-- snippet: transactions/single-connection -->
```java
/** Hands out the same connection on every call, and leaves closing it to the application. */
public final class SingleConnection {

    public static DataSource of(Connection connection) {
        Connection kept = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] { Connection.class }, (proxy, method, args) -> {
                    if (method.getName().equals("close")) {
                        return null;   // the application closes it, after the commit or the rollback
                    }
                    try {
                        return method.invoke(connection, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                new Class<?>[] { DataSource.class }, (proxy, method, args) -> {
                    if (method.getName().equals("getConnection")) {
                        return kept;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
```

Inside the transaction:

- **Calls and plain JDBC see each other's work,** and no other session sees any of it until the commit.
- **`OUT` parameters and cursors** read what the transaction has not committed yet.
- **Every call runs in the connection's session,** so session state carries from one call to the next.

> **The cost of this setup:** an interface created with `create` keeps what it read from the dictionary, and that
> cache belongs to the instance. An interface created for each transaction reads the dictionary again, on the first
> call of each method: about 5 queries for a routine with scalars. For a few transactions that is fine; in a hot path,
> prefer Spring, or follow the [roadmap](roadmap.md#transactions-without-spring).

## When a call fails inside a transaction

Oracle undoes the work of the call that failed, and only that: what the transaction did before it stays, still
uncommitted. Penitent does not roll anything back, so what happens next is your decision:

<!-- snippet: transactions/failure-in-the-middle -->
```java
orders.placeOrder(604, new BigDecimal("100.00"), orderId);
try {
    orders.cancelOrder(-1);
} catch (PlsqlRoutineRaisedException e) {
    // only cancel_order was undone: the order placed above is still in the transaction
}
connection.commit();   // keeps the order
```

- **Let the exception propagate,** and your `catch` rolls everything back, as in the example of the previous
  section. With Spring, an exception that leaves the `@Transactional` method does the same.
- **Catch it and go on,** and the commit keeps everything except the failed call. With Spring, this works the same
  inside a `@Transactional` method.

## When the routine commits or rolls back by itself

Much PL/SQL ends its own transaction with a `COMMIT` or a `ROLLBACK`. In Oracle, that ends the session's
transaction, not a part of it: everything the transaction did before the call is committed or undone along with the
routine's own work. Penitent does nothing about it, since it never touches the transaction, but your transaction
manager does not know either:

| The routine does | With a pool in auto-commit | In a transaction you or Spring manage |
|---|---|---|
| `COMMIT` | nothing changes | **everything before the call is committed**, and a later rollback comes too late |
| `ROLLBACK` | the call's own work is undone, without an error | **everything before the call is undone**, without an error, and the transaction goes on with what comes after |
| `ROLLBACK`, inside a Spring nested transaction (`PROPAGATION_NESTED`) | – | the savepoint is erased too: if the nested work then fails, Spring throws `TransactionSystemException: Could not roll back to JDBC savepoint`, caused by `ORA-01086`, instead of the original failure |
| `COMMIT` in a `PRAGMA AUTONOMOUS_TRANSACTION`, as audit and log routines do | nothing changes | only the routine's own work is committed: your transaction is untouched, and a rollback does not undo the log |

Committing or rolling back again afterwards is not an error, in a transaction you manage or in Spring's: there is
just nothing left to commit, or to undo. In auto-commit, the driver refuses both, with `ORA-17273` and `ORA-17274`.

A cursor the routine opens before its `COMMIT` is still read whole.

If a routine you call ends the transaction, keep it out of a larger transaction, call it last, or ask whether it can
become autonomous.

## See also

- [Spring and Spring Boot](spring.md): transactions and exceptions with `penitent-spring`.
- [Errors](errors.md): which exception a failed call raises.
- [How Penitent works](concepts.md#what-happens-in-a-call): each step of a call.
