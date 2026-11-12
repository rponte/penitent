# 0001. Mocking PL/SQL in integration tests with editions

- **Status:** Accepted, 2026-11-11. The core, without JUnit, is implemented and measured on Oracle 23ai; its tests
  are skipped on other versions until they are measured.
- **Module:** `penitent-test`.

## Context

An application that calls PL/SQL often needs, in a test, a routine to return a fixed value or to fail: a freight
calculation, a call to an external system wrapped in a package, an error that is hard to provoke for real. The test
should still run against a real Oracle, with the real schema, the real SQL and the real PL/SQL around the call, because
that is where most of the behavior lives. A mock that lives only in Java misses exactly that: it cannot stand in for a
routine that other PL/SQL calls.

Oracle's edition-based redefinition (EBR) lets a session see its own version of a package body, while every other
session keeps seeing the real one. Teams already mock PL/SQL this way, with a disposable edition per test and the mock
bodies written by hand as SQL scripts. Penitent already knows, for each method of an interface, which routine it
calls, with its parameters and overload, from the dictionary. So it can do what those scripts do, from the interface
the application already has.

## Decision

A new module, `penitent-test`, mocks a routine of a package in an integration test, in the style of Mockito:

<!-- snippet: untested -->
```java
CalculadoraFretes pkg = mocks.mock(CalculadoraFretes.class);

when(pkg.calculaFrete("SP", "CE")).thenReturn(new BigDecimal("24.90"));
when(pkg.calculaFrete("SP", "XX")).thenThrow(-20200, "Cannot calculate freight to XX");
when(() -> pkg.cotacao(eq("CE"), out(new BigDecimal("99.99"))));

BigDecimal value = service.freightBetween("SP", "CE");   // real code, real database

verify(pkg).calculaFrete("SP", "CE");
```

1. **The interface is the handle.** `mock(...)` returns a proxy that only records the call. The routine, its owner
   and its overload come from the same metadata a real call uses.
2. **Only the mocked subprogram changes.** The package body is read from `ALL_SOURCE` and parsed with
   [IslandSQL](https://github.com/IslandSQL/IslandSQL). The declare section and body of the mocked subprogram are
   replaced by generated code; its heading, every other subprogram, private ones included, and the package spec stay
   as the database has them. Declarations such as `%TYPE`, `SUBTYPE` and `DEFAULT` are kept verbatim.
3. **The generated code** records the call, then checks the stubs, the last one first, so that the last stub that
   matches wins, as in Mockito: a matching one returns its value, sets its `OUT` values or raises its error. No match
   is an error that names the routine and shows the arguments (strict). Other PL/SQL that calls
   the routine sees the mock too.
4. **One edition per test,** created before the test, so that a Spring test transaction opens its connection already
   in it, and dropped after the test. Each stub is compiled into the edition as soon as it is complete, on a connection
   of its own, since DDL commits.
5. **The application's connections enter the edition through its `DataSource`, wrapped in tests:** each connection
   handed out runs `ALTER SESSION SET EDITION` for the current test's edition, and goes back to `ORA$BASE` when it is
   closed.
6. **Prerequisites are explicit DDL,** shipped in the jar and in the documentation, for the user to apply as a
   migration, a Flyway callback or a container init script:
   - `penitent-mock-admin.sql`, for a DBA: `ENABLE EDITIONS FOR PACKAGE`, limited to packages so that the schema's
     tables and object types are left as they are, and `CREATE ANY EDITION` and `DROP ANY EDITION`;
   - `penitent-mock-schema.sql`, for the schema owner: the table `PENITENT_MOCK_CALLS` and the package `PENITENT_MOCK`,
     static, with an autonomous `record` and a `version`.

   The library never runs them by itself. It checks them, the version included, when a test begins, and fails with
   a message that lists everything missing and names the scripts. The schema reads its own state, without a DBA:
   `USER_EDITIONED_TYPES` for the editions, `SESSION_PRIVS` for the privileges. `PlsqlMocks.prepare(admin, owner,
   schema)` runs the same scripts, the first through a DBA's `DataSource` and the second as the schema, as a
   convenience for a disposable database such as Testcontainers; running it again is harmless.
7. **Support for JUnit 5 and Spring Test,** with the Spring support ordered before Spring's transaction listener.

### First version

| In | Later |
|---|---|
| functions and procedures of a package in the user's schema | routines outside a package, another schema, synonyms |
| scalars: numbers, text, dates, timestamps, `null`, and `BOOLEAN` on 23ai | object types, collections, cursors |
| return values, scalar `OUT`, application and predefined errors | `IN OUT`, consecutive returns |
| matchers: exact value, `eq`, `any`, `isNull` | other matchers |
| strict | spy: the real logic when no stub matches |
| JUnit 5, Spring Test | mocking SQL views, which editions can also do |

## Measured

A spike, on Oracle 23ai (`gvenzl/oracle-free:23.26.3`), with a package whose spec uses `%TYPE`, a `SUBTYPE`, a
`DEFAULT`, an overload, an `OUT` parameter, package state and a private function, and a second package calling the
first:

| What | Result |
|---|---|
| a stubbed return, error, `null` argument and `OUT` value | as stubbed |
| arguments with no stub | a clear error naming the routine |
| the overload, the private function and the other routines | still the real ones |
| another package calling the mocked routine | sees the mock |
| the same package, at the same time, outside the edition | still the real one |
| `verify`, through the calls table | every call, including the one made from PL/SQL |
| package state used before entering the edition | no `ORA-04068` |
| two tests in a row, each with its own edition | both pass, and nothing is left behind |
| creating, compiling into and dropping an edition | 5 to 22 ms, about 10 ms and about 15 ms |
| parsing a body with IslandSQL | about 830 ms the first time, while ANTLR warms up, then about 10 ms |

## Alternatives considered

- **Mocks in Java only, with Mockito on the interface.** It already works, needs nothing in the database and suits a
  unit test. It cannot reach a routine that PL/SQL calls, nor exercise the SQL around the call, which is what this
  module is for.
- **Generating the body from `ALL_ARGUMENTS`.** The dictionary resolves `%TYPE` and `SUBTYPE` to their base types, and
  a body whose heading does not match the spec does not compile (`PLS-00323`, measured). Parsing the source keeps the
  declarations as written.
- **Replacing the body in place, without an edition, and restoring it afterwards.** Every other session would see the
  mock, compiling waits for sessions running the package (3.5 s measured), and a test that dies leaves the mock in
  place of the real body.
- **A mock schema, switched with `CURRENT_SCHEMA`.** PL/SQL in the real schema keeps calling the real package, and
  everything else referenced without a schema has to exist in the mock one.
- **A data-driven body, compiled once and reading its stubs from a table.** It avoids recompiling per stub, but every
  value then has to round-trip through a table with its type. Recompiling costs about 10 ms, so it is not needed now.

## Consequences

- **Tests that mock run one at a time on a database.** An edition can have only one child (`ORA-38807`), so two
  tests cannot each have one at the same time.
- **`ENABLE EDITIONS` cannot be undone.** It belongs on a test database only, which is why it is explicit DDL and never
  run by the library on its own.
- **A wrapped body cannot be parsed,** so it cannot be mocked; the library refuses it with a clear error.
- **The schema holds two objects of the library,** the calls table and the `PENITENT_MOCK` package, versioned so that
  an outdated schema is reported before a test fails in an obscure way.
- **IslandSQL and the ANTLR runtime** become dependencies of `penitent-test` only. The core stays free of them.

## Open questions

- **Mocking more than packages.** In legacy schemas, PL/SQL is often coupled to other objects through the database:
  views, standalone functions and procedures, synonyms, triggers, object types. Editions can redefine all of them, so
  the mocks are meant to grow beyond packages, and `ENABLE EDITIONS FOR PACKAGE` in the admin script will widen with
  them. Object types need care: enabling editions for a type that a table uses fails (`ORA-38819`), and forcing it
  leaves the table invalid, as an earlier measurement showed.

- Oracle 21c and 19c, including whether `ADMIN` can enable editions on the Autonomous Database image used for 19c.
- Spy mode: running the original body, as a nested block, when no stub matches.
- Packages in another schema, and how much privilege that asks for.
