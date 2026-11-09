# Anonymous blocks

Not everything you need from the database is a `package.routine` call. An OBJECT type's member method, a call that
needs a local variable first, or a cursor opened over a query all need a few lines of PL/SQL around them. For those,
`@PlsqlBlock` runs an anonymous block, and Penitent still takes care of the binding and the types.

## A first block

The `postal_address_t` type from [Types](types.md#composite-types) has a member method:

<!-- snippet: fixture -->
```sql
CREATE TYPE postal_address_t AS OBJECT (
    street   VARCHAR2(100),
    city     VARCHAR2(60),
    zip_code VARCHAR2(9),

    MEMBER FUNCTION formatted RETURN VARCHAR2
);
```

A member method cannot be called as `package.routine`, so the block calls it:

<!-- snippet: blocks/first -->
```java
public interface AddressBlocks {

    @PlsqlBlock("declare l postal_address_t := :address; begin :result := l.formatted(); end;")
    String format(@PlsqlType("POSTAL_ADDRESS_T") Address address);
}
```

<!-- snippet: blocks/first-call -->
```java
AddressBlocks blocks = PlsqlPackages.create(AddressBlocks.class, dataSource);

blocks.format(new Address("Rua das Flores 10", "Fortaleza", "60000000"));   // "Rua das Flores 10, Fortaleza 60000000"
```

- **Parameters go in by name,** as `:name`, matching the Java parameter's name. That requires compiling with
  `-parameters`, which the Spring Boot parent already turns on.
- **The return value comes out through `:result`.**
- **No annotation on the interface:** an interface made only of `@PlsqlBlock` methods needs neither `@PlsqlPackage`
  nor `@PlsqlSchema`.

## Declare what the dictionary would say

For a routine, the dictionary says which Oracle type each parameter has. A block has no signature in the dictionary,
so for composites and collections, the annotations say it instead:

<!-- snippet: blocks/types -->
```java
public interface AddressBlocks {

    // a collection parameter declares its Oracle type
    @PlsqlBlock("declare l tag_list_t := :tags; begin :result := l.count; end;")
    int countTags(@PlsqlType("TAG_LIST_T") List<String> tags);

    // a collection :result declares its type in resultType
    @PlsqlBlock(value = "begin :result := customers_pkg.tags_of(:customerId); end;", resultType = "TAG_LIST_T")
    List<String> tagsOf(long customerId);

    // a cursor opened with OPEN :result FOR needs nothing else
    @PlsqlBlock("begin open :result for select id, total, status from orders where customer_id = :customerId order by id; end;")
    List<OrderRow> ordersOf(long customerId);
}
```

- **`@PlsqlType`** goes on the parameter, or on the class of a composite. It names an OBJECT, a RECORD
  (`PACKAGE.RECORD`), a `TABLE%ROWTYPE` or a collection, resolved in the session's schema, and the dictionary says
  which of them it is. A `null` goes as a `NULL` of that type.
- **Scalars need nothing:** a `String`, a `long` or a `LocalDate` is bound as it is.
- **A collection `:result`** becomes a `List<T>`, and a `NULL` one an empty list, as for a routine.
- **A cursor `:result`** is read into a `List<T>` as for a routine. When the block assigns a cursor variable to
  `:result` instead of opening it, declare `resultType = "SYS_REFCURSOR"`. An `OPEN :result FOR` inside a comment or
  a string literal does not count.

Forget the type, and the method fails before the call, with a message that says what to add, instead of the
driver's `ORA-17004`:

<!-- snippet: block-without-type -->
```text
countTags(List): parameter tags is a List; in a block, declare its Oracle type with @PlsqlType, as in @PlsqlType("NAME_LIST_T")
```

The same goes for a `List`, array, `record` or JavaBean parameter without `@PlsqlType`, and for a `List` `:result`
without `resultType` and without `OPEN :result FOR`.

## Validation

[`PlsqlPackages.validate`](validation.md) checks a block's placeholders against the method: each `:name` needs a
Java parameter with that name. The PL/SQL itself is only compiled by Oracle when the block runs.

## See also

- [Types](types.md): the Java types that go with each Oracle type, in blocks as in routines.
- [Calling routines](routines.md): when what you need is a `package.routine` call.
