# Validation

Find every mismatch between your interfaces and the database at once, in a test or at startup, instead of one call
at a time. Penitent checks each method on its first call anyway, but that is late: a typo in a method that runs once
a month only fails once a month. `PlsqlPackages.validate` checks every method of an interface at once, without calling
any routine:

<!-- snippet: validation/validate -->
```java
Orders orders = PlsqlPackages.create(Orders.class, dataSource);
PlsqlPackages.validate(orders);
```

If something does not match, a single `PlsqlMappingException` lists every method that is wrong, one per line, so
you do not fix one only to find the next:

<!-- snippet: validate-message -->
```text
2 methods of com.example.shop.Orders do not match the database:
  - cancelOrder(long): the method declares a return value, but SHOP.ORDERS_PKG.CANCEL_ORDER is a procedure and returns nothing; use void
  - placeOrder(long, BigDecimal, Long): P_ORDER_ID of SHOP.ORDERS_PKG.PLACE_ORDER is OUT; declare PlsqlOut<T> in this position, not Long
```

## Where to call it

- **In a test,** against a database with the real packages. When someone changes a package, the test fails in the
  build instead of a call failing in production:

  <!-- snippet: validation/test -->
  ```java
  @Test
  void interfaces_match_the_database() {
      PlsqlPackages.validate(PlsqlPackages.create(Orders.class, dataSource));
      PlsqlPackages.validate(PlsqlPackages.create(Customers.class, dataSource));
  }
  ```

- **At startup,** so that an application deployed against the wrong schema does not start. With Spring Boot:

  <!-- snippet: untested -->
  ```yaml
  penitent:
    validation:
      enabled: true
  ```

  With `@EnablePlsqlPackages`, `validateOnStartup = true`. See [Spring and Spring Boot](spring.md).

## What it checks

- **What a call checks before running:** the routine exists, procedure or function, the number of parameters, their
  types and directions, and the composite types the method declares, nested ones included.
- **For an anonymous block,** which has no signature in the dictionary, only its placeholders, against the method's
  parameters.
- **What depends on a value stays for the call:** a cursor's columns, which only exist when the query runs, and a
  composite sent as a subclass of the declared type.
- **A database failure is not a mapping error:** it stops the validation, and arrives translated as on a call.

<div class="admonition warning" markdown>
<p class="admonition-title">What validation cannot see</p>

A method that still fits the routine passes, even if the routine changed in a way that matters: binding by position,
two parameters of the same type that swapped places, or a parameter with `DEFAULT` inserted in the middle. The call
then runs with values in other parameters, with no error. [By position or by name](routines.md#by-position-or-by-name)
shows each case, and how binding by name avoids them.

</div>

## What it costs

It uses one connection, and leaves what it read cached, so the first calls afterwards do not read the dictionary
again. An interface costs a few queries to the dictionary, whatever its number of methods, and a few more for each
composite type: 4 queries for an interface of 38 methods, 3 of them for the routines. A call pays this once, on its first run, and validation pays it
for every method at once. The count is covered by a test, and reducing it further is on the
[roadmap](roadmap.md#metadata-cache).
