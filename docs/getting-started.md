# Getting started

In about ten minutes, you will call a real PL/SQL package from Java without writing a line of JDBC: no
`CallableStatement`, no `registerOutParameter`, no parameter positions to count. You write an interface, and Penitent
reads everything else from the database.

**You need** Java 17 or later, an Oracle Database (23ai is the version the library is tested on) and a `DataSource` that
connects to it.

## 1. The package

This guide uses a small orders package. Every page of the documentation uses the same one, so the examples build on
each other. Create it in your schema; the guide assumes a schema called `SHOP`:

<!-- snippet: fixture -->
```sql
CREATE TABLE orders (
    id          NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id NUMBER NOT NULL,
    total       NUMBER(10, 2) NOT NULL,
    status      VARCHAR2(10) DEFAULT 'OPEN' NOT NULL
);

CREATE OR REPLACE PACKAGE orders_pkg AS
    FUNCTION  calculate_freight(p_origin VARCHAR2, p_destination VARCHAR2) RETURN NUMBER;
    PROCEDURE place_order(p_customer_id NUMBER, p_total NUMBER, p_order_id OUT NUMBER);
    PROCEDURE cancel_order(p_order_id NUMBER);
    FUNCTION  order_status(p_order_id NUMBER) RETURN VARCHAR2;
END orders_pkg;
/

CREATE OR REPLACE PACKAGE BODY orders_pkg AS

    FUNCTION calculate_freight(p_origin VARCHAR2, p_destination VARCHAR2) RETURN NUMBER IS
    BEGIN
        RETURN CASE WHEN p_origin = p_destination THEN 10 ELSE 25.5 END;
    END;

    PROCEDURE place_order(p_customer_id NUMBER, p_total NUMBER, p_order_id OUT NUMBER) IS
    BEGIN
        INSERT INTO orders (customer_id, total) VALUES (p_customer_id, p_total)
        RETURNING id INTO p_order_id;
    END;

    PROCEDURE cancel_order(p_order_id NUMBER) IS
    BEGIN
        UPDATE orders SET status = 'CANCELLED' WHERE id = p_order_id AND status = 'OPEN';
        IF SQL%ROWCOUNT = 0 THEN
            RAISE_APPLICATION_ERROR(-20001, 'order ' || p_order_id || ' is not open');
        END IF;
    END;

    FUNCTION order_status(p_order_id NUMBER) RETURN VARCHAR2 IS
        l_status orders.status%TYPE;
    BEGIN
        SELECT status INTO l_status FROM orders WHERE id = p_order_id;
        RETURN l_status;
    END;

END orders_pkg;
/
```

## 2. The dependency

<!-- snippet: untested -->
```xml
<dependency>
    <groupId>io.github.rponte</groupId>
    <artifactId>penitent-core</artifactId>
    <version>0.32.0</version>
</dependency>
<dependency>
    <groupId>com.oracle.database.jdbc</groupId>
    <artifactId>ojdbc17</artifactId>
    <version>23.26.3.0.0</version>
</dependency>
```

<div class="admonition tip" markdown>
<p class="admonition-title">Using Spring Boot?</p>

Skip to [step 8](#8-with-spring-boot): the starter replaces this step and the next one.

</div>

## 3. The interface

Describe the package as a Java interface. Each method calls one routine, the PL/SQL word for a function or a
procedure (see [How Penitent works](concepts.md#routine)). The method's name in camelCase is the routine's name in
SNAKE_CASE:

<!-- snippet: getting-started/interface -->
```java
@PlsqlPackage("ORDERS_PKG")
public interface Orders {

    BigDecimal calculateFreight(String origin, String destination);

    void placeOrder(long customerId, BigDecimal total, PlsqlOut<Long> orderId);

    void cancelOrder(long orderId);

    String orderStatus(long orderId);
}
```

Notice what is missing: Oracle types, parameter names and positions to register. Penitent reads them from the
database dictionary on the first call, and checks that your method agrees with them.

<div class="admonition tip" markdown>
<p class="admonition-title">Another name for a method</p>

`@PlsqlRoutine("ORDER_STATUS")` on a method calls that routine, whatever the method is named: see
[Calling routines](routines.md#functions-and-procedures).

</div>

Create the interface from your `DataSource`, and call it:

<!-- snippet: getting-started/create-and-call -->
```java
Orders orders = PlsqlPackages.create(Orders.class, dataSource);

BigDecimal freight = orders.calculateFreight("SP", "CE");   // 25.5
```

Each call takes a connection from the `DataSource` and gives it back when it ends. Create the interface once and
share it: it is thread-safe, and it keeps what it read from the dictionary.

## 4. A procedure with an `OUT` parameter

`place_order` returns the new order's id through an `OUT` parameter. On the Java side, that is a `PlsqlOut<T>`, which
you read after the call:

<!-- snippet: getting-started/out-parameter -->
```java
PlsqlOut<Long> orderId = new PlsqlOut<>();
orders.placeOrder(42, new BigDecimal("100.00"), orderId);

orders.orderStatus(orderId.get());   // "OPEN"
```

## 5. When the routine raises an error

Cancel the same order twice, and `cancel_order` raises `ORA-20001`. The error arrives as a
`PlsqlRoutineRaisedException`, with the code and the text the routine gave:

<!-- snippet: getting-started/routine-error -->
```java
orders.cancelOrder(orderId.get());

try {
    orders.cancelOrder(orderId.get());
} catch (PlsqlRoutineRaisedException e) {
    e.getCode();     // 20001, without the sign
    e.getReason();   // "order 1 is not open"
}
```

A routine's own errors are kept apart from infrastructure failures, such as a lost connection, which arrive as its
base class, `PlsqlExecutionException`. [Errors](errors.md) explains where the line is drawn.

## 6. Catch mistakes before they reach production

Penitent is strict: when a method does not agree with the database, it fails with a message that says what it looked
up and what exists. It never guesses. Here is an interface with two mistakes, a procedure declared as a function and
an `OUT` parameter declared as a plain `Long`:

<!-- snippet: getting-started/broken-interface -->
```java
@PlsqlPackage("ORDERS_PKG")
public interface Orders {

    String cancelOrder(long orderId);

    void placeOrder(long customerId, BigDecimal total, Long orderId);
}
```

Each method is checked on its first call. To check them all at once, without calling any routine, use
`PlsqlPackages.validate`:

<!-- snippet: getting-started/validate -->
```java
PlsqlPackages.validate(orders);
```

<!-- snippet: validate-exception -->
```text
io.github.rponte.penitent.exception.PlsqlMappingException:
2 methods of com.example.shop.Orders do not match the database:
  - cancelOrder(long): the method declares a return value, but SHOP.ORDERS_PKG.CANCEL_ORDER is a procedure and returns nothing; use void
  - placeOrder(long, BigDecimal, Long): P_ORDER_ID of SHOP.ORDERS_PKG.PLACE_ORDER is OUT; declare PlsqlOut<T> in this position, not Long
```

Call it in a test, or at application startup, and a package changed by someone else's migration shows up before
any user hits it. See [Validation](validation.md).

## 7. Records and lists, not ResultSets

A routine often gives back more than a number or a text: a whole customer, its address, its tags, the rows of a
query. Penitent maps each of them to plain Java. You declare a `record` or a `List`, and never touch a `ResultSet`, a
`Struct` or an `Array`:

| In Oracle | What it is | In Java |
|---|---|---|
| `customers%ROWTYPE` | a row of a table | a `record` |
| OBJECT type | a type of the schema, with attributes | a `record` |
| RECORD | a type of a package, with fields | a `record` |
| nested table, `VARRAY` | many values of one type | a `List` |
| `SYS_REFCURSOR` | the rows of a query | a `List` of `record`s |

The attributes are matched by name, so the `record` only needs the same names, in camelCase: `zipCode` is `ZIP_CODE`.
Here is each one, from the simplest.

### A row of a table: `%ROWTYPE`

`customers%ROWTYPE` is a record with one field per column of the `customers` table. A function that returns it gives
you the whole row:

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

### A type of the schema: OBJECT

An OBJECT type is Oracle's closest thing to a class: a type created in the schema, with attributes. It goes both
ways, as a parameter and as a return value:

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

### A type of the package: RECORD

A RECORD is declared in the package spec, and exists only in PL/SQL. Its fields can be objects and collections too,
and the Java `record` simply nests them:

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

### Many values: collections

A collection is Oracle's list: a nested table or a `VARRAY` of a scalar or of an object. It is a `List` in Java, and
a parameter can also be a `Set` or an array:

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

### The rows of a query: cursors

A function that opens a cursor returns its rows. Penitent reads them all, each into a `record`, and closes the cursor
during the call:

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

Nothing in the Java code names `POSTAL_ADDRESS_T`, `TAG_LIST_T` or a column's type: Penitent reads them from the
dictionary. [Types](types.md) has the details: `null`s, other names with `@PlsqlAttr`, `INDEX BY` tables, JSON and
dates.

## 8. With Spring Boot

Use the starter instead of `penitent-core`:

<!-- snippet: untested -->
```xml
<dependency>
    <groupId>io.github.rponte</groupId>
    <artifactId>penitent-spring-boot-starter</artifactId>
    <version>0.32.0</version>
</dependency>
<dependency>
    <groupId>com.oracle.database.jdbc</groupId>
    <artifactId>ojdbc17</artifactId>
</dependency>
```

There is no `create` to call. Every interface in your application's packages becomes a bean, and you inject it:

<!-- snippet: untested -->
```java
@Service
class CheckoutService {

    private final Orders orders;

    CheckoutService(Orders orders) {
        this.orders = orders;
    }

    @Transactional
    long checkout(long customerId, BigDecimal total) {
        PlsqlOut<Long> orderId = new PlsqlOut<>();
        orders.placeOrder(customerId, total, orderId);
        return orderId.get();
    }
}
```

The call takes part in the current transaction, and infrastructure failures arrive as Spring's `DataAccessException`,
as with a `JdbcTemplate`. An error raised by the routine is still a `PlsqlRoutineRaisedException`, as in step 5. To
validate every interface at startup:

<!-- snippet: untested -->
```yaml
penitent:
  validation:
    enabled: true
```

[Spring and Spring Boot](spring.md) covers the rest.

## Next steps

- [Calling routines](routines.md): `DEFAULT` parameters, overloads, `IN OUT`, binding by name and routines outside a
  package.
- [Types](types.md): OBJECT, RECORD, `%ROWTYPE`, collections, cursors and JSON, mapped to Java records, lists and maps.
- [Errors](errors.md): which exception arrives, and how to translate it.
- [Transactions](transactions.md): several calls, and your own SQL, in one transaction.
- [Testing](testing.md): stub a routine in a test, as with Mockito.
