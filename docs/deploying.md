# Changing PL/SQL while the application runs

A deploy often changes PL/SQL while the application keeps running: a package body gets a fix, a function gets a new
parameter, a table gets a column. Most changes need nothing from you. In short:

| The deploy changes | What the calls do | What to do |
|---|---|---|
| a package body | pick it up at once | nothing; a package that keeps state between calls fails each connection once with `ORA-04068` |
| a routine's signature, in a way the method no longer fits | the next call says what changed | update the method |
| a routine's signature, in a way the method still fits, as a renamed parameter | the next call says so, without running, and the one after works | nothing |
| the order of a routine's parameters | right while the application runs; **after a restart, binding by position sends values to other parameters, with no error** | bind [by name](routines.md#by-position-or-by-name) where the order may change |
| an object type | the next call says what changed | update the Java type, and renew the pool's connections, [as below](#recovering-without-a-restart) |
| the table of a `%ROWTYPE` | the database closes the connection, which the pool replaces | update the Java type if a column was added |

## Before and after a deploy

- **Before:** prefer packages without state, and bind by name where the order of a routine's parameters could
  change.
- **During:** a change of signature or type is a change of the application's contract with the database: deploy it
  with the application, not under it.
- **After:** update the methods and types that no longer fit, as the calls' errors say, and renew the pool's
  connections, or restart the application, when a type changed. Run
  [validation](validation.md) at startup, so that a method that no longer matches fails then, with a clear message,
  rather than on the first request.

## Why a change can go unnoticed

Three layers keep something about a routine, each for a different time:

| Layer | What it keeps | Until |
|---|---|---|
| **Penitent**, in each proxy | each routine's signature, read from the dictionary on the method's first call, and the attributes of each composite type | the proxy goes away; for a Spring bean, until the application restarts |
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

Penitent describes each routine once, on the method's first call, and keeps that description. Every call names the
parameters as that description did, by position too: `F(P_A => ?, P_B => ?)`. When a call fails as if the routine
had changed, Penitent describes the routine again, and says what no longer fits. While the application runs:

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

### A composite type

| Change | What the next call does |
|---|---|
| an object type gains an attribute, with `ALTER TYPE ... ADD ATTRIBUTE ... CASCADE` | the database refuses the value in the old shape: `ORA-03101: invalid input data`, or, on 21c, an internal `ORA-00600 [kope2_readstr232]` that also closes the connection. Penitent reads the type again and, if the Java type no longer covers it, says so: `a type of ... changed since this proxy described it: Pair does not cover attribute(s) of PAIR_T: [C NUMBER]`, at that call or, on 21c, at the next one. Once the Java type covers it, the connections that used the old type still fail, with `ORA-17049: Inconsistent Java and SQL object types`, because the driver keeps the old type in each connection: Penitent keeps that error, with its code, and adds what to do |
| the table of a `%ROWTYPE` gains a column | **the database closes the connection**: `ORA-03138: connection terminated due to security policy violation`, or, with driver 19, `No more data to read from socket` |

Only new connections read the new type: the database session already sees the new one, but the driver keeps the
old one in a cache of its own, which its public methods do not clear. Restart the application, or renew the pool's
connections, [as below](#recovering-without-a-restart). Then also update the Java record or JavaBean, since a
composite has to cover every attribute.

## Recovering without a restart

Penitent never repeats a call that failed, and never closes a connection it was given: the connection belongs to the
pool and to the caller's transaction, and closing it would undo the caller's work behind its back. Recovering is the
pool's job, as HikariCP does it: it replaces a connection rather than repairing it, it never repeats a statement, and
it leaves every decision beyond its default to explicit configuration. The examples below use HikariCP, Spring
Boot's pool, each one measured on Oracle 23ai and 19c.

### When a type changed

HikariCP replaces a connection by itself only when the connection is broken: a SQLState of class `08`, as when the
database closes the session after a `%ROWTYPE` changed. `ORA-17049` and `ORA-03101` come from a healthy connection,
which HikariCP keeps, still holding the old type. Two ways to replace it:

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
