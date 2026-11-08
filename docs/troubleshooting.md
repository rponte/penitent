# Troubleshooting

The problems people run into most, by the message or the symptom you see. Every message on this page is what
Penitent prints, word for word.

## The routine or package is not found

<!-- snippet: package-not-found -->
```text
package SHOP.ORDER_PKG does not exist or is not visible to this user
```

<!-- snippet: routine-not-found -->
```text
routine SHOP.ORDERS_PKG.CALCULATE_FRIEGHT does not exist. Available: APPLY_DISCOUNT, CALCULATE_FREIGHT, CANCEL_ORDER, COUNT_ORDERS, DESCRIBE, DESCRIBE, ORDER_STATUS, PLACE_ORDER
```

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
cancelOrder declares a return value, but SHOP.ORDERS_PKG.CANCEL_ORDER is a procedure and returns nothing; use void
```

<!-- snippet: wrong-arity -->
```text
calculateFreight declares 1 parameter(s), but SHOP.ORDERS_PKG.CALCULATE_FREIGHT expects 2: [P_ORIGIN VARCHAR2, P_DESTINATION VARCHAR2]
```

<!-- snippet: out-without-plsqlout -->
```text
placeOrder -> SHOP.ORDERS_PKG.PLACE_ORDER: P_ORDER_ID is OUT; declare PlsqlOut<T> in this position, not Long
```

The message says what the database has. A procedure maps to a `void` method, and an `OUT` or `IN OUT` parameter to
a `PlsqlOut<T>`. Only `DEFAULT` parameters can be left out: at the end of the list, or anywhere when binding
[by name](routines.md#binding-by-name).

To find every mismatch of an interface at once, instead of one call at a time, run
[`PlsqlPackages.validate`](validation.md) in a test.

## Parameter names are missing

<!-- snippet: parameters-flag -->
```text
upper: parameter names are not in the bytecode (compile with -parameters) or use @PlsqlParam on each one
```

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
countOrders: parameter customerId looks for P_CUSTOMER, which SHOP.ORDERS_PKG.COUNT_ORDERS does not have. Parameters: [P_CUSTOMER_ID NUMBER, P_STATUS VARCHAR2, P_MIN_TOTAL NUMBER]. Use @PlsqlParam with the Oracle name.
```

- **A Java name that does not follow the routine's:** there is no prefix convention, so `customerId` looks for
  `CUSTOMER_ID`, not `P_CUSTOMER_ID`. Use `@PlsqlParam("P_CUSTOMER_ID")`.
- **A global `BY_NAME`**, in [Settings](settings.md) or `penitent.bind`, also applies to the interfaces written for
  binding by position. Give those `bind = PlsqlBind.BY_POSITION`, or add `@PlsqlParam` to their parameters.

## A value does not convert

<!-- snippet: null-primitive -->
```text
roundToCents -> SHOP.ROUND_TO_CENTS: NULL does not fit in long; declare Long
```

<!-- snippet: boolean-literal -->
```text
vipFlag -> SHOP.CUSTOMERS_PKG.VIP_FLAG: cannot convert 'S' to boolean: expected 'Y' or 'N'. Use @PlsqlBoolean to declare other literals.
```

<!-- snippet: local-on-tstz -->
```text
deliveryDeadline -> SHOP.CUSTOMERS_PKG.DELIVERY_DEADLINE: P_ORDERED_AT is TIMESTAMP WITH TIME ZONE, and LocalDateTime has no time zone; declare OffsetDateTime, ZonedDateTime or Instant
```

Penitent refuses a conversion that would lose information: a `NULL` turned into `0`, an unknown flag turned into
`false`, a time zone dropped. Declare a type that can hold the value, as each message suggests. See
[Types](types.md).

## A composite is missing an attribute

<!-- snippet: missing-attribute -->
```text
Address does not cover attribute(s) of POSTAL_ADDRESS_T: [ZIP_CODE VARCHAR2]. Available properties: [STREET, CITY]. Use @PlsqlAttr to declare the name.
```

Every Oracle attribute needs a Java property, matched by name: `zipCode` is `ZIP_CODE`. Add the property, or map a
different name with `@PlsqlAttr("ZIP_CODE")`. Extra Java properties are fine.

## The first call is slow

The first call of each method reads the dictionary, with about 5 queries for a scalar routine and up to about 16
with nested composites. Later calls use the cache. To pay that cost at startup instead of on the first request, run
[validation](validation.md) at startup. See [How Penitent works](concepts.md#what-happens-in-a-call).

## Connections run out

Each call takes a connection from the `DataSource` and gives it back. A `DataSource` that opens a new physical
connection each time, such as a plain `OracleDataSource`, can exhaust the Oracle listener under load, with
`ORA-12516`. Use a connection pool, such as HikariCP, which Spring Boot already configures.

## With Spring

- **A duplicate key is not a `DuplicateKeyException`.** An error raised inside the routine, a violated constraint
  included, arrives as `PlsqlRoutineRaisedException`: check `getCode() == 1`. Only infrastructure failures become a
  `DataAccessException`. See [Spring and Spring Boot](spring.md#transactions-and-exceptions).
- **No bean of your interface.** The starter registers the interfaces in the application's packages, from the
  `@SpringBootApplication` class down; `scanBasePackages` does not count. It also backs off when there are several
  `DataSource`s and none is primary. Use `@EnablePlsqlPackages` to name the packages or the `DataSource`.
- **Two beans of the same interface:** an interface created by hand inside the scanned packages is registered a
  second time. Move it out, or let the scan create it.
