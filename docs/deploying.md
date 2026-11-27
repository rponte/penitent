# Changing PL/SQL while the application runs

A deploy often changes PL/SQL while the application keeps running: a package body gets a fix, a function gets a new
parameter, a table gets a column. Most changes need nothing from you. In short:

| The deploy changes | What the calls do | What to do |
|---|---|---|
| a package body | pick it up once the DDL runs, which waits for the calls in progress and makes the new ones wait behind it | nothing; a package that keeps state between calls fails each connection once with `ORA-04068` |
| a package spec, changed | wait behind the DDL, then **fail on every call until the body is compiled again** | deploy the spec and the body together, [as below](#the-spec-and-the-ddl-itself) |
| a package, or a routine, left INVALID by a compilation that failed | a new proxy refuses it, saying it is INVALID | compile what the migration left invalid, [at its end](#a-compilation-that-fails) |
| a routine's signature, in a way the method no longer fits | the next call says what changed | update the method |
| a routine's signature, in a way the method still fits, as a renamed parameter | the next call says so, without running, and the one after works | nothing |
| the order of a routine's parameters | right while the application runs; **after a restart, binding by position sends values to other parameters, with no error** | bind [by name](routines.md#by-position-or-by-name) where the order may change |
| an object type | the next call says what changed | update the Java type, and renew the pool's connections, [as below](#recovering-without-a-restart) |
| the table of a `%ROWTYPE`, a column added | **a session that reads the `%ROWTYPE` again can break it for every session after it**, until a DBA clears the shared pool | read [below](#a-composite-type) before adding a column under a running application |
| the package a synonym points to, as a blue/green deploy does | the next call goes to the new one | nothing, if both packages have the same signatures |

## Before and after a deploy

- **Before:** prefer packages without state, and bind by name where the order of a routine's parameters could
  change.
- **During:** a change of signature or type is a change of the application's contract with the database: deploy it
  with the application, not under it. Replace a package's spec and body in the same script, at a quiet moment, since
  the DDL waits for every call in progress and the calls after it wait behind it.
- **At the end of the migration:** compile what it left invalid, [as below](#a-compilation-that-fails).
- **After:** update the methods and types that no longer fit, as the calls' errors say, and renew the pool's
  connections, or restart the application, when a type changed. Run
  [validation](validation.md) at startup, so that a method that no longer matches fails then, with a clear message,
  rather than on the first request.

## Why a change can go unnoticed

Three layers keep something about a routine, each for a different time:

| Layer | What it keeps | Until |
|---|---|---|
| **Penitent**, in each proxy | the signatures of the routines a package interface maps, read in one query on the first call of any of its methods, and the attributes of each composite type | the proxy goes away; for a Spring bean, until the application restarts |
| **The Oracle driver**, in each connection | the description of each object type it has sent or read | the connection leaves the pool |
| **The database**, in each session | the values of a package's variables, its *state* | the session ends |

A change in the database reaches none of these by itself. What happens next depends on the change.

## What each change does

Measured on Oracle 23ai with driver 23 and on Oracle 19c with driver 19, with a function
`f(p_a NUMBER, p_b NUMBER) RETURN NUMBER` called as `f(10, 3)` before and after the change. The details are in
[#81](https://github.com/rponte/penitent/issues/81).

### The body only

| Change | What the next call does |
|---|---|
| a package body without state: no variables, constants or cursors at package level | runs the new body, with no error |
| a package body **with state** | fails **once on each connection** that had used the package: `ORA-04068: existing state of packages has been discarded`. The next call on that connection runs the new body, with the state starting over |

The second case is Oracle's, not Penitent's: the session had values in the package's variables, and the new body
cannot know what to do with them. With a pool, the error shows up once per connection, at its next use, which may be
long after the deploy. The call that fails is undone, as Oracle undoes any call that fails, but work already done by
earlier calls of the same transaction is not.

Penitent does not repeat the call by itself. A call that fails with `ORA-04068` ran with the package's state, and its
retry would run without it: if the application keeps something in that state, such as the current user set when the
session began, the retry would run with that value gone. Oracle's own reference warns that mishandling this error "can
lead to silent data corruption". To avoid the error, Oracle offers:

- **a package without state**, the simplest: keep values in tables or parameters, not in package variables;
- **`PRAGMA SERIALLY_REUSABLE`**, for a package whose variables only need to last one call;
- **edition-based redefinition**, which compiles the new code in a new edition while sessions keep using the old one;
- **`SESSION_EXIT_ON_PACKAGE_STATE_ERROR = TRUE`**, from Oracle 19c RU 19.23: the session ends instead of raising
  `ORA-04068`, and a pool replaces a connection that ended
  ([reference](https://docs.oracle.com/en/database/oracle/oracle-database/19/refrn/SESSION_EXIT_ON_PACKAGE_STATE_ERROR.html)).
  See [below](#when-a-package-with-state-is-recompiled) how to set it for a pool;
- **`CREATE OR REPLACE PACKAGE ... RESETTABLE`**, from Oracle 26ai, for a package whose state can be discarded
  ([reference](https://docs.oracle.com/en/database/oracle/oracle-database/26/lnpls/RESETTABLE-clause.html)).

### The signature

Penitent reads the signatures of a package interface's routines once, in one query, on the first call of any of its
methods, and keeps them. Each method is described from them on its own first call. Every call names the parameters as
that description did, by position too: `F(P_A => ?, P_B => ?)`. When a call fails as if the routine had changed,
Penitent describes the routine again, and says what no longer fits. That holds for a method first called after the
change, too: its description comes from the signatures read before it, so its first call fails as the table says, and
the next one uses the new signature. While the application runs:

| Change | What the next call does |
|---|---|
| a parameter added at the end, with `DEFAULT` | works: the call leaves it out |
| a parameter with `DEFAULT` inserted in the middle, or two parameters that swap places | works, with the right values: each value goes to the parameter it was meant for, by name |
| a parameter renamed, binding by position | fails once, without running, with a `PlsqlExecutionException` that says so: `PLSQL_TEST.ORDERS_PKG.F changed since this proxy described it, and was described again: the call did not run, and the next one uses the new signature. Parameters before: [P_A NUMBER, P_B NUMBER]; now: [P_A NUMBER, P_C NUMBER].`, followed by Oracle's `PLS-00306`, which stays its cause and its code. The next call uses the new name |
| an overload added | works, as long as the method still fits the version it used |
| a parameter added without `DEFAULT`, a parameter renamed when binding by name, a function turned into a procedure, or the routine removed | fails without running: Oracle refuses it with `PLS-00306: wrong number or types of arguments`, `PLS-00222` or `PLS-00302`, and Penitent, having read the routine again, throws a `PlsqlMappingException` that says so, such as `PLSQL_TEST.ORDERS_PKG.F changed since this proxy described it: the method declares 2 parameters, but PLSQL_TEST.ORDERS_PKG.F expects 3: [...]`. The calls after it are refused before they run, as a new proxy would, until the method fits again |
| the return type changed | fails converting the value, such as `ORA-06502` |
| **a parameter's type changed to one Oracle converts to**, such as `NUMBER` to `VARCHAR2` | **runs, with Oracle's implicit conversion**: the result depends on what the routine now does with the value |

The failures are safe: the call does not run, and Penitent does not repeat it. Update the method to the new
signature, as in [Troubleshooting](troubleshooting.md#the-method-does-not-match-the-routine). Penitent says a routine
changed only when it reads it again and the method no longer fits: a `PLS-00306` raised inside the routine, by its own
dynamic PL/SQL, stays as Oracle says it.

**After a restart, binding by position follows the new order.** A new proxy describes the routine as it is now, so
the order the Java method was written for is gone: measured with `f(p_a, p_b)` called as `f(10, 3)`, which returns
`7`, a parameter with `DEFAULT` inserted in the middle returns `10`, and two parameters that swap places return
`-7`, with no error. Validation at startup does not catch them either, since the method still fits.
[By position or by name](routines.md#by-position-or-by-name) shows each case. What no error reveals:

- **Bind by name** when the package's order may change, as Oracle recommends for a subprogram maintained by someone
  else: `@PlsqlPackage(value = "...", bind = PlsqlBind.BY_NAME)`, or for every interface in
  [Settings](settings.md). The values then go to the right parameters, while the application runs and after a
  restart.
- **Treat a parameter's type change as a change of the routine's contract**, as you would in Java, and review the
  callers.

### The spec, and the DDL itself

The DDL that replaces a package, its body or its spec, needs the package to itself: it waits for every call in
progress, and every call that starts meanwhile waits behind it. Measured on every version supported, three runs each,
with a call taking 4 s, the DDL started 1 s into it, and another call 0.5 s later, a package without state:

| DDL | The DDL | A call started meanwhile | The next call |
|---|---|---|---|
| the body replaced | waits **about 3 s**, until the long call ends | waits **about 2.5 s**, behind the DDL, then runs the new body | works |
| the spec replaced, identical | about 6 ms, with no wait: Oracle sees the same text | runs | works |
| the spec changed, a function added | waits **about 3 s** | waits about 2.5 s, then fails: `ORA-04068` or `ORA-04065`, or `ORA-04063: package body ... has errors` | **`ORA-04063`, until the new body is compiled** |

A spec that changed invalidates its body, even in a package without state, so the package fails every call until the
body is compiled again. Penitent can do nothing about either: it is Oracle's locking, and the calls fail inside the
database. To keep the window short:

- **replace the spec and the body in the same script,** the body right after the spec, and leave the spec alone when
  it does not change: an identical one costs nothing;
- **deploy at a quiet moment,** since every call to the package waits for the longest one in progress: with long
  calls, the wait can take every connection of the pool;
- **give the calls a timeout,** such as the pool's or the transaction's, so that a wait behind the DDL fails rather
  than holds a connection for good;
- **use edition-based redefinition** to avoid it altogether: the new code is compiled in a new edition, while the
  sessions keep running the old one.

### A compilation that fails

A migration can leave a package, or a standalone routine, INVALID: when a type it uses is dropped and created again,
say, and a compilation runs in between. Oracle then lists none of its routines in the dictionary, so a new proxy,
which reads them there, cannot call it, and, not calling it, never makes Oracle compile it again. It says so:

<!-- snippet: untested -->
```text
plus(BigDecimal): package APP.ORDERS_PKG is INVALID, and what it takes and returns cannot be read until it compiles:
compile it, as ALTER PACKAGE APP.ORDERS_PKG COMPILE, or have each migration compile what it left invalid, as
DBMS_UTILITY.COMPILE_SCHEMA does; its errors, if any, are in ALL_ERRORS
```

On Oracle 19c, Penitent's lookup of the name compiles it already, and the call works: do not rely on it. Have the
migration, which changed the database, compile what it left invalid, at its end; with Flyway, in an `afterMigrate`
callback:

<!-- snippet: untested -->
```sql
BEGIN
  DBMS_UTILITY.COMPILE_SCHEMA(schema => USER, compile_all => FALSE);  -- only what is invalid
END;
```

`UTL_RECOMP` does the same in parallel, in the order of the dependencies. Penitent does not compile anything itself:
it does not change the database, and a compilation started by a call would make the other calls of the package wait,
as above, and discard a package's state in every session that used it.

### A composite type

| Change | What the next call does |
|---|---|
| an object type gains an attribute, with `ALTER TYPE ... ADD ATTRIBUTE ... CASCADE` | the database refuses the value in the old shape: `ORA-03101: invalid input data`, or, on 21c, an internal `ORA-00600 [kope2_readstr232]` that also closes the connection. Penitent reads the type again and, if the Java type no longer covers it, says so: `a type of ... changed since this proxy described it: Pair does not cover attribute(s) of PAIR_T: [C NUMBER]`, at that call or, on 21c, at the next one. Once the Java type covers it, the connections that used the old type still fail, with `ORA-17049: Inconsistent Java and SQL object types`, because the driver keeps the old type in each connection: Penitent keeps that error, with its code, and adds what to do |
| the table of a `%ROWTYPE` gains a column | **the database closes the connection** that reads the `%ROWTYPE` again: `ORA-03138: connection terminated due to security policy violation`, `ORA-03113`, or, with driver 19, `No more data to read from socket`; and that read can break it for every session after it, as below |

#### A column added to the table of a `%ROWTYPE`

**This one can take every session down.** Measured with plain JDBC, without Penitent, on every version supported: a
session reads a routine's `%ROWTYPE` as a `Struct`, a column is added to its table, and **the same session reads it
again**, as the old version of an application does in a rolling deploy. From then on:

| | The session that read it again | Every **new** connection that reads it | What undoes it |
|---|---|---|---|
| 23ai, driver 23 | `ORA-03113`, its connection closed | **`ORA-03113`, its connection closed** | only `ALTER SYSTEM FLUSH SHARED_POOL`; not `ALTER PACKAGE ... COMPILE`, nor the package created again |
| 21c, driver 23 | `ORA-03113` | **`ORA-03113`** | only `ALTER SYSTEM FLUSH SHARED_POOL` |
| 19c, driver 23 | `ORA-03113` | **`ORA-03113`** | `ALTER PACKAGE ... COMPILE`; on an Autonomous Database, `FLUSH SHARED_POOL` is refused, `ORA-01031` |
| 23ai, driver 19 | `No more data to read from socket` | **the old shape, without the new column, and no error** | only `ALTER SYSTEM FLUSH SHARED_POOL` |

Without that second read, everything works, on every version: new connections read the new column, and the package
compiles again on its first call. The database seems to keep the description of the type behind the `%ROWTYPE` in its
shared pool, left stale by the read: that is inferred, not confirmed by Oracle. With driver 19, the old shape is a
value with fewer attributes than a proxy that described the new type expects, and Penitent refuses it, saying so,
rather than read it into the wrong properties. The details are in
[#184](https://github.com/rponte/penitent/issues/184), B1.

So, until a safer order is measured, add a column to the table of a `%ROWTYPE` that a routine takes or returns only
while no running version of the application reads it, and be ready, if one does, to clear the shared pool, at a cost
to the whole database, or, on 19c, to compile the package.

#### What the driver keeps

Only new connections read the new type: the database session already sees the new one, but the driver keeps the
old one in a cache of its own, which its public methods do not clear. Restart the application, or renew the pool's
connections, [as below](#recovering-without-a-restart). Then also update the Java record or JavaBean, since a
composite has to cover every attribute.

### A synonym pointed elsewhere

A blue/green deploy can install the new version of a package beside the old one, and switch the application to it by
pointing a synonym there, as `CREATE OR REPLACE SYNONYM orders_pkg FOR orders_pkg_v2`. Penitent calls a routine
through the synonym the interface names, as PL/SQL calls it, so the proxy's next call goes to the new package, without
a restart, and keeps working once the old one is dropped. It describes the routine, and binds its types, at the
synonym's target, since the dictionary only knows real names.

| The new package | What the next call does |
|---|---|
| has the same signatures | runs in the new package |
| changed a signature | fails once, without running, saying so and where the synonym points now: `ORDERS_PKG now points to SALES.ORDERS_PKG_V2, not SALES.ORDERS_PKG_V1`; the one after uses the new signature, as for [a signature](#the-signature) |
| takes a RECORD of its own | fails once the same way, since a RECORD of the old package is not one of the new: the one after works |

A synonym with no schema declared resolves in the session's schema, as PL/SQL's does: one with
`ALTER SESSION SET CURRENT_SCHEMA` may reach another object.

## Recovering without a restart

Penitent never repeats a call that failed, and never closes a connection it was given: the connection belongs to the
pool and to the caller's transaction, and closing it would undo the caller's work behind its back. Recovering is the
pool's job, as HikariCP does it: it replaces a connection rather than repairing it, it never repeats a statement, and
it leaves every decision beyond its default to explicit configuration. The examples below use HikariCP, Spring
Boot's pool, each one measured on Oracle 23ai and 19c.

### When a type changed

HikariCP replaces a connection by itself only when the connection is broken: a SQLState of class `08`, as when the
database closes the session after a `%ROWTYPE` changed. `ORA-17049` and `ORA-03101` come from a healthy connection,
which HikariCP keeps, still holding the old type. On Oracle 19c, the connection was measured to drop the old type after
a new proxy described a routine on it, a side effect of `DBMS_UTILITY.NAME_RESOLVE` that Oracle does not document:
do not rely on it. Two ways to replace it:

- **At once, for every connection,** as a step of the deploy, through the pool's MXBean, also reachable through JMX
  with `registerMbeans`:

  <!-- snippet: deploying/soft-evict -->
  ```java
  hikari.getHikariPoolMXBean().softEvictConnections();
  ```

  An idle connection closes at once; one in use closes when it comes back to the pool, so no call in progress is cut.

- **One connection at a time, when it fails,** with an `SQLExceptionOverride`, which HikariCP asks about each
  `SQLException`:

  <!-- snippet: deploying/stale-type-eviction -->
  ```java
  /** Evicts a connection whose driver keeps an object type a deploy has changed. */
  public static class StaleTypeEviction implements SQLExceptionOverride {

      public Override adjudicate(SQLException e) {
          // ORA-17049: the driver's old type; ORA-03101: the old type sent to the database
          return e.getErrorCode() == 17049 || e.getErrorCode() == 3101 ? Override.MUST_EVICT
                  : Override.CONTINUE_EVICT;
      }
  }
  ```

  <!-- snippet: deploying/exception-override -->
  ```java
  config.setExceptionOverride(new StaleTypeEviction());
  ```

  With Spring Boot, name the class in `spring.datasource.hikari.exception-override-class-name`. The call that failed
  still fails: the override only gives the next borrower a new connection. HikariCP takes one override, so if the
  application already has one, add these codes to it. Return `CONTINUE_EVICT` for everything else, as HikariCP's own
  documentation advises.

### When a package with state is recompiled

`ORA-04068` comes from a healthy session, and the call after it works, so HikariCP rightly keeps the connection.
Two ways to handle it, both outside Penitent:

- **Make the session end instead,** with Oracle's `SESSION_EXIT_ON_PACKAGE_STATE_ERROR`, set on each new connection:

  <!-- snippet: deploying/session-exit -->
  ```java
  config.setConnectionInitSql("ALTER SESSION SET SESSION_EXIT_ON_PACKAGE_STATE_ERROR = TRUE");
  ```

  The call then fails with `ORA-00028: session has been terminated`, a connection error, and HikariCP replaces the
  connection. **The whole transaction is undone,** not only the call that failed, so nothing partial is left, and the
  next call works with the new state. It needs Oracle 19c RU 19.23 or later, and the right to change the parameter:
  on an Autonomous Database 19c, the statement failed with `ORA-01031: insufficient privileges`, and with it every new
  connection of the pool. Ask the DBA, and try it before turning it on.

- **Retry the whole unit of work,** in the application, at the boundary of its transaction, with Spring Retry or
  Resilience4j: retry when the cause is a `PlsqlExecutionException` whose `getCode()` is `4068`, or the connection
  error above. A retry there runs the transaction again from its start, with a new state, rather than one call in
  the middle of it. It is still the application's call: only it knows whether its work can run twice.

## See also

- [Validation](validation.md): checks every interface against the database, at startup.
- [By position or by name](routines.md#by-position-or-by-name): what each binding protects, with an example of each
  risk.
- [Troubleshooting](troubleshooting.md): the messages Penitent gives when a method does not match.
