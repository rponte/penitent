# Calling routines

An interface maps the routines of one container: a package, with `@PlsqlPackage`, or the routines that live directly
in a schema, with `@PlsqlSchema`. Each method is one routine, and the library builds the call from the dictionary.

```java
@PlsqlPackage("FREIGHT_CALCULATOR")
public interface FreightCalculator {
    // FUNCTION calculate_freight(p_origin VARCHAR2, p_destination VARCHAR2) RETURN NUMBER
    BigDecimal calculateFreight(String originState, String destinationState);
}
```

## Routine names

The method name in camelCase becomes the routine name in SNAKE_CASE: `calculateFreight` looks up
`CALCULATE_FREIGHT`. When the convention does not apply, `@PlsqlRoutine` gives the name:

```java
@PlsqlRoutine("CALC_FREIGHT_V2")
BigDecimal calculateFreight(String originState, String destinationState);
```

A Java method that returns a value maps a function, and a `void` one maps a procedure. Calling a procedure as a
function, or the other way around, is a `PlsqlMappingException` that says what the database has.

## Names, and routines outside a package

Names in annotations follow SQL: unquoted, they are read in upper case, and in double quotes they keep their case.
`@PlsqlPackage("orders_pkg")` is `ORDERS_PKG`, and `@PlsqlPackage("\"ordersPkg\"")` is the package created as
`"ordersPkg"`. The rule is the same for `schema`, `@PlsqlRoutine`, `@PlsqlParam` with `BY_NAME`, `@PlsqlType` and
`resultType`, and the call quotes such a name for you.

An Oracle function or procedure may also live directly in a schema, outside any package. A Java method cannot live
outside a class, so the interface is the container of its routines: `@PlsqlPackage` for a package, and `@PlsqlSchema`
for the routines outside a package. An interface declares one container, not both, and an interface made only of
`@PlsqlBlock` needs none. `bind = BY_NAME`, `OUT`, `DEFAULT` and synonyms work as for a package:

```java
@PlsqlSchema                       // the session's schema; or @PlsqlSchema("FINANCE")
public interface Utilities {
    // FUNCTION round_half_even(p_value NUMBER) RETURN NUMBER, outside any package
    BigDecimal roundHalfEven(BigDecimal value);
}
```

`@PlsqlPackage("")` is an error that points to `@PlsqlSchema`, so that a package name erased by mistake does not
silently switch containers.

A package in another schema is named with `@PlsqlPackage(value = "ORDERS_PKG", schema = "SALES")`. Private, public and
chained synonyms are resolved, so the interface can name the synonym the application already uses. A synonym through
a database link is ignored.

## Parameters by position

By default, the Java parameter at position *i* goes to the Oracle parameter at position *i*, and the call is checked
against the dictionary: the arity, the types and whether the routine exists.

- **`DEFAULT` at the end of the list:** a parameter with `DEFAULT` can be omitted when every parameter after it is
  omitted too. Declare one Java method per way of calling, as overloads with the same name if you like. To omit one in
  the middle of the list, bind by name.
- **`null`** goes as `NULL`, even to a parameter with `DEFAULT`: Oracle does not replace it with the default.
- **Overloaded routines:** the version is picked by the Java method's signature. When more than one version fits and
  none is more restrictive in every parameter, the call is a `PlsqlMappingException`; for an OBJECT, `@PlsqlType`
  breaks the tie.

## `OUT` and `IN OUT` parameters

```java
// PROCEDURE add_numbers(p_a NUMBER, p_b NUMBER, p_total OUT NUMBER)
void addNumbers(int a, int b, PlsqlOut<Integer> total);

PlsqlOut<Integer> total = new PlsqlOut<>();
calculator.addNumbers(1, 2, total);
total.get(); // 3
```

`T` follows the same rules as a return value: a scalar, a composite, or `List<E>` for a collection or a cursor. If the
call fails, the `PlsqlOut` keeps its previous value. A function can also have `OUT` parameters.

An `OUT` parameter declared without `PlsqlOut`, or a `PlsqlOut` on an `IN` parameter, is a `PlsqlMappingException`:
the library does not guess which side is wrong.

## Binding by name

By default, the Java parameter at position *i* goes to the Oracle parameter at position *i*. With `bind = BY_NAME`, on
the interface or as the default of every interface in `PlsqlSettings` (see [Settings](settings.md)),
each Java parameter goes to the Oracle parameter with the same name, and the call is issued as
`ROUTINE(P_A => ?, P_C => ?)`:

```java
@PlsqlPackage(value = "ORDERS_PKG", bind = PlsqlBind.BY_NAME)
public interface Orders {
    // PROCEDURE reprocess_order_p(p_norder_id NUMBER, p_rlist_product_type ...)
    void reprocessOrderP(@PlsqlParam("P_NORDER_ID") Long orderId,
            @PlsqlParam("P_RLIST_PRODUCT_TYPE") List<String> productTypes);
}
```

- **The name:** it is the Java parameter's name in SNAKE_CASE, like the method's, or the one from `@PlsqlParam`. There
  is no prefix convention, such as stripping `P_`, because that varies from project to project. Without
  `@PlsqlParam`, you must compile with `-parameters`, which the Spring Boot parent already turns on.
- **Java order does not matter:** if two parameters of the same type swap positions in the database, which would go
  unnoticed when binding by position, the call stays correct.
- **`DEFAULT` anywhere:** a parameter with `DEFAULT` can be omitted even in the middle of the list. The method simply
  does not declare it, and a Java overload covers each way of calling.
- **Strict:** a Java parameter with no match, two with the same match, or an Oracle parameter without `DEFAULT` and
  with no match are a `PlsqlMappingException` on the first call, listing the names the routine has.
- **Overloading:** only the versions that match by name qualify, so a version that differs only in a parameter name
  can now be chosen.
