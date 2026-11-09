# Types

You never write an Oracle type name in Java. The dictionary already says which type each parameter, attribute and
return value has, so your method only declares the Java type, and Penitent checks that the two fit before the call.
This page shows which Java types go with each Oracle type, from a `NUMBER` to a cursor of records.

The examples use the orders package from [Getting started](getting-started.md) and a customers package, shown in each
section. The [compatibility table](compatibility.md) lists every pair of types that is tested.

## Scalars

| Oracle type | Java types |
|---|---|
| `NUMBER`, `BINARY_INTEGER`, `PLS_INTEGER` | `BigDecimal`, `Integer`, `Long`, `Double`, and their primitives |
| `BINARY_DOUBLE`, `BINARY_FLOAT`, `FLOAT` | `Double`, `Float`, `BigDecimal` |
| `VARCHAR2`, `CHAR`, `NVARCHAR2`, `ROWID` | `String`, an enum by its `name()`, `UUID` |
| `CLOB` | `String`, `java.sql.Clob` |
| `BLOB`, `RAW` | `byte[]`, and `java.sql.Blob` for a `BLOB` |
| `DATE` | `LocalDate`, `LocalDateTime` |
| `TIMESTAMP` | `LocalDateTime` |
| `TIMESTAMP WITH TIME ZONE`, `TIMESTAMP WITH LOCAL TIME ZONE` | `OffsetDateTime`, `ZonedDateTime`, `Instant` |
| `INTERVAL DAY TO SECOND`, `INTERVAL YEAR TO MONTH` | `Duration`, `Period` |
| `BOOLEAN` | `boolean`, `Boolean` |

How values cross:

- **`NULL` is `null`.** A primitive cannot hold `null`, so a `NULL` read into an `int` or a `boolean` is a
  `PlsqlMappingException`, never a made-up `0` or `false`. Declare the wrapper type, or `Optional<T>`.
- **`Optional<T>` as a return value** is empty on `NULL`. As a parameter it is rejected: a parameter that may be
  absent is just `null`.
- **A value that does not fit fails,** such as `300` read into a `byte` or a fraction into a `BigInteger`. It is
  never rounded.
- **Large values:** a `CLOB` above 32 KB is sent in a temporary LOB, freed when the call ends. A `Clob` or `Blob`
  read from the database is a copy in memory, so you can use it after the connection is gone.

For a type of your own, such as a `TaxId` value object, write a [converter](converters.md).

### `Optional` and dates

<!-- snippet: fixture -->
```sql
FUNCTION born_on(p_id NUMBER) RETURN DATE;          -- NULL for a customer without a birth date
FUNCTION created_at(p_id NUMBER) RETURN TIMESTAMP;
```

<!-- snippet: types/date-methods -->
```java
Optional<LocalDate> bornOn(long id);
LocalDateTime createdAt(long id);
```

<!-- snippet: types/date-calls -->
```java
customers.bornOn(1);      // Optional[1990-04-12]
customers.bornOn(2);      // Optional.empty
customers.createdAt(1);   // 2025-01-15T09:30
```

## Booleans

Oracle 23ai has a native `BOOLEAN`, and it maps to a Java `boolean` with no setup. Older schemas store booleans as
flags, and Penitent follows the type in the dictionary: a `NUMBER` uses `1`/`0`, and a `VARCHAR2` uses `Y`/`N`.

If your schema uses other literals, such as `S`/`N`, declare them with `@PlsqlBoolean`, on the parameter, on a record
component or, for the return value, on the method:

<!-- snippet: fixture -->
```sql
FUNCTION is_vip(p_id NUMBER) RETURN BOOLEAN;
FUNCTION vip_flag(p_id NUMBER) RETURN VARCHAR2;     -- 'S' or 'N'
```

<!-- snippet: types/boolean-methods -->
```java
boolean isVip(long id);

@PlsqlBoolean(trueValue = "S", falseValue = "N")
boolean vipFlag(long id);
```

Without `@PlsqlBoolean`, reading an `S` fails, instead of turning into `false`:

<!-- snippet: boolean-literal -->
```text
vipFlag(long): the return value of SHOP.CUSTOMERS_PKG.VIP_FLAG: cannot convert 'S' to boolean: expected 'Y' or 'N'. Use @PlsqlBoolean to declare other literals.
```

Writing cannot fail the same way: without the annotation, the database receives `Y` or `N`, whatever your schema
expects. So declare `@PlsqlBoolean` wherever a flag uses other literals.

## Time zones

Choose the Java type by what the Oracle type stores:

| Oracle type | Stores | Java types |
|---|---|---|
| `DATE`, `TIMESTAMP` | a local date and time, with no time zone | `LocalDate`, `LocalDateTime` |
| `TIMESTAMP WITH TIME ZONE` | an instant and its offset or region | `OffsetDateTime`, `ZonedDateTime`, `Instant` |
| `TIMESTAMP WITH LOCAL TIME ZONE` | an instant | `OffsetDateTime`, `ZonedDateTime`, `Instant` |

<!-- snippet: fixture -->
```sql
FUNCTION delivery_deadline(p_ordered_at TIMESTAMP WITH TIME ZONE) RETURN TIMESTAMP WITH TIME ZONE;
```

<!-- snippet: types/time-zone-method -->
```java
OffsetDateTime deliveryDeadline(OffsetDateTime orderedAt);
```

<!-- snippet: types/time-zone-call -->
```java
customers.deliveryDeadline(OffsetDateTime.parse("2025-03-10T18:00-03:00"));   // 2025-03-12T18:00-03:00
```

Crossing the line in either direction would lose information without any error, so Penitent rejects it. A
`LocalDateTime` for a time zone type would lose the time zone:

<!-- snippet: local-on-tstz -->
```text
deliveryDeadline(LocalDateTime): P_ORDERED_AT of SHOP.CUSTOMERS_PKG.DELIVERY_DEADLINE is TIMESTAMP WITH TIME ZONE, and LocalDateTime has no time zone; declare OffsetDateTime, ZonedDateTime or Instant
```

And an `OffsetDateTime`, `ZonedDateTime` or `Instant` for a `DATE` or `TIMESTAMP` would have nowhere to store it. The
same rule applies to the elements of a collection. To drop the time zone on purpose, declare `LocalDateTime` and
convert in Java, as in `value.toLocalDateTime()`.

A few more details:

- **An `Instant` is stored in UTC,** and that is what the routine sees in a `TO_CHAR` or an `EXTRACT`.
- **The result does not depend on the machine.** A `LocalDateTime` or `LocalDate` crosses the driver as a local date
  and time, never through the JVM's time zone, so a time that the zone skips when daylight saving time starts, such
  as 02:30, arrives unchanged. The session's language and territory, which the driver takes from the JVM's locale, do
  not change any value either.
- **`java.util.Date`, `java.sql.Date` and `java.sql.Timestamp` are the exception:** they hold an instant in the JVM's
  time zone. Prefer `java.time`.

## Composite types

An OBJECT type, a RECORD declared in a package and a table's `%ROWTYPE` all map to a Java `record` or a JavaBean.

### OBJECT

<!-- snippet: fixture -->
```sql
CREATE TYPE postal_address_t AS OBJECT (
    street   VARCHAR2(100),
    city     VARCHAR2(60),
    zip_code VARCHAR2(9),

    MEMBER FUNCTION formatted RETURN VARCHAR2   -- see Anonymous blocks
);

FUNCTION normalize_address(p_address postal_address_t) RETURN postal_address_t;
```

<!-- snippet: types/object -->
```java
public record Address(String street, String city, String zipCode) {
}

Address normalizeAddress(Address address);
```

<!-- snippet: types/object-call -->
```java
customers.normalizeAddress(new Address("rua das flores 10", "Fortaleza", "60000-000"));
// Address[street=Rua Das Flores 10, city=FORTALEZA, zipCode=60000000]
```

The record is not named after the type, and nothing in it says `POSTAL_ADDRESS_T`: the dictionary says which type
the parameter has.

### `%ROWTYPE`

<!-- snippet: fixture -->
```sql
FUNCTION find_customer(p_id NUMBER) RETURN customers%ROWTYPE;
```

<!-- snippet: types/rowtype -->
```java
public record Customer(Long id, String name, String taxId, String tier,
                       @PlsqlBoolean(trueValue = "S", falseValue = "N") boolean vip,
                       LocalDate bornOn, LocalDateTime createdAt) {
}

Customer findCustomer(long id);
```

<!-- snippet: types/rowtype-call -->
```java
customers.findCustomer(1);
// Customer[id=1, name=Ana Lima, taxId=12345678901, tier=G, vip=true, bornOn=1990-04-12, createdAt=2025-01-15T09:30]
```

### RECORD, with nested types

A RECORD declared in a package works the same way, and so do attributes that are themselves composites or
collections, at any depth:

<!-- snippet: fixture -->
```sql
TYPE summary_rec IS RECORD (
    customer_id NUMBER,
    name        VARCHAR2(100),
    address     postal_address_t,
    tags        tag_list_t
);

FUNCTION summary(p_id NUMBER) RETURN summary_rec;
```

<!-- snippet: types/record -->
```java
public record CustomerSummary(Long customerId, String name, Address address, List<String> tags) {
}

CustomerSummary summary(long id);
```

### How attributes are matched

- **By name, never by position.** Each Oracle attribute goes to the Java property with the same name, by the
  camelCase → SNAKE_CASE convention (`zipCode` is `ZIP_CODE`), whatever the order of the fields. Matching by
  position would silently swap two attributes of the same type.
- **Other names:** `@PlsqlAttr("POSTAL_CODE")` on a component or property gives the Oracle name. It accepts more
  than one name, for a Java type that shows up with different spellings, such as a RECORD field and a cursor column.
- **Every Oracle attribute needs a property.** Extra Java properties are fine, but a missing one is an error, since
  its value would be lost:

  <!-- snippet: missing-attribute -->
  ```text
  normalizeAddress(Address): Address does not cover attribute(s) of POSTAL_ADDRESS_T: [ZIP_CODE VARCHAR2]. Available properties: [STREET, CITY]. Use @PlsqlAttr to declare the name.
  ```
- **JavaBeans** need a no-argument constructor and setters to be read. Inherited properties count.
- **Inheritance:** an OBJECT type created `UNDER` another works too.
- **`null`:** a whole OBJECT can be `null`. A RECORD cannot be null in PL/SQL, so it comes back with null fields.
- **`@PlsqlType("POSTAL_ADDRESS_T")`** on the class is optional for a routine, and must agree with the dictionary if
  present. It is required in an [anonymous block](anonymous-blocks.md), which has no signature in the dictionary,
  and it breaks a tie between two overloads that take different OBJECT types.

## Collections

A nested table or `VARRAY`, declared in the schema or in a package spec, maps to a `List<T>`. The element can be a
scalar, a composite or another collection:

<!-- snippet: fixture -->
```sql
CREATE TYPE tag_list_t AS TABLE OF VARCHAR2(30);
CREATE TYPE postal_address_list_t AS TABLE OF postal_address_t;

FUNCTION tags_of(p_id NUMBER) RETURN tag_list_t;
FUNCTION count_tags(p_tags tag_list_t) RETURN NUMBER;
FUNCTION cities(p_addresses postal_address_list_t) RETURN tag_list_t;
```

<!-- snippet: types/collection-methods -->
```java
List<String> tagsOf(long id);

int countTags(List<String> tags);
int countTags(Set<String> tags);
int countTags(String[] tags);

List<String> cities(List<Address> addresses);
```

<!-- snippet: types/collection-calls -->
```java
customers.tagsOf(1);                                    // [vip, newsletter]
customers.countTags(new String[] { "a", "b", "c" });   // 3
customers.cities(List.of(
        new Address("Rua A 1", "Fortaleza", "60000000"),
        new Address("Rua B 2", "Recife", "50000000"))); // [Fortaleza, Recife]
```

- **As a parameter:** `List<T>`, `Set<T>`, `T[]` or a primitive array.
- **As a return value:** `List<T>`. A `NULL` collection comes back as an empty list.
- **Collections of collections:** `List<List<T>>`, or `T[][]` as a parameter, at any depth.
- **`INDEX BY` tables** can be read into a `List<T>`, in index order, but the indexes are lost. Sending one is
  rejected, because the driver numbers the elements from 0. See the [roadmap](roadmap.md#writing-index-by-and-its-keys).

## Cursors

A `SYS_REFCURSOR`, or a strongly typed `REF CURSOR`, returned by a routine is read whole into a `List<T>`. Each row
becomes a `record` or a JavaBean, matched by column name as for a composite:

<!-- snippet: fixture -->
```sql
FUNCTION orders_of(p_customer_id NUMBER) RETURN SYS_REFCURSOR;
-- OPEN l_cursor FOR SELECT id, total, status FROM orders WHERE customer_id = p_customer_id ORDER BY id;
```

<!-- snippet: types/cursor -->
```java
public enum OrderStatus { OPEN, CANCELLED }

public record OrderRow(Long id, BigDecimal total, OrderStatus status) {
}

List<OrderRow> ordersOf(long customerId);
```

<!-- snippet: types/cursor-call -->
```java
customers.ordersOf(201);
// [OrderRow[id=1, total=100, status=OPEN], OrderRow[id=2, total=40, status=CANCELLED]]
```

The `status` column holds `'OPEN'` and `'CANCELLED'`, so the enum is read by its `name()`. For a code that is not the
constant's name, see [enums by code](converters.md#enums-by-code).

- **Columns without a property are ignored,** since a query often returns more than one screen needs. A row type
  with no column in common with the cursor is an error.
- **A single-column cursor** can be read into a list of a scalar, as in `List<String> names()`.
- **An OBJECT column** can be read into a composite property.
- **An `OUT` cursor** is a `PlsqlOut<List<T>>`.
- **The cursor is read and closed during the call,** so the list does not depend on the connection.

## See also

- [Converters](converters.md): your own Java types, and enums stored by code.
- [Anonymous blocks](anonymous-blocks.md): types in a block, where there is no dictionary signature.
- [Compatibility](compatibility.md): every pair of types and every behavior on this page, with its test.
