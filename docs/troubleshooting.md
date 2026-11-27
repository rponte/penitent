# Troubleshooting

The problems people run into most, by the message or the symptom you see. Every message on this page is what
Penitent prints, word for word.

A mapping error always starts with the method and the types of its parameters, as in `countOrders(long, String):`,
so you can tell overloads apart, and then says what was looked up and what exists.

| You see | Go to |
|---|---|
| `does not exist or is not visible`, `does not exist. Available: ...` | [The routine or package is not found](#the-routine-or-package-is-not-found) |
| `the method declares ...`, `is OUT; declare PlsqlOut<T>` | [The method does not match the routine](#the-method-does-not-match-the-routine) |
| `parameter names are not in the bytecode` | [Parameter names are missing](#parameter-names-are-missing) |
| `parameter ... looks for ..., which ... does not have` | [Binding by name fails](#binding-by-name-fails-for-an-interface-that-used-to-work) |
| `cannot convert`, `does not fit in`, `has no time zone` | [A value does not convert](#a-value-does-not-convert) |
| `does not cover attribute(s)` | [A composite is missing an attribute](#a-composite-is-missing-an-attribute) |
| `changed since this proxy described it`, `ORA-04068`, `ORA-17049` | [A call fails after a deploy](#a-call-fails-after-a-deploy-while-it-worked-before) |
| a wrong result after a deploy, with no error | [A call returns a wrong value after a deploy](#a-call-returns-a-wrong-value-after-a-deploy-with-no-error) |
| the first call takes long | [The first call is slow](#the-first-call-is-slow) |
| `ORA-12516` under load | [Connections run out](#connections-run-out) |

## The routine or package is not found

<!-- snippet: package-not-found -->
```text
orderStatus(long): package SHOP.ORDER_PKG does not exist or is not visible to this user
```

<!-- snippet: routine-not-found -->
```text
calculateFrieght(String, String): routine SHOP.ORDERS_PKG.CALCULATE_FRIEGHT does not exist. Available: APPLY_DISCOUNT, CALCULATE_FREIGHT, CANCEL_ORDER, COUNT_ORDERS, DESCRIBE, ORDER_STATUS, PLACE_ORDER
```

**What to do:** check the name, the schema and the grant, in that order.

- **A typo** in `@PlsqlPackage`, `@PlsqlRoutine` or the method name. The second message lists what the package does
  have; remember that `calculateFreight` looks for `CALCULATE_FREIGHT`.
- **Another schema:** Penitent looks in the session's schema unless you say otherwise. Use
  `@PlsqlPackage(value = "ORDERS_PKG", schema = "SALES")`, or name a synonym the session can see.
- **No grant:** the dictionary only shows what the session can see. A package in another schema needs
  `GRANT EXECUTE ON sales.orders_pkg TO <your user>`.
- **A name created in double quotes,** such as `"ordersPkg"`, must be written with the quotes:
  `@PlsqlPackage("\"ordersPkg\"")`. See [names](routines.md#other-schemas-synonyms-and-quoted-names).

## The method does not match the routine

<!-- snippet: procedure-as-function -->
```text
cancelOrder(long): the method declares a return value, but SHOP.ORDERS_PKG.CANCEL_ORDER is a procedure and returns nothing; use void
```

<!-- snippet: wrong-arity -->
```text
calculateFreight(String): the method declares 1 parameter, but SHOP.ORDERS_PKG.CALCULATE_FREIGHT expects 2: [P_ORIGIN VARCHAR2, P_DESTINATION VARCHAR2]
```

<!-- snippet: out-without-plsqlout -->
```text
placeOrder(long, BigDecimal, Long): P_ORDER_ID of SHOP.ORDERS_PKG.PLACE_ORDER is OUT; declare PlsqlOut<T> in this position, not Long
```

**What to do:** change the method to what the message says the database has. A procedure maps to a `void` method, and an `OUT` or `IN OUT` parameter to
a `PlsqlOut<T>`. Only `DEFAULT` parameters can be left out: at the end of the list, or anywhere when binding
[by name](routines.md#binding-by-name).

To find every mismatch of an interface at once, instead of one call at a time, run
[`PlsqlPackages.validate`](validation.md) in a test.

## Parameter names are missing

<!-- snippet: parameters-flag -->
```text
upper(String): parameter names are not in the bytecode (compile with -parameters) or use @PlsqlParam on each one
```

**What to do:** compile with `-parameters`, or give each parameter a `@PlsqlParam`.

An [anonymous block](anonymous-blocks.md) binds `:name` by the Java parameter's name, and so does binding by name
without `@PlsqlParam`. Java only keeps parameter names in the class file when you compile with `-parameters`:

<!-- snippet: untested -->
```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <parameters>true</parameters>
    </configuration>
</plugin>
```

The Spring Boot parent already turns it on. With Gradle, add `options.compilerArgs << '-parameters'` to the
`JavaCompile` tasks.

## Binding by name fails for an interface that used to work

<!-- snippet: unknown-name -->
```text
countOrders(long): parameter customerId looks for P_CUSTOMER, which SHOP.ORDERS_PKG.COUNT_ORDERS does not have. Parameters: [P_CUSTOMER_ID NUMBER, P_STATUS VARCHAR2, P_MIN_TOTAL NUMBER]. Use @PlsqlParam with the Oracle name.
```

**What to do:** give the parameter the Oracle name with `@PlsqlParam`, or bind the interface by position.

- **A Java name that does not follow the routine's:** there is no prefix convention, so `customerId` looks for
  `CUSTOMER_ID`, not `P_CUSTOMER_ID`. Use `@PlsqlParam("P_CUSTOMER_ID")`.
- **A global `BY_NAME`**, in [Settings](settings.md) or `penitent.bind`, also applies to the interfaces written for
  binding by position. Give those `bind = PlsqlBind.BY_POSITION`, or add `@PlsqlParam` to their parameters.

## A value does not convert

<!-- snippet: null-primitive -->
```text
roundToCents(BigDecimal): the return value of SHOP.ROUND_TO_CENTS: NULL does not fit in long; declare Long
```

<!-- snippet: boolean-literal -->
```text
vipFlag(long): the return value of SHOP.CUSTOMERS_PKG.VIP_FLAG: cannot convert 'S' to boolean: expected 'Y' or 'N'. Use @PlsqlBoolean to declare other literals.
```

<!-- snippet: local-on-tstz -->
```text
deliveryDeadline(LocalDateTime): P_ORDERED_AT of SHOP.CUSTOMERS_PKG.DELIVERY_DEADLINE is TIMESTAMP WITH TIME ZONE, and LocalDateTime has no time zone; declare OffsetDateTime, ZonedDateTime or Instant
```

**What to do:** declare a type that can hold the value, as each message suggests.

Penitent refuses a conversion that would lose information: a `NULL` turned into `0`, an unknown flag turned into
`false`, a time zone dropped. See [Types](types.md).

## A composite is missing an attribute

<!-- snippet: missing-attribute -->
```text
normalizeAddress(Address): Address does not cover attribute(s) of POSTAL_ADDRESS_T: [ZIP_CODE VARCHAR2]. Available properties: [STREET, CITY]. Use @PlsqlAttr to declare the name.
```

**What to do:** add the property, or map a different name with `@PlsqlAttr("ZIP_CODE")`.

Every Oracle attribute needs a Java property, matched by name: `zipCode` is `ZIP_CODE`. Extra Java properties are
fine.

## A call fails after a deploy, while it worked before

**What to do:** read the message: it says what changed, and whether the next call works by itself.

The PL/SQL changed under the running application: Penitent described the routine before the change, the driver kept
the old types in each connection, or the session had state in a package that was compiled again.

- **`ORA-04068: existing state of packages has been discarded`:** a package with state was compiled again. Each
  connection fails once, and the next call works. Penitent does not repeat the call, since the package's state was
  lost.
- **`... changed since this proxy described it: ...`:** the routine, or a type it takes, changed. Penitent read it
  again, and the rest of the message says what no longer fits the method, as a new proxy would. Update the method or
  the Java type.
- **`... changed since this proxy described it, and was described again: the call did not run, and the next one uses
  the new signature`:** the routine changed, and the method still fits it, as when a parameter is renamed and the
  interface binds by position. Nothing ran, so nothing is to undo, and the next call works: the message shows the
  parameters before and now. Oracle's `PLS-00306` follows, and stays the exception's cause and code. Whether to call
  again is the application's choice: see [Errors](errors.md#when-the-driver-fails-itself).
- **`PLS-00306` alone, the same on every call:** the routine raises it itself, from its own dynamic PL/SQL, and its
  signature did not change. Penitent checks that by reading the routine again, at most once a second, and leaves the
  error as Oracle says it.
- **`... is INVALID, and what it takes and returns cannot be read until it compiles`:** a migration left the package,
  or the standalone routine, invalid, and a compilation of it failed, as when a type it uses was dropped and created
  again. Oracle then lists none of its routines, so Penitent cannot read them, and a proxy that cannot read them does
  not call them, which is what would make Oracle compile them. Compile it, as the message says, and read its errors
  in `ALL_ERRORS`; better, have each migration compile what it left invalid, as
  `DBMS_UTILITY.COMPILE_SCHEMA(schema => USER, compile_all => FALSE)` does in a Flyway `afterMigrate` callback, as in
  [A compilation that fails](deploying.md#a-compilation-that-fails).
  Penitent does not compile it itself: it does not change the database. On 19c, Penitent's lookup of the name
  compiles it already, and the call works.
- **`ORA-21700: object does not exist or is marked for deletion`, with "A type this call uses was most likely
  replaced":** a type was replaced with `CREATE OR REPLACE TYPE ... FORCE` since the proxy described it. The call
  fails once; the next one reads the types again and works.
- **`ORA-17049`, with "The driver keeps, in each connection, a type as it was when the connection first used it":**
  a type changed, and the connection still has the old one. Renew the pool's connections, or restart the
  application.
- **`ORA-04063: package body ... has errors`, or `ORA-04065`, on every call, after a deploy:** the package's spec
  changed, and its body was not compiled again yet. The calls fail until it is: deploy the spec and the body together,
  as in [The spec, and the DDL itself](deploying.md#the-spec-and-the-ddl-itself).
- **Calls that wait, during a deploy:** the DDL that replaces a package waits for the calls in progress, and the calls
  after it wait behind it. See [The spec, and the DDL itself](deploying.md#the-spec-and-the-ddl-itself).
- **`ORA-03138`, `ORA-03113` or "No more data to read from socket"** on a call with a `%ROWTYPE`: the database closed
  the connection, after the table changed. The pool replaces it; update the Java type if a column was added. **If new
  connections fail the same way**, a session read the `%ROWTYPE` again past a column added, and the database keeps it
  broken for every session, until a DBA runs `ALTER SYSTEM FLUSH SHARED_POOL`, or, on 19c, compiles the package: see
  [A column added to the table of a `%ROWTYPE`](deploying.md#a-column-added-to-the-table-of-a-rowtype).

What each change does, and how to avoid the errors, is in
[Changing PL/SQL while the application runs](deploying.md).

## A call returns a wrong value after a deploy, with no error

**What to do:** bind the interface by name, `@PlsqlPackage(value = "...", bind = PlsqlBind.BY_NAME)`, and update the
method to the routine's new signature.

Binding by position, the values go to the routine's parameters in its order. If a deploy inserted a parameter with
`DEFAULT` in the middle, or swapped two parameters of the same type, a proxy that describes the routine after the
change, as after a restart, sends the values to other parameters, and the call runs. Validation does not catch it
either, since the method still fits. While the application keeps running, the calls name the parameters they were
written for, so this only shows after a restart. [By position or by name](routines.md#by-position-or-by-name) shows
each case.

## The first call is slow

**What to do:** run [validation](validation.md) at startup, to pay that cost before the first request.

The first call of each method reads the dictionary, with 2 queries for a scalar routine and up to about 6 with
nested composites, each a round trip to the database. Later calls use the cache. See [How Penitent works](concepts.md#what-happens-in-a-call).

## Connections run out

**What to do:** use a connection pool, such as HikariCP, which Spring Boot already configures.

Each call takes a connection from the `DataSource` and gives it back. A `DataSource` that opens a new physical
connection each time, such as a plain `OracleDataSource`, can exhaust the Oracle listener under load, with
`ORA-12516`.

## With Spring

- **A duplicate key is not a `DuplicateKeyException`.** An error raised inside the routine, a violated constraint
  included, arrives as `PlsqlRoutineRaisedException`: check `getCode() == 1`. Only infrastructure failures become a
  `DataAccessException`. See [Spring and Spring Boot](spring.md#transactions-and-exceptions).
- **No bean of your interface.** The starter registers the interfaces in the application's packages, from the
  `@SpringBootApplication` class down; `scanBasePackages` does not count. It also backs off when there are several
  `DataSource`s and none is primary. Use `@EnablePlsqlPackages` to name the packages or the `DataSource`.
- **Two beans of the same interface:** an interface created by hand inside the scanned packages is registered a
  second time. Move it out, or let the scan create it.
