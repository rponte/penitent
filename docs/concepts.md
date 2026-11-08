# How Penitent works

A few words mean one thing in Java and another in PL/SQL. This page fixes the terms used across the documentation,
and then follows a call from the Java method to the database and back.

## Terms

### Routine

In PL/SQL, a **routine** is a function or a procedure, the two kinds of subprogram you can call. A function returns
a value and a procedure does not, though both can return values through `OUT` parameters. A routine lives in one of
two places:

- **in a package,** declared in its spec, as `ORDERS_PKG.CALCULATE_FREIGHT`;
- **directly in a schema,** created with `CREATE FUNCTION` or `CREATE PROCEDURE`, as `ROUND_TO_CENTS`. Oracle calls
  it a standalone routine.

In Java, the closest thing is a method, and that is the mapping: **each method of an interface calls one routine**.
In this documentation, "routine" always means the PL/SQL side, and "method" the Java side.

<!-- snippet: untested -->
```text
Java                                     PL/SQL
────                                     ──────
interface Orders           ─────────►    package ORDERS_PKG          (the container)
  calculateFreight(...)    ─────────►      FUNCTION calculate_freight (a routine)
  cancelOrder(...)         ─────────►      PROCEDURE cancel_order     (a routine)

interface Money            ─────────►    schema SHOP                 (the container)
  roundToCents(...)        ─────────►      FUNCTION round_to_cents    (a standalone routine)
```

Not everything callable is a routine: an OBJECT type's member method, for example, can only be reached through an
[anonymous block](anonymous-blocks.md).

### Container

The package or schema whose routines an interface maps. `@PlsqlPackage("ORDERS_PKG")` makes the package the
container, and `@PlsqlSchema` the schema. An interface has one container, and an interface made only of
`@PlsqlBlock` methods has none.

### Dictionary

Oracle's data dictionary: the views where the database describes its own objects. Penitent reads `ALL_PROCEDURES`
and `ALL_ARGUMENTS` for routines and their parameters, `ALL_TYPE_ATTRS` and `ALL_PLSQL_TYPE_ATTRS` for the
attributes of composites, `ALL_COLL_TYPES` and `ALL_PLSQL_COLL_TYPES` for collections, and `ALL_TAB_COLUMNS` for
`%ROWTYPE`. They are the `ALL_` views, so Penitent sees exactly what the session can see, with no special privilege.

### Composite and collection

A **composite** is a value with named attributes: an OBJECT type, a RECORD declared in a package, or a table's
`%ROWTYPE`. In Java, it is a `record` or a JavaBean. A **collection** is a nested table, a `VARRAY` or an `INDEX BY`
table, and in Java a `List<T>`. See [Types](types.md).

### Proxy

`PlsqlPackages.create` returns an object that implements your interface: a Java dynamic proxy. You never implement
the interface yourself. A `default` method of the interface still runs your own code, and can call the other methods.

## What happens in a call

When you call `orders.calculateFreight("SP", "CE")`:

1. **A connection is taken from the `DataSource`.** With [`penitent-spring`](spring.md), it is the current
   transaction's connection, if there is one. A view made with [`PlsqlPackages.using`](transactions.md) uses the
   connection it was given instead.
2. **On the method's first call, the dictionary is read,** on that same connection: which routine the method calls,
   its parameters and their types, and the attributes of every composite involved. That is about 5 queries for a
   routine with scalars, and up to about 16 with nested composites. The result is cached in the proxy, so later
   calls skip this step.
3. **The method is checked against what was read:** the routine exists, procedure or function, the parameters, their
   directions and their types. A mismatch is a `PlsqlMappingException`, and nothing is sent to the database.
4. **The call is built and the values are bound:** `{? = call SHOP.ORDERS_PKG.CALCULATE_FREIGHT(?, ?)}`, with each
   Java value converted to what the Oracle type expects, through your [converters](converters.md) when there are
   any.
5. **The routine runs.**
6. **The results are read:** the return value and the `OUT` parameters are converted back to the Java types. A
   cursor is read whole into a list and closed.
7. **The connection is given back,** unless it is a view's, which stays open, and the value is returned to you.
   Penitent never commits or rolls back: the transaction belongs to whoever owns the connection.

If the database fails at any step, the `SQLException` goes through the exception translator, which decides what you
receive: a [`PlsqlRoutineRaisedException`](errors.md) when the error came from the routine's logic, and otherwise a
`PlsqlExecutionException`, or a Spring `DataAccessException` with `penitent-spring`.

A proxy is safe to share between threads: each call takes its own connection, and the only state kept between
calls is the dictionary cache. Create it once, and reuse it. A view bound to a connection shares that cache, and
follows the connection's rules: one thread at a time.

## Strict by design

Penitent could guess in many places: drop a time zone, round a number, swap two attributes of the same type, read
an unknown flag as `false`. Each of those would work in a test and corrupt data in production, without any error.
So it refuses, with a message that says what it looked up and what exists, and you decide.

The cost is that some mistakes show up as errors you have to fix. To find all of them at once, instead of one call at
a time, use [validation](validation.md).
