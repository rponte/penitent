# Getting started

In about ten minutes, you will call a real PL/SQL package from Java without writing a line of JDBC: no
`CallableStatement`, no `registerOutParameter`, no parameter positions to count. You write an interface, and Penitent
reads everything else from the database.

**You need** Java 21, an Oracle Database (23ai is the version the library is tested on) and a `DataSource` that
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
    <version>0.21.0</version>
</dependency>
<dependency>
    <groupId>com.oracle.database.jdbc</groupId>
    <artifactId>ojdbc17</artifactId>
    <version>23.26.3.0.0</version>
</dependency>
```

Using Spring Boot? Skip to [step 7](#7-with-spring-boot): the starter replaces this step and the next one.

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

## 7. With Spring Boot

Use the starter instead of `penitent-core`:

<!-- snippet: untested -->
```xml
<dependency>
    <groupId>io.github.rponte</groupId>
    <artifactId>penitent-spring-boot-starter</artifactId>
    <version>0.21.0</version>
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
- [Types](types.md): OBJECT, RECORD, `%ROWTYPE`, collections and cursors, mapped to Java records.
- [Errors](errors.md): which exception arrives, and how to translate it.
