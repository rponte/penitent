# Transactions and connections

Penitent does one thing with connections: for each call, it takes a connection from the `DataSource`, runs the call
on it and closes it, or, for a view made with `PlsqlPackages.using`, runs it on the connection you gave it. It never
commits, never rolls back and never changes auto-commit. So whether two calls are one transaction, or two, depends
only on the connection they run on, and the transaction belongs to whoever owns it.

This page shows the four setups you are likely to have, when each one fits, and what happens in it.

## Choosing a setup

Answer these in order, and stop at the first yes:

1. **Does the application use Spring?** Use [Spring's transactions](#with-spring), with `penitent-spring`. Spring
   already manages the connection and the transaction for everything else the application does; a second mechanism
   beside it would split the work into two transactions.
2. **Does the application run in a Jakarta EE container, with JTA?** Use the container's `DataSource` with
   `PlsqlPackages.create`, and its transactions. The container hands out the transaction's connection by itself. This
   is not measured yet.
3. **Do several calls, or calls and your own JDBC, have to succeed or fail together?** Use
   [`PlsqlTransactions`](#without-spring-plsqltransactions).
4. **Is the connection, or the transaction, already in your hands,** from your own transaction manager, or from
   legacy code that receives a `Connection`? Use [`PlsqlPackages.using`](#a-transaction-you-manage) on it.
5. **Otherwise, each call stands alone:** a [pool in auto-commit](#a-pool-in-auto-commit) is all you need.

| Setup | Use it when | Two calls are… | Who commits |
|---|---|---|---|
| [Spring, inside `@Transactional`](#with-spring) | the application uses Spring | one transaction, with your `JdbcTemplate` work | Spring |
| [`PlsqlTransactions`](#without-spring-plsqltransactions) | the core alone, and the work must be atomic | one transaction, with your plain JDBC work | the block, as it ends |
| [`PlsqlPackages.using`](#a-transaction-you-manage) | you already own the connection and its transaction | one transaction, with your plain JDBC work | you |
| [A pool in auto-commit](#a-pool-in-auto-commit) | each call is a unit of work on its own | two transactions | each call, as it ends |

## With Spring

**Use it when** the application uses Spring, even if only part of it calls PL/SQL. Spring then decides the
boundaries of every transaction, for routines and `JdbcTemplate` alike, and Penitent only takes part.

**One application, one transaction model.** In a Spring application, Spring owns the transactions. A
`PlsqlTransactions` block mixed with them would take a connection of its own and commit on its own, so the work would
quietly split in two. With `penitent-spring` on the classpath, that can't happen: each overlap
[fails with a clear message](#plsqltransactions-in-a-spring-application) before anything runs. To move from blocks to
Spring's transactions, [the steps are mechanical](#moving-from-plsqltransactions-to-spring).

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

### `PlsqlTransactions` in a Spring application

Spring's transactions are the way to go, and nothing forces a rewrite: a block that runs on its own, outside any
Spring transaction, keeps working, side by side with them. What `penitent-spring` refuses is the overlap, because
there the work would split into two transactions that commit apart, without a word:

| What | When it is refused |
|---|---|
| A `PlsqlTransactions` block inside a Spring transaction, such as in a `@Transactional` method | as the block starts, before it takes a connection |
| A Spring transaction that begins inside a block, such as a call to a `@Transactional` bean | as Spring begins it, before it takes a connection |
| An interface of `penitent-spring` called inside a block without `tx.using`, when it uses the block's `DataSource` | as it is called, as in the core |

<!-- snippet: untested -->
```text
a Spring transaction is active on this thread, and a PlsqlTransaction would commit apart from it: in a Spring application, Spring owns the transactions, so use @Transactional or TransactionTemplate, whose interfaces already take part, or open the block outside any Spring transaction
```

The first refusal works wherever `penitent-spring` is on the classpath. The second needs Spring to know about it:
`@EnablePlsqlPackages` and the starter add it, once, to every transaction manager in the context, the ones Spring
Boot creates included. Out of their reach are:

- **an application that uses Spring with the core alone,** without `penitent-spring`: the core does not know Spring;
- **a transaction manager created with `new`** outside the context, such as `new TransactionTemplate(new
  DataSourceTransactionManager(dataSource))` inside a method;
- **a Spring Boot application with several `DataSource`s and none `@Primary`,** where the starter steps aside, as
  [Spring and Spring Boot](spring.md) explains: declare `@EnablePlsqlPackages`, which adds the refusal too;
- **work with no Spring transaction behind it,** such as a `JdbcTemplate` call, or a method with `SUPPORTS`,
  `NOT_SUPPORTED` or `NEVER` propagation, inside a block: like any JDBC outside `tx.connection()`, it runs on a
  connection of its own.

### Moving from `PlsqlTransactions` to Spring

Each piece of a block has a direct counterpart, and interfaces of `penitent-spring` take part in the transaction by
themselves, so `tx.using` simply goes away:

| With `PlsqlTransactions` | With Spring |
|---|---|
| `transactions.run(tx -> …)` | a `@Transactional` method, or `transactionTemplate.executeWithoutResult(status -> …)` |
| `transactions.runWithResult(tx -> …)` | a `@Transactional` method that returns, or `transactionTemplate.execute(status -> …)` |
| `tx.using(orders).placeOrder(…)` | `orders.placeOrder(…)`, with `orders` created by `penitent-spring` |
| `tx.connection()` | a `JdbcTemplate`, or `DataSourceUtils.getConnection(dataSource)` |
| `tx.setRollbackOnly()` | `status.setRollbackOnly()`, or `TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()` |
| rollback on every exception, checked or not | `@Transactional(rollbackFor = Exception.class)`: by default, Spring commits on a checked exception |

The last row is the one to watch: a block rolls back on a checked exception, such as a `SQLException` or an
`IOException`, and a plain `@Transactional` does not. `TransactionTemplate` rolls back on any exception its callback
throws, which can only be unchecked.

<!-- snippet: untested -->
```java
@Transactional(rollbackFor = Exception.class)   // as the block did
public void discount(long orderId) {
    PlsqlOut<Long> id = new PlsqlOut<>();
    orders.placeOrder(605, new BigDecimal("100.00"), id);                            // was tx.using(orders)
    jdbc.update("update orders set total = total * 0.9 where id = ?", id.get());   // was tx.connection()
}
```

## Without Spring: `PlsqlTransactions`

**Use it when** the application uses the core alone, and several calls, or calls and your own JDBC, have to succeed
or fail together: placing an order and updating its stock, or calling a routine and writing an audit row in the same
step.

**Don't use it when** the application uses Spring or a Jakarta EE container, which already manage transactions (with
Spring, [`penitent-spring` refuses the overlaps](#plsqltransactions-in-a-spring-application)); when
each call is a unit of work by itself, where it only adds a commit the call would have made anyway; or when you need
more than one connection, one thread and no nesting, such as propagation or timeouts.

For an application that uses the core alone, `PlsqlTransactions` runs a block in one transaction: it commits when
the block returns, and rolls back when it throws. It holds nothing but the `DataSource` and the exception
translator, so creating it costs nothing: keep one, or create it where you need it. Use the same `DataSource`
object as the interfaces, for the reason below:

<!-- snippet: transactions/plsql-transactions -->
```java
PlsqlTransactions transactions = PlsqlTransactions.of(dataSource);   // cheap: once, or where you need it
```

Inside the block, an interface takes part through `tx.using`, and plain JDBC through `tx.connection()`:

<!-- snippet: transactions/run -->
```java
transactions.run(tx -> {
    Orders inTransaction = tx.using(orders);

    PlsqlOut<Long> orderId = new PlsqlOut<>();
    inTransaction.placeOrder(605, new BigDecimal("100.00"), orderId);
    try (PreparedStatement discount = tx.connection().prepareStatement(
            "update orders set total = total * 0.9 where id = ?")) {
        discount.setLong(1, orderId.get());
        discount.executeUpdate();
    }
});   // committed: the block returned
```

To return a value from the block, use `runWithResult`:

<!-- snippet: transactions/run-with-result -->
```java
long orderId = transactions.runWithResult(tx -> {
    PlsqlOut<Long> id = new PlsqlOut<>();
    tx.using(orders).placeOrder(606, new BigDecimal("50.00"), id);
    return id.get();
});
```

**How a block ends:**

- **It returns:** the transaction is committed. If `tx.setRollbackOnly()` was called, it is rolled back instead, and
  the block still returns normally.
- **It throws anything,** a `RuntimeException`, a checked exception or an `Error`: the transaction is rolled back,
  and the exception comes out as it was thrown, without a wrapper. A failure to roll back is added to it as
  suppressed, and never replaces it.
- **The commit fails,** for instance on a deferred constraint (`ORA-02091`): the transaction is rolled back, and the
  failure arrives through the exception translator, as a `PlsqlExecutionException` by default. Pass `PlsqlSettings`
  to `PlsqlTransactions.of` to choose another translator.
- **Always:** the connection goes back to the `DataSource` with its auto-commit as it was found.

### Exceptions from the block

Nothing is wrapped. `run` and `runWithResult` are generic in the exception the block throws, `<E extends Exception>`,
so the compiler knows what comes out, and what comes out is the exception the block threw, after the rollback.

**A block that throws no checked exception** asks nothing of its caller. Routine errors, such as
`PlsqlRoutineRaisedException`, are unchecked, and come out as they are:

<!-- snippet: transactions/exceptions-none -->
```java
transactions.run(tx -> tx.using(orders).placeOrder(607, new BigDecimal("10.00"), orderId));   // no try, no throws
```

**A block that throws a `SQLException`,** because it uses JDBC, throws it to its caller, which handles or declares
it as it would without the block. There is no wrapper to catch, and no cause to unwrap:

<!-- snippet: transactions/exceptions-sql -->
```java
void discount(long orderId) throws SQLException {   // the same as without the block
    transactions.run(tx -> {
        try (PreparedStatement update = tx.connection().prepareStatement(
                "update orders set total = total * 0.9 where id = ?")) {
            update.setLong(1, orderId);
            update.executeUpdate();
        }
    });
}
```

**A block that throws two checked types** makes Java infer their common supertype, usually `Exception`, since it has
no union types. The compiler then asks the caller for `Exception`, but what arrives is still the original, which a
`catch` of its own type receives:

<!-- snippet: transactions/exceptions-two-types -->
```java
try {
    transactions.run(tx -> {
        PlsqlOut<Long> orderId = new PlsqlOut<>();
        tx.using(orders).placeOrder(609, new BigDecimal("100.00"), orderId);
        discount(tx, orderId.get());   // throws SQLException
        export(orderId.get());         // throws IOException
    });
} catch (IOException e) {
    // the original IOException, not a wrapper; the order was rolled back
    retryExportLater(e);
} catch (Exception e) {
    // E was inferred as Exception, the common supertype, so the compiler asks for it
    reportFailure(e);
}
```

To keep a narrower type, handle one of them inside the block.

**What the library itself throws is unchecked:** a failed commit or a connection that cannot be obtained arrives
through the exception translator, as a `PlsqlExecutionException` by default, and a misuse, such as a nested block or
a view used after its block, as an `IllegalStateException`. Neither has to be declared.

Unlike `@Transactional`, which by default does not roll back on a checked exception, a block rolls back on every
exception, checked or not, and on an `Error`.

**It is strict about leaving the transaction.** These fail with a clear message, instead of quietly running
somewhere else:

| What | Why |
|---|---|
| An interface of the same `DataSource` called directly inside the block, without `tx.using` | it would take another connection, and run outside the transaction |
| A view from `tx.using`, or `tx.connection()`, used after the block | the transaction is over |
| The same, used from another thread, such as a `CompletableFuture` | a connection serves one thread at a time |
| `commit`, `rollback()`, `setAutoCommit`, `close` or `abort` on `tx.connection()` | the block ends the transaction, not the code inside it. Savepoints work, and `unwrap` reaches the driver's connection, without these protections |
| A block inside another, on the same thread | nested blocks are not supported: pass the `tx` you have |
| With `penitent-spring`, a block inside a Spring transaction, or a Spring transaction inside a block | each would commit apart from the other: see [`PlsqlTransactions` in a Spring application](#plsqltransactions-in-a-spring-application) |

<!-- snippet: outside-the-transaction -->
```text
com.example.shop.Orders uses the DataSource of the PlsqlTransaction open on this thread, and would run outside it: call it through tx.using(...)
```

An interface of **another** `DataSource` is another resource, and runs on its own, outside the block's transaction.

> **"The same `DataSource`" means the same object.** The check compares the `DataSource` of the block and the one an
> interface was created with by identity, not by the database they reach. If one of them is wrapped, for metrics,
> logging or tracing, or each has a pool of its own for the same URL, the interface counts as another resource: called
> without `tx.using`, it runs outside the transaction, and nothing refuses it. Create `PlsqlTransactions` and the
> interfaces from the same `DataSource` object, as a dependency injection container does by itself. `tx.using` works
> either way. The one wrapper that counts as the same `DataSource` is the one `penitent-spring` puts around it to join
> Spring's transactions, so its interfaces are refused just the same.

`PlsqlTransactions` is deliberately small: one connection, one thread, no nesting, no propagation, no timeout. For
more, use Spring or a Jakarta EE container. A routine's own `COMMIT` or `ROLLBACK` still
[ends the block's transaction](#when-the-routine-commits-or-rolls-back-by-itself), as it ends any other.

## A pool in auto-commit

**Use it when** each call stands on its own: a query, or a routine that is a unit of work by itself, including one
that commits by itself. It is what you get with `PlsqlPackages.create` and nothing else, and the cheapest setup: no
transaction to manage, and the connection goes back to the pool as soon as the call ends.

**Don't use it when** two calls, or a call and your own JDBC, must succeed or fail together: the first call's work
stays committed when the second one fails. Use `PlsqlTransactions` then.

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

**Use it when** the connection, or the transaction, is already in your hands: your own transaction manager, a batch
that commits every thousand rows, or legacy code that receives a `Connection` and cannot move into a block.
`PlsqlTransactions` is built on the same view, so this is the lower level of the same mechanism.

**Don't use it when** a `PlsqlTransactions` block would do. Here the commit, the rollback, restoring auto-commit and
closing the connection are yours, and none of the block's protections apply: the connection is not guarded against a
stray `commit`, and a view used after the connection is closed only fails then.

Bind the interface to your connection with `PlsqlPackages.using`, and commit or roll back yourself. Create the
interface once, as usual:

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
  is still safe to share;
- **keeps the interface's exceptions:** a view of an interface created by `SpringPlsqlPackages` still raises Spring's
  `DataAccessException` classes, such as `BadSqlGrammarException` or `PessimisticLockingFailureException`, and routine
  errors as `PlsqlRoutineRaisedException`;
- **runs on its connection even inside a Spring transaction:** the connection you gave wins over the transaction's,
  so a view inside a `@Transactional` method is not part of that transaction.
- **keeps what Spring put around the bean:** a view of a bean that Spring wrapped, for `@Validated`, Resilience4j or
  advice of your own, applies the same behavior, in the same order, except a `@Transactional` on the interface: the
  view runs in the transaction of its connection. The same goes for `tx.using`.

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
