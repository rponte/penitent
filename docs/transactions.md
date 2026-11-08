# Transactions and connections

Penitent does one thing with connections: for each call, it takes a connection from the `DataSource`, runs the call
on it and closes it, or, for a view made with `PlsqlPackages.using`, runs it on the connection you gave it. It never
commits, never rolls back and never changes auto-commit. So whether two calls are one transaction, or two, depends
only on the connection they run on, and the transaction belongs to whoever owns it.

This page shows the three setups you are likely to have, and what happens in each one.

| Setup | Two calls are… | Who commits |
|---|---|---|
| [Spring, inside `@Transactional`](#with-spring) | one transaction, with your `JdbcTemplate` work | Spring |
| [A pool in auto-commit](#a-pool-in-auto-commit) | two transactions | each call, as it ends |
| [A transaction you manage, with `PlsqlPackages.using`](#a-transaction-you-manage) | one transaction, with your plain JDBC work | you |

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

To put several calls, and your own JDBC work, in one transaction without Spring, bind the interface to your
connection with `PlsqlPackages.using`, and commit or roll back yourself. Create the interface once, as usual:

<!-- snippet: transactions/create -->
```java
Orders orders = PlsqlPackages.create(Orders.class, dataSource);   // once, as usual
```

Then, for each transaction, take a view of it bound to the transaction's connection:

<!-- snippet: transactions/transaction -->
```java
try (Connection connection = dataSource.getConnection()) {
    connection.setAutoCommit(false);
    try {
        Orders inTransaction = PlsqlPackages.using(orders, connection);

        PlsqlOut<Long> orderId = new PlsqlOut<>();
        inTransaction.placeOrder(602, new BigDecimal("100.00"), orderId);
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

The view implements the same interface, and:

- **runs every call on your connection,** reading the dictionary on it too when it needs to, and **never closes,
  commits or rolls it back, nor changes its auto-commit.** The connection and its transaction stay yours;
- **shares what the interface read from the dictionary,** and its settings and exception translator: creating a view
  reads nothing, and so does calling a method the interface already called;
- **is cheap, and meant to live as long as the transaction.** One view per transaction, even in a loop, reads the
  dictionary no more than the interface alone would, and a view nobody references is collected;
- **follows the rules of the connection:** do not use one view from two threads at once. The interface it came from
  is still safe to share.

Inside the transaction, calls and plain JDBC see each other's work, and no other session sees any of it until the
commit; `OUT` parameters and cursors read what the transaction has not committed yet; and every call runs in the
connection's session, so session state carries from one call to the next.

The same works with any transaction manager of your own that gives you its connection. With Spring, there is no need
for it: `penitent-spring` already takes part in `@Transactional`. In a Jakarta EE container, the `DataSource` it
provides hands out the JTA transaction's connection by itself, so `create` with that `DataSource` should take part
in the transaction without a view; this is not measured yet.

## When a call fails inside a transaction

Oracle undoes the work of the call that failed, and only that: what the transaction did before it stays, still
uncommitted. Penitent does not roll anything back, so what happens next is your decision:

<!-- snippet: transactions/failure-in-the-middle -->
```java
inTransaction.placeOrder(604, new BigDecimal("100.00"), orderId);
try {
    inTransaction.cancelOrder(-1);
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
