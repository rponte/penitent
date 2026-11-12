# Roadmap

The library is in `0.x`: it works and is tested, but the public API may still change between minor versions. The
packages named `internal`, such as `io.github.rponte.penitent.internal`, are never part of the API.

The items below are in three groups: what comes next, what waits for a real use case, and what is left out on purpose.

## Next

### Mocking PL/SQL in tests

`penitent-test` stubs a routine of a package in an integration test, in the style of Mockito, from the interface the
application already has, as [Mocking PL/SQL in tests](testing.md) shows, measured on Oracle 23ai. Still open, as
[architecture decision 0001](adr/0001-mocking-plsql-with-editions.md) details:

- **Oracle 21c and 19c,** including whether the Autonomous Database image used for 19c lets editions be enabled.
- **A stub made after the test used the package,** which now fails the next call once: retrying it in tests, or a
  body that reads its stubs from a table and needs no recompilation.
- **More than packages:** views, standalone routines, synonyms, triggers and object types, which legacy PL/SQL is often
  coupled to; and values beyond scalars: object types, collections and cursors.
- **Spy mode,** running the real code when no stub matches, and **several pools of the same database** sharing the
  mocks.

## On demand

These wait for a real use case: until one shows up, the current behavior stays, and a rejection says so with a clear
error.

### Older Oracle versions

Every release Oracle still supports is covered: Oracle 23ai, 21c and 19c, with every driver from 19 to 23, on Java 17
and 21, as [Driver versions](compatibility.md#driver-versions) and [Database versions](compatibility.md#database-versions)
detail. Oracle 19c, the long-term release most projects run, is measured on Oracle's Autonomous Database Free. Older
releases, such as Oracle 12c, and drivers 18 and before are out of Oracle's support: they would need an image to test
against and a case that justifies keeping them working.

### Metadata cache

Each proxy reads the dictionary on the first call of each method and keeps the result for its whole life, and
`PlsqlPackages.validate` warms it. Still open:

- **Fewer queries:** each method reads its own `ALL_ARGUMENTS`, an N+1 per interface. The current schema and the
  package are looked up again for every method, and the same collection more than once within a method. Reading one
  interface's routines in a single query, and caching the repeated lookups, would cut a scalar interface to a few
  queries in total. `DictionaryQueriesTest` holds today's numbers.
- **Cache shared between proxies:** two interfaces that use the same type read it twice. It cannot be static, because
  of the classloader trap.

### Transactions without Spring

`PlsqlTransactions` runs a block in one transaction, and `PlsqlPackages.using` binds a view of an interface to the
application's connection, sharing its cache, as [Transactions](transactions.md#without-spring-plsqltransactions)
shows. Internally, a call gets its connection from a connection provider, which today is either the `DataSource` or a
bound connection. Still open:

- **A public connection provider,** to hand out and give back connections in ways a `DataSource` makes awkward, such
  as preparing the session before each call (`CLIENT_IDENTIFIER`, an application context for VPD). It needs the
  contract the provider already follows: one connection per call, given back exactly once, and never a commit.
- **`PlsqlTransactions` backed by Spring:** an implementation in `penitent-spring` on top of Spring's transaction
  manager, so that the same block would run with or without Spring. Today an application has one transaction model:
  in a Spring application, Spring's, and `penitent-spring` refuses a block inside a Spring transaction and a Spring
  transaction inside a block. A bridge between the two models would bring back the questions that rule settles, so it
  waits for a concrete case, such as a module that has to run both with and without Spring.
- **Noticing a routine that ends the caller's transaction,** by comparing the transaction before and after the call,
  for instance through `DBMS_TRANSACTION.LOCAL_TRANSACTION_ID`, to warn or to fail.

`TransactionsTest`, `ConnectionBoundViewTest` and `PlsqlTransactionsTest` describe today's behavior, which any of
them has to keep.

### Jakarta EE and JTA

Inside a Jakarta EE container, the `DataSource` hands out the JTA transaction's connection by itself, so `create`
should take part in the transaction without anything else. This is not measured yet. The plan is to measure it
without a full application server, which would be slow and hard to keep working: a JTA transaction manager and a
pool that enlists its connections, Narayana and Agroal, in a plain JUnit test against the same Oracle.

The tests would live in a module of their own, `penitent-jta-tests`, which depends on `penitent-core`, reuses its
Oracle and fixtures through the core's test jar, and is never published to Maven Central. That keeps the JTA
libraries out of the core's test classpath. The scenarios to measure first:

- `create` with the JTA `DataSource`: calls and plain JDBC committed and rolled back together by the transaction
  manager.
- `PlsqlTransactions` inside a JTA transaction, which is expected to fail, since `setAutoCommit` and `commit` are not
  allowed on an enlisted connection. If so, it should refuse with a clear message instead.
- `PlsqlPackages.using` on a connection of the JTA transaction.
- A routine's own `COMMIT` inside an XA transaction, which Oracle is expected to refuse with `ORA-02089`, unlike plain
  JDBC, where it commits everything before it.

### Writing `INDEX BY` and its keys

Currently the library rejects writing an `INDEX BY` and every `INDEX BY VARCHAR2`, and reading an
`INDEX BY PLS_INTEGER` loses the indexes. The cause is the driver, which only carries a list of values and, when
writing, numbers from 0. The rejection stays until a real use case shows up, because lifting it later does not break
existing users of the library.

A path to evaluate, the same one [jOOQ adopted](https://github.com/jOOQ/jOOQ/issues/13957):

- Map to `Map<Integer, V>` and `Map<String, V>`, so that the keys come from the developer. `List<T>` remains
  read-only.
- Generate an anonymous block that receives keys and values in parallel arrays and builds the collection with
  `r(k(i)) := v(i)`. The dictionary already provides the type and the `INDEX_BY` column.
- When reading, walk the collection with `FIRST`/`NEXT` and return keys and values in separate arrays.

Not measured yet: we only tested the block that fixes the index base, with `NUMBER`. Still to measure: sparse keys,
`INDEX BY VARCHAR2`, reading with keys, `IN OUT`, `NULL`, empty collections and composite elements, as well as how
the generated call coexists with `@PlsqlBlock`, converters and error mapping.

### Nested collections in attributes and cursor columns

A collection of collections works as a parameter and a return value, and a cursor column can be an OBJECT. Two
neighboring cases are refused with a clear error, because they go through other code paths that were not extended:

- A collection of collections as an attribute of an OBJECT or RECORD, such as `CELLS MATRIX_T` in `REPORT_T`. The
  attributes go through `TypeBinding`, and only the parameter path recurses into nested collections. No driver limit
  was found; it was not measured.
- A collection as a cursor column, such as `CAST(MULTISET(...) AS NAME_LIST_T)` read into a `List<String>`. The value
  carries the type name, as an OBJECT column does, so it looks feasible. Today the error suggests `TABLE()` or wrapping
  the collection in an OBJECT column.

### Methods of OBJECT types

An OBJECT type is a third container of routines, besides the package and the schema, and today its methods are only
reachable through `@PlsqlBlock`. Two kinds need a design:

- `STATIC` methods, such as `ADDRESS_T.parse(p_text)`, are routines whose container is the type. They could follow
  the same rule as `@PlsqlPackage` and `@PlsqlSchema`, with an annotation that names the type.
- `MEMBER` methods, such as `l_address.format()`, run on an instance, so the call needs the object itself: the Java
  side might pass it as the first parameter, or the record might declare the methods. The generated call would be a
  block that builds the object, calls the method and, for a `MEMBER PROCEDURE` that changes `SELF`, reads it back.

Before designing it, check how jOOQ maps the methods of Oracle UDTs, and how it handles `SELF` in `MEMBER`
procedures.

### Binding by name

Binding by name is opt-in per interface. Deferred until there is a need:

- `@PlsqlParam(omitIfNull = true)`, to omit a `null` parameter and let the `DEFAULT` take effect. For now, one Java
  overload per way of calling solves it, and the SQL of each method stays fixed.
- Making `BY_NAME` the library's default, in a major version, once existing applications have a migration path.
  An application can already choose it for all its interfaces with `PlsqlSettings.withBind`.

### Converters and binding

`PlsqlConverter` remains a pure value conversion, with no connection and no dictionary. Configuration goes in the
constructor, with `PlsqlConverters.of(...)`. The library's conversions that need no context live in one class per
type, in `io.github.rponte.penitent.internal.convert`, which is not part of the API and may change. Whatever needs JDBC, such
as `TIMESTAMPLTZ`, STRUCT and ARRAY, stays in the binding layer.

These steps are deferred until there is a need:

- More ready-made converters, besides enum by code, when a real case shows which ones are worth it.
- The boolean literals of a schema that uses `S`/`N` in `PlsqlSettings`. Until then,
  each point declares its own `@PlsqlBoolean`. A plain `Boolean` ↔ `String` converter does not work, because it
  would also write to `NUMBER`.
- A public binding API, in the style of jOOQ's `Binding`, only when a real case shows up that a converter cannot
  solve. The candidates are `XMLTYPE`, `JSON` and LOBs. It would have to cover direct binding as well as attributes
  and elements, and declare what it writes, so as not to bypass validation against the dictionary.

### Call logging

Deferred until there is a need:

- A way to hide a sensitive parameter even at trace level, through an annotation or configuration.
- The names of an OBJECT's attributes in the values sent, which are currently shown only in the type's order.
- A public listener, in the style of jOOQ's `ExecuteListener`, for metrics and tracing.

### Spring and Spring Boot integration

The integration covers registration with `@EnablePlsqlPackages`, a `PlsqlSettings` bean, validation at startup
and the Spring Boot starter. What remains:

- **More properties in the starter,** such as `penitent.considerNested` or a `DataSource` by name, if an application needs
  them. Today these go through `@EnablePlsqlPackages`.

### Immutable classes built through a constructor

A composite maps to a `record`, built through its canonical constructor, or to a JavaBean, built through a
no-argument constructor and its setters. An immutable class that is neither, with `final` fields and a constructor
that takes them all, as many domain models and Lombok's `@Value` produce, would fit the same mapping. The open
question is how to match each constructor parameter to an attribute: by the parameter names, when compiled with
`-parameters`, by an annotation such as `@ConstructorProperties`, or both.

### Generating Java code from the schema

Today the developer writes each interface and each `record` by hand, and validation at startup checks them against
the dictionary. The library already reads everything needed to write them: routines, parameters, OBJECT and RECORD
types, collections and `%ROWTYPE`. A generator, most likely a Maven plugin, could turn a package into its interface
and records, as a starting point to keep or as a build step. Still open: whether the code is generated once and then
owned by the developer, or on every build; how to reach the database at build time; and how naming choices, such as
`camelCase` and the record names, are made configurable.

### Observability with OpenTelemetry

Each call is a natural span: routine, interface and method, time spent in the dictionary and in the call, and the
outcome, including the Oracle error code. The cleanest path is the public listener in
[Call logging](#call-logging), with an OpenTelemetry module built on it, outside the core, as Spring is. Until then,
the call log and the instrumentation of the driver or the pool already cover the essentials.

### Asynchronous calls

A method of an interface could return `CompletableFuture<T>`, or `Future<T>`, and run the call on an executor, so
that the caller does not wait for the database. A connection serves one thread at a time, so such a call takes its
own connection and runs outside any transaction, which the design has to make explicit, along with the executor to
use, virtual threads included, and how errors arrive through the future.

## By design

These are decisions, not gaps. Changing one would need a strong case.

- **No framework in the core.** `penitent-core` depends only on the JDK and the driver. Spring, Spring Boot and any
  future integration live in their own modules.
- **A minimal transaction, not a transaction manager.** `PlsqlTransactions` is one connection, one thread and explicit
  participation through `tx.using`. Propagation, nested transactions, timeouts, automatic retries, synchronization
  callbacks, annotations and JTA are left to Spring and Jakarta EE.
- **No static configuration.** `PlsqlSettings` is an object passed to each `create`, so two contexts or two tests can
  use different settings, and a proxy never changes behavior after it is created.
- **No silent fallback.** When the Java side and the dictionary disagree, the library fails with a message that says
  what it looked up and what exists, instead of guessing.
- **No metadata expiry.** The cache never expires, so with `BY_POSITION` a new parameter in the middle of the list
  binds the values wrongly, without an error, until a restart. A package spec is a contract and rarely changes in
  production, so restarting the application is the policy.
