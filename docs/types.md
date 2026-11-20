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
| `JSON`, from Oracle 21c on | `String`, the JSON text, or `Map<String, Object>`, `List<Object>`, a number or `Boolean`: see [JSON](#json) |

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

## JSON

Oracle 21c added a `JSON` type, which stores a document in a binary form of its own, OSON. A parameter, a return
value or a cursor column of that type maps to a `String`, holding the document's JSON text, or to the document
itself, as a `Map` or a `List` (see [As a Map or a List](#as-a-map-or-a-list)):

<!-- snippet: fixture -->
```sql
FUNCTION echo(p_doc JSON) RETURN JSON;
```

<!-- snippet: untested -->
```java
String echo(String doc);

json.echo("{ \"b\" : 2 }");   // {"b":2}
```

- **The text that comes back is the database's,** with its own spacing. **A number keeps its value, not its written
  form,** since the type stores numbers as `NUMBER`s: `{"price":24.90,"n":1.5e3}` comes back as
  `{"price":24.9,"n":1500}`, and a number of more than about 38 significant digits loses the rest, with no error.
  Keep such a value, such as a large identifier, as a JSON string. A `VARCHAR2` or `CLOB` column checked with
  `IS JSON` keeps the text as written.
- **To work with your own classes,** convert the text with the JSON library of your application, in a
  [converter](converters.md). With Jackson:

  <!-- snippet: types/json-converter -->
  ```java
  public static class OrderJson implements PlsqlConverter<Order, String> {

      private static final JsonMapper JSON = JsonMapper.builder().build();

      @Override
      public String toDatabase(Order order) {
          return order == null ? null : JSON.writeValueAsString(order);
      }

      @Override
      public Order fromDatabase(String json) {
          return json == null ? null : JSON.readValue(json, Order.class);
      }
  }
  ```

  <!-- snippet: types/json-converter-use -->
  ```java
  @PlsqlRoutine("ECHO")
  @PlsqlConvert(OrderJson.class)
  Order echo(@PlsqlConvert(OrderJson.class) Order pDoc);
  ```

- **As a Map or a List,** see [below](#as-a-map-or-a-list): with no JSON library at all.
- **Drivers 21 and later** know the type, and bind it as such, in every place above. A document of any size goes
  both ways, a scalar at the top, such as `42`, included.
- **Driver 19 does not know the type,** so Penitent converts the text in the database, where the driver sees only
  text: a `JSON` parameter goes as `JSON(?)`, and a `JSON` return value comes back through
  `JSON_SERIALIZE(... RETURNING CLOB)`, which the call log shows. That works for a document of any size, by position
  and by name. An `OUT` or an `IN OUT` takes only a bind of the JSON type itself, which driver 19 cannot make, so it
  is refused before the call. A cursor with a `JSON` column fails inside driver 19 itself, while the call runs,
  before Penitent sees any column: Penitent discards the connection, which the failure leaves broken, and its message
  names the likely type, `JSON`, and the way around it. Convert the column in the query, with
  `JSON_SERIALIZE(doc RETURNING CLOB)`, or use driver 21 or later.
- **For an `OUT` with driver 19,** call the routine through an [anonymous block](anonymous-blocks.md), whose
  variable takes the document, and whose `JSON_SERIALIZE` makes it text again; the same goes for any conversion of
  your own:

  <!-- snippet: types/json-block -->
  ```java
  interface JsonThroughBlocks {

      // IN: the text becomes JSON in the database, with JSON(...)
      @PlsqlBlock("begin :result := fixture_json.length_of(JSON(:doc)); end;")
      BigDecimal lengthOf(String doc);

      // return value: the JSON becomes text in the database, with JSON_SERIALIZE
      @PlsqlBlock("begin :result := JSON_SERIALIZE(fixture_json.echo(JSON(:doc)) RETURNING CLOB); end;")
      String echo(String doc);

      // OUT: into a JSON variable of the block, then as text
      @PlsqlBlock("""
              declare
                  v_doc JSON;
              begin
                  fixture_json.out_doc(v_doc);
                  :result := JSON_SERIALIZE(v_doc RETURNING CLOB);
              end;
              """)
      String outDoc();
  }
  ```

  It works with every driver. A document of any size goes in, but a block's `:result` is a `VARCHAR2`, so a
  document above 32,767 bytes that comes back fails with `ORA-06502`: only the JSON type of drivers 21 and later
  brings back a larger one.
- **Not supported, refused before the call:** a collection of `JSON` and a RECORD with a `JSON` field, which no
  driver can describe, and a `%ROWTYPE` of a table with a `JSON` column: read such rows through a cursor, where a
  `JSON` column is supported. An OBJECT cannot have a `JSON` attribute: Oracle itself refuses it. Two versions of
  a routine, one taking `JSON` and one `VARCHAR2`, both fit a `String`, so a method that calls them is refused as
  ambiguous: call the one you want through an [anonymous block](anonymous-blocks.md).


### As a Map or a List

Without a JSON library, declare the document's own shape: Penitent parses and writes the text with the driver's
JSON API, `oracle.sql.json`, which every supported driver ships.

| The document's top | Java type |
|---|---|
| an object | `Map<String, Object>`, a `LinkedHashMap` that keeps the keys' order |
| an array | `List<Object>`, an `ArrayList` |
| a number | `BigDecimal`, `Long`, `Integer` or another numeric type, converted exactly |
| `true` or `false` | `Boolean` |
| any | `String`, its JSON text |

<!-- snippet: types/json-map -->
```java
@PlsqlPackage("FIXTURE_JSON")
interface Orders {
    Map<String, Object> echo(Map<String, Object> pDoc);   // an object

    @PlsqlRoutine("ECHO")
    List<Object> echoArray(List<Object> pDoc);            // an array

    BigDecimal scalar();                                  // a number at the top
}
```

<!-- snippet: types/json-map-use -->
```java
Map<String, Object> order = new LinkedHashMap<>();
order.put("id", 7);
order.put("total", new BigDecimal("24.90"));
order.put("items", List.of("pen", "ink"));

Map<String, Object> back = orders.echo(order);
Object id = back.get("id");         // BigDecimal 7
Object total = back.get("total");   // BigDecimal 24.9: the value, not its written form
Object items = back.get("items");   // List ["pen", "ink"]
```

- **Inside a `Map` or a `List`,** an object is a `Map<String, Object>`, an array a `List<Object>`, a string a
  `String`, `true` and `false` a `Boolean`, `null` a `null`, and **every number a `BigDecimal`:** the JSON type stores
  numbers as `NUMBER`s, so an integer and a decimal look the same, and `24.90` comes back as `24.9`. Cast with
  `((BigDecimal) map.get("id")).intValueExact()`, not `(Integer) map.get("id")`.
- **On the way in,** a `Map` with `String` keys, a `List`, a `CharSequence`, a `Boolean`, a finite number and
  `null` go as they are, at any depth. Anything else, such as a `LocalDate`, is refused before the call, naming where
  it is, as in `$.items[0].when`: convert it to a `String` yourself, or use a [converter](converters.md).
- **A document that does not fit the declared type is refused,** naming what it is: an array read as a `Map`, a
  number read as a `List`, or a JSON string read as anything but `String`, whose text keeps its quotes.
- **Only `Map<String, Object>` and `List<Object>`,** or their wildcards, describe a document, whose values are of
  many types. A `Map<String, String>`, a `List<String>`, or a `HashMap` is refused before the call, and in a
  cursor's record, before the row is read.
- **A number beyond the range of `NUMBER`,** about `1e-130` to `1e126`, such as `1e200`, is not read by the
  driver's JSON parser, and where the database parses it depends on the driver:

  | Driver | Database | `String` | `Map` or `List` |
  |---|---|---|---|
  | 21 or later | any | refused on the way in: the driver parses the text before sending it | the same |
  | 19 | 23ai | works: the text comes back, as `{"big":1E+200}` | refused on the way back, by the driver's parser |
  | 19 | 21c | the database refuses it, with `ORA-01426` | the same |

  Keep such a number as a JSON string.
- **The same mapping works with every driver,** since the document crosses JDBC as text. With driver 19, the limits
  above apply: no `OUT`, no `IN OUT` and no cursor column.
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
- **JavaBeans** need a no-argument constructor and setters to be read. Inherited properties count. A `boolean` or
  `Boolean` property can be read through `isX()` as well as `getX()`, but not through both: they may answer
  differently, as a `boolean` and a `Boolean` that is `null`, so the library refuses the class rather than pick one.
  `@PlsqlBoolean` and `@PlsqlAttr` go on the field, the getter or the setter.
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
