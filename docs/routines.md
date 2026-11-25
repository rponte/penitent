# Calling routines

Each method of an interface calls one PL/SQL function or procedure. This page covers how a method finds its routine
and how the arguments get there: `DEFAULT` values, overloads, `OUT` and `IN OUT` parameters, binding by name, and
routines that live outside a package.

Here, "routine" always means the PL/SQL side, and "method" the Java side: see
[How Penitent works](concepts.md#routine). New to Penitent? Start with [Getting started](getting-started.md), which
builds the package used here.

## The package on this page

The orders package from Getting started, with a few more routines:

<!-- snippet: fixture -->
```sql
CREATE OR REPLACE PACKAGE orders_pkg AS
    FUNCTION  calculate_freight(p_origin VARCHAR2, p_destination VARCHAR2) RETURN NUMBER;

    PROCEDURE place_order(p_customer_id NUMBER, p_total NUMBER, p_order_id OUT NUMBER);
    PROCEDURE cancel_order(p_order_id NUMBER);
    FUNCTION  order_status(p_order_id NUMBER) RETURN VARCHAR2;

    PROCEDURE apply_discount(p_total IN OUT NUMBER, p_percent NUMBER DEFAULT 10);

    FUNCTION  count_orders(p_customer_id NUMBER, p_status VARCHAR2 DEFAULT NULL, p_min_total NUMBER DEFAULT NULL)
        RETURN NUMBER;

    FUNCTION  describe(p_order_id NUMBER) RETURN VARCHAR2;
    FUNCTION  describe(p_status VARCHAR2) RETURN VARCHAR2;
END orders_pkg;
```

## Functions and procedures

A method that returns a value calls a function, and a `void` method calls a procedure. The method name in camelCase
is the routine name in SNAKE_CASE, so `calculateFreight` calls `CALCULATE_FREIGHT`. When your Java name is different,
`@PlsqlRoutine` gives the routine's name:

<!-- snippet: routines/interface -->
```java
@PlsqlPackage("ORDERS_PKG")
public interface Orders {

    BigDecimal calculateFreight(String origin, String destination);   // a function

    void cancelOrder(long orderId);                                   // a procedure

    @PlsqlRoutine("ORDER_STATUS")
    String statusOf(long orderId);
}
```

<!-- snippet: routines/calls -->
```java
Orders orders = PlsqlPackages.create(Orders.class, dataSource);

orders.calculateFreight("SP", "SP");   // 10
orders.cancelOrder(orderId);
orders.statusOf(orderId);              // "CANCELLED"
```

## Parameters by position

By default, the first Java parameter goes to the first Oracle parameter, the second to the second, and so on. The
types are not declared anywhere: Penitent reads them from the dictionary and checks that each Java type fits. The
other way, by the parameters' names, and when to choose it, is in [By position or by name](#by-position-or-by-name).

### `DEFAULT` values

A parameter with `DEFAULT` can be left out, as long as every parameter after it is left out too. Declare one Java
method for each way you call the routine; they can share the name:

<!-- snippet: routines/default-methods -->
```java
long countOrders(long customerId);                  // every order of the customer
long countOrders(long customerId, String status);   // only those with this status
```

<!-- snippet: routines/default-calls -->
```java
orders.countOrders(102);           // 2
orders.countOrders(102, "OPEN");   // 1
```

To leave out a parameter in the middle of the list, such as `p_status` while passing `p_min_total`, bind
[by name](#binding-by-name).

> **`null` is not "use the default".** A `null` argument is sent as `NULL`, even to a parameter that has a `DEFAULT`:
> Oracle only applies the default when the parameter is left out. This is how PL/SQL works, and Penitent does not
> change it.

### Overloaded routines

When the package has more than one routine with the same name, the Java signature picks the version:

<!-- snippet: routines/overload-methods -->
```java
String describe(long orderId);    // describe(p_order_id NUMBER)
String describe(String status);   // describe(p_status VARCHAR2)
```

<!-- snippet: routines/overload-calls -->
```java
orders.describe(7);        // "order 7"
orders.describe("OPEN");   // "orders with status OPEN"
```

If more than one version fits and none of them is more specific in every parameter, the call fails with a
`PlsqlMappingException` instead of picking one. For two versions that take different OBJECT types,
[`@PlsqlType`](types.md#composite-types) breaks the tie.

## `OUT` and `IN OUT` parameters

An `OUT` parameter is declared as `PlsqlOut<T>`, in the same position it has in the routine. Pass an empty one, and
read it after the call:

<!-- snippet: routines/out-method -->
```java
void placeOrder(long customerId, BigDecimal total, PlsqlOut<Long> orderId);
```

<!-- snippet: routines/out-call -->
```java
PlsqlOut<Long> orderId = new PlsqlOut<>();
orders.placeOrder(103, new BigDecimal("100.00"), orderId);
orderId.get();   // the new order's id
```

An `IN OUT` parameter is a `PlsqlOut<T>` too, created with `PlsqlOut.of(value)`: the routine receives the value and
replaces it. Here `apply_discount` also has a `DEFAULT`, so there are two methods again:

<!-- snippet: routines/in-out-methods -->
```java
void applyDiscount(PlsqlOut<BigDecimal> total);                 // p_percent takes its DEFAULT, 10
void applyDiscount(PlsqlOut<BigDecimal> total, int percent);
```

<!-- snippet: routines/in-out-calls -->
```java
PlsqlOut<BigDecimal> total = PlsqlOut.of(new BigDecimal("200"));

orders.applyDiscount(total);       // total.get() is 180
orders.applyDiscount(total, 50);   // total.get() is 90
```

A few things to know:

- **`T` is any type a function could return:** a scalar, a [composite](types.md#composite-types), or a `List<E>` for a
  collection or a cursor.
- **A function can have `OUT` parameters too.** The return value comes back as usual, and the `OUT` values in their
  `PlsqlOut`.
- **If the call fails, a `PlsqlOut` keeps the value it had before.**
- **The direction must match.** An `OUT` parameter declared as a plain type, or a `PlsqlOut` on an `IN` parameter, is
  a `PlsqlMappingException`. Penitent cannot tell which side is wrong, so it does not guess.

## By position or by name

Penitent matches each Java parameter to an Oracle parameter in one of two ways, for a whole interface:

| | By position, the default | By name, `bind = PlsqlBind.BY_NAME` |
|---|---|---|
| **The Java method declares** | the parameters in the routine's order | parameters named as the routine's, in any order |
| **Java names** | free: `customerId` can stand for `PI_CUSTOMER_ID` | the Oracle name in SNAKE_CASE, or `@PlsqlParam("PI_CUSTOMER_ID")` |
| **Compiling with `-parameters`** | not needed | needed, unless every parameter has `@PlsqlParam` |
| **Leaving out a `DEFAULT` parameter** | only at the end of the list | anywhere |
| **The routine renames a parameter** | keeps working | fails, saying which name is missing, until the Java is updated |
| **The routine changes the order of its parameters** | the values can go to the wrong parameters, with no error: see below | keeps working |

**Choose by position** when the Java interface should not depend on the database's parameter names: a package
whose names follow a convention of their own, with prefixes or a letter for the type, or one with many parameters.
It is the shortest interface to write, and it reads like the package spec.

**Choose by name** when the package is maintained by someone else, or changes often: then the order is the part
you cannot control. Oracle recommends named notation in that case, "when you invoke a subprogram defined or
maintained by someone else" ([PL/SQL Language Reference, Subprogram Parameters](https://docs.oracle.com/en/database/oracle/oracle-database/23/lnpls/subprogram-parameters.html)).

### What can go wrong by position

Each example starts from this function, called as `f(10, 3)`, which returns `7`, with a method
`BigDecimal f(BigDecimal pA, BigDecimal pB)`, and shows what the next call returns after a deploy changes the
signature and the application restarts:

<!-- snippet: untested -->
```sql
FUNCTION f(p_a NUMBER, p_b NUMBER) RETURN NUMBER;   -- RETURN p_a - p_b
```

| The function becomes | By position | By name |
|---|---|---|
| `f(p_a NUMBER, p_new NUMBER DEFAULT 100, p_b NUMBER DEFAULT 0)`: a parameter inserted in the middle | **`10`, with no error:** `3` goes to `p_new`, and `p_b` keeps its `DEFAULT` | `7` |
| `f(p_a NUMBER, p_new VARCHAR2 DEFAULT 'x', p_b NUMBER DEFAULT 0)`: the same, of another type | **`10`, with no error:** Oracle converts `3` to the text `'3'` | `7` |
| `f(p_b NUMBER, p_a NUMBER)`: two parameters of the same type that swap places | **`-7`, with no error** | `7` |
| `f(p_a NUMBER, p_c NUMBER)`: a parameter renamed | `7` | fails: `parameter pB looks for P_B, which … does not have` |
| `f(p_a NUMBER, p_b NUMBER, p_c NUMBER DEFAULT 1)`: a parameter added at the end, with `DEFAULT` | `7` | `7` |
| `f(p_a NUMBER, p_b NUMBER, p_c NUMBER)`: a parameter added without `DEFAULT` | fails: the method declares 2 parameters, the routine expects 3 | fails, the same |

Neither validation at startup nor a restart catches the first three: the method still fits the new signature, so the
call runs. Only binding by name, or updating the method with the package, does.

<div class="admonition note" markdown>
<p class="admonition-title">While the application runs, position is protected too</p>

A proxy matches each position once, when it first describes the routine, and every call names the parameters that
were there: `F(P_A => ?, P_B => ?)`. So a deploy made while the application runs, before a restart, cannot shift
the values: the first three rows return `7`, and a renamed parameter fails one call safely, after which the routine
is described again. [Changing PL/SQL while the application runs](deploying.md) has the details.

</div>

## Binding by name

You cannot leave out a `DEFAULT` parameter in the middle of the list by position, and the order of the parameters is
the part of a package that can change under you without an error, as above.

With `bind = PlsqlBind.BY_NAME`, each Java parameter goes to the Oracle parameter with the same name, and the call is
sent as `COUNT_ORDERS(P_CUSTOMER_ID => ?, P_MIN_TOTAL => ?)`:

<!-- snippet: routines/by-name-interface -->
```java
@PlsqlPackage(value = "ORDERS_PKG", bind = PlsqlBind.BY_NAME)
public interface OrderSearch {

    long countOrders(@PlsqlParam("P_CUSTOMER_ID") long customerId,
                     @PlsqlParam("P_MIN_TOTAL") BigDecimal minTotal);
}
```

<!-- snippet: routines/by-name-call -->
```java
search.countOrders(104, new BigDecimal("100"));   // p_status is left out and takes its DEFAULT
```

- **The name** is the one in `@PlsqlParam` or, without it, the Java parameter's name in SNAKE_CASE: `customerId`
  looks for `CUSTOMER_ID`. There is no prefix convention, such as adding `P_`, because each project has its own.
  Relying on the Java name requires compiling with `-parameters`, which the Spring Boot parent already turns on.
- **Java order does not matter**, and a `DEFAULT` parameter can be left out from anywhere in the list.
- **Overloads** are picked among the versions whose parameter names match, so two versions that differ only in a
  name can be told apart.
- **For every interface:** instead of repeating `bind` on each one, set it once in [Settings](settings.md) or, with
  Spring Boot, with `penitent.bind: BY_NAME`. An interface that declares `bind` keeps its own.

## Routines outside a package

A function or procedure can live directly in a schema, outside any package. A Java method cannot live outside a
type, so the interface stands for the schema, with `@PlsqlSchema`:

<!-- snippet: fixture -->
```sql
CREATE FUNCTION round_to_cents(p_value NUMBER) RETURN NUMBER IS
BEGIN
    RETURN ROUND(p_value, 2);
END;
```

<!-- snippet: routines/schema-interface -->
```java
@PlsqlSchema                        // the session's schema; or @PlsqlSchema("FINANCE")
public interface Money {

    BigDecimal roundToCents(BigDecimal value);
}
```

<!-- snippet: routines/schema-call -->
```java
money.roundToCents(new BigDecimal("10.456"));   // 10.46
```

Everything on this page works the same way here: `DEFAULT`, `OUT`, overloads, synonyms and binding by name. An
interface declares `@PlsqlPackage` or `@PlsqlSchema`, never both, and one made only of
[`@PlsqlBlock`](anonymous-blocks.md) methods needs neither. `@PlsqlPackage("")` is an error that points to
`@PlsqlSchema`, so a package name erased by mistake does not quietly switch to the schema's routines.

## Other schemas, synonyms and quoted names

- **Another schema:** `@PlsqlPackage(value = "ORDERS_PKG", schema = "SALES")`.
- **Synonyms:** name the synonym your application already uses. Private, public and chained synonyms are resolved;
  a synonym that points through a database link is not.

### Upper case, lower case and quotes

You do not have to write a name in upper case. Names follow the rules of SQL and PL/SQL: without double quotes, a
name is read in upper case, as Oracle reads `CREATE PACKAGE orders_pkg`; in double quotes, it keeps its case exactly,
for an object created with quotes:

| You write | Penitent looks for | When to use it |
|---|---|---|
| `@PlsqlPackage("ORDERS_PKG")` | `ORDERS_PKG` | the usual case |
| `@PlsqlPackage("orders_pkg")` or `@PlsqlPackage("Orders_Pkg")` | `ORDERS_PKG` | the same package: without quotes, case does not matter |
| `@PlsqlPackage("\"ordersPkg\"")` | `ordersPkg`, exactly | a package created as `CREATE PACKAGE "ordersPkg"`; Penitent quotes it in the call |

So most schemas need no quotes at all: they are only for an object whose name was created in quotes, in mixed or
lower case, or with a character such as a space or a dot. The same rule applies to `schema`, `@PlsqlRoutine`,
`@PlsqlParam`, `@PlsqlType` and `resultType`. A method without `@PlsqlRoutine` takes its routine name from the
method, in upper case: `calculateFreight` calls `CALCULATE_FREIGHT`.

## When a method does not match

Every method is checked against the dictionary before its first call runs. A mismatch is a `PlsqlMappingException`
that says what was looked up and what the database has, so you can fix it without opening the package:

<!-- snippet: routine-not-found -->
```text
calculateFrieght(String, String): routine SHOP.ORDERS_PKG.CALCULATE_FRIEGHT does not exist. Available: APPLY_DISCOUNT, CALCULATE_FREIGHT, CANCEL_ORDER, COUNT_ORDERS, DESCRIBE, ORDER_STATUS, PLACE_ORDER
```

<!-- snippet: wrong-arity -->
```text
calculateFreight(String): the method declares 1 parameter, but SHOP.ORDERS_PKG.CALCULATE_FREIGHT expects 2: [P_ORIGIN VARCHAR2, P_DESTINATION VARCHAR2]
```

<!-- snippet: unknown-name -->
```text
countOrders(long): parameter customerId looks for P_CUSTOMER, which SHOP.ORDERS_PKG.COUNT_ORDERS does not have. Parameters: [P_CUSTOMER_ID NUMBER, P_STATUS VARCHAR2, P_MIN_TOTAL NUMBER]. Use @PlsqlParam with the Oracle name.
```

To check every method up front, in a test or at startup, use [`PlsqlPackages.validate`](validation.md).

## Rules at a glance

Penitent fails instead of guessing. It refuses a method that:

| The method… | What the message tells you |
|---|---|
| names a routine that does not exist, or that the session cannot see | the routines that do exist |
| returns a value for a procedure, or is `void` for a function | which of the two the routine is |
| has more or fewer parameters than the routine takes, by position | the routine's parameters. Only `DEFAULT` ones can be left out, at the end of the list |
| declares an `OUT` or `IN OUT` without `PlsqlOut`, or a `PlsqlOut` for an `IN` | the parameter and its direction |
| fits more than one overload equally well | that more than one version fits |
| binds by name to a name the routine does not have | the routine's parameters |
| binds two Java parameters to the same name | the two Java parameters |
| binds by name and leaves out a parameter without `DEFAULT` | the parameters that are missing |

## See also

- [Types](types.md): which Java type goes with each Oracle type, composites, collections and cursors.
- [Anonymous blocks](anonymous-blocks.md): for what is not a `package.routine` call, such as an OBJECT member method.
- [Compatibility](compatibility.md): every behavior on this page, with the test that covers it.
- [Changing PL/SQL while the application runs](deploying.md): what a deploy that changes a routine does to the calls.
