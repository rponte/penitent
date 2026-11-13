# Testing

Code that calls PL/SQL is best tested against a real Oracle: the routine, the SQL inside it and the data it reads are
where most of the behavior lives. Penitent's interfaces are plain Java, so a test calls them as the application does,
and checks what comes back. This page starts there, and then shows how to stub a single routine, for the cases where
the real one is out of your hands.

## Testing code that calls PL/SQL

### Call the real routine

A test needs an Oracle to talk to. [Testcontainers](https://java.testcontainers.org/modules/databases/oraclefree/)
starts one for the tests and throws it away after them, so every developer and every build gets the same database:

<!-- snippet: untested -->
```java
@Container
static OracleContainer oracle = new OracleContainer("gvenzl/oracle-free:23-slim-faststart");
```

With your schema in it, applied by your migrations as in production, the tests on this page work with a package that
calculates freights between two states of Brazil:

<!-- snippet: fixture -->
```sql
CREATE OR REPLACE PACKAGE freights AS
  SUBTYPE money IS NUMBER(10,2);

  FUNCTION calculate(p_origin freight_item.uf%TYPE, p_destination VARCHAR2 DEFAULT 'SP') RETURN money;
  PROCEDURE quote(p_uf IN VARCHAR2, p_value OUT money);
  FUNCTION count_items(p_uf VARCHAR2) RETURN NUMBER;
END freights;
/

CREATE OR REPLACE PACKAGE BODY freights AS
  FUNCTION base_rate(p_uf VARCHAR2) RETURN NUMBER IS
  BEGIN
    RETURN CASE p_uf WHEN 'CE' THEN 50 ELSE 10 END;
  END base_rate;

  FUNCTION calculate(p_origin freight_item.uf%TYPE, p_destination VARCHAR2 DEFAULT 'SP') RETURN money IS
    l_value money;
  BEGIN
    l_value := base_rate(p_destination) * 1.5;
    RETURN l_value;
  END calculate;

  PROCEDURE quote(p_uf IN VARCHAR2, p_value OUT money) AS
  BEGIN
    p_value := base_rate(p_uf);
  END;

  FUNCTION count_items(p_uf VARCHAR2) RETURN NUMBER IS
    l_count NUMBER;
  BEGIN
    SELECT COUNT(*) INTO l_count FROM freight_item WHERE uf = p_uf;
    RETURN l_count;
  END count_items;
END freights;
/
```

The application calls it through an interface of Penitent:

<!-- snippet: testing/interface -->
```java
@PlsqlPackage("FREIGHTS")
public interface Freights {

    BigDecimal calculate(String pOrigin, String pDestination);

    void quote(String pUf, PlsqlOut<BigDecimal> pValue);

    int countItems(String pUf);
}
```

A test creates the interface as the application does, calls it, and checks the result of the real PL/SQL:

<!-- snippet: testing/real-call -->
```java
Freights freights = PlsqlPackages.create(Freights.class, dataSource);

BigDecimal freight = freights.calculate("SP", "CE");

// what the real PL/SQL computes: the rate of CE, 50, times 1.5
assertThat(freight).isEqualByComparingTo("75.00");
```

### Arrange, act, assert

Most routines read and write the schema's data, so a test arranges the rows it needs, calls the routine, and asserts
on what it returns or on the rows it changed. With Spring, a `@Transactional` test is rolled back at its end, so each
test starts from the same data, and no test cleans up after another:

<!-- snippet: testing/arrange-act-assert -->
```java
@SpringBootTest
@Transactional
class FreightReportTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired Freights freights;

    @Test
    void counts_the_items_of_a_state() {
        // arrange
        jdbc.update("INSERT INTO FREIGHT_ITEM (ID, UF) VALUES (7001, 'CE')");
        jdbc.update("INSERT INTO FREIGHT_ITEM (ID, UF) VALUES (7002, 'CE')");

        // act
        int items = freights.countItems("CE");

        // assert
        assertThat(items).isEqualTo(2);
    }
}
```

The interface takes part in the test's transaction, as in the application, so the routine sees the rows the test
inserted. [Transactions and connections](transactions.md) has the details, with Spring and without it.

### When a routine depends on what you cannot control

Sometimes the real routine is the problem, not the thing to test. A package that calls a web service through
`UTL_HTTP`, reads another system over a database link, or belongs to a team whose test data you cannot shape; an
error that is hard to provoke for real, such as a timeout or a rule deep inside another package. For those, stub that
one routine, and keep everything else real.

## Mocking a routine

### A first mock

Say the code under test is a service that adds the freight to an order's subtotal:

<!-- snippet: testing/service -->
```java
public class CheckoutService {

    private final Freights freights;

    public CheckoutService(Freights freights) {
        this.freights = freights;
    }

    public BigDecimal total(BigDecimal subtotal, String origin, String destination) {
        return subtotal.add(freights.calculate(origin, destination));
    }
}
```

To test its arithmetic, the freight has to be one you know, whatever the real calculation does. With `penitent-test`,
the test stubs the routine, in the style of Mockito, from the same interface the service calls. In a Spring Boot test,
it takes one annotation, `@EnablePlsqlMocks`:

<!-- snippet: testing/spring -->
```java
@SpringBootTest
@EnablePlsqlMocks
class CheckoutServiceTest {

    @Autowired PlsqlMocks mocks;
    @Autowired CheckoutService checkout;

    @Test
    void adds_the_freight_to_the_subtotal() {
        // arrange
        Freights freights = mocks.mock(Freights.class);
        when(freights.calculate("SP", "CE")).thenReturn(new BigDecimal("24.90"));

        // act
        BigDecimal total = checkout.total(new BigDecimal("100.00"), "SP", "CE");

        // assert
        assertThat(total).isEqualByComparingTo("124.90");
    }
}
```

- **Arrange:** `mocks.mock(...)` gives a stand-in for the interface, and `when(...).thenReturn(...)` says what
  `calculate("SP", "CE")` answers in this test.
- **Act:** the service calls `freights.calculate("SP", "CE")` as always, and gets `24.90`.
- **Assert:** the total is the subtotal plus the stubbed freight.

The stub lives only while this test runs, and everything else in the package stays real. It is real PL/SQL, too, so
the code under test sees it wherever it calls the routine from, Java or other PL/SQL.

### Getting started

Add the module with the `test` scope:

<!-- snippet: untested -->
```xml
<dependency>
    <groupId>io.github.rponte</groupId>
    <artifactId>penitent-test</artifactId>
    <version>0.29.0</version>
    <scope>test</scope>
</dependency>
```

The test database needs two scripts, applied once. On a disposable database, such as a Testcontainers one, one call
right after it starts applies both:

<!-- snippet: testing/prepare -->
```java
// admin: a DBA's DataSource; pool: the DataSource of the schema that owns the packages
PlsqlMocks.prepare(admin, pool, "PLSQL_TEST");
```

On a database shared by your team or your CI, apply the scripts as your migrations, as
[Preparing a shared test database](#preparing-a-shared-test-database) shows. If anything is missing when a test
begins, it fails with a message that says what, and how to fix it.

> **Measured on Oracle 23ai.** Oracle 21c and 19c are not measured yet.

### Stubbing a routine

The examples below stub routines of `freights`, a mock of the interface, as in the first mock:

<!-- snippet: untested -->
```java
Freights freights = mocks.mock(Freights.class);
```

**Return a value.** `when(...).thenReturn(...)`, as in the first mock. Numbers, text, `LocalDate` and `null` are
measured; booleans and `LocalDateTime` are written as PL/SQL too, but not measured yet.

**Raise an error.** `thenThrow` raises an application error, as `RAISE_APPLICATION_ERROR` does, and `thenRaise` one of
Oracle's predefined exceptions, by name. Both reach the code under test as a real error would, a
`PlsqlRoutineRaisedException`:

<!-- snippet: testing/errors -->
```java
when(freights.calculate("SP", "XX")).thenThrow(-20200, "Cannot calculate freight to XX");
when(freights.calculate("SP", "AM")).thenRaise("NO_DATA_FOUND");
```

**Set `OUT` parameters.** A procedure returns nothing, so its call goes in a lambda, and `out(value)` stands for the
`PlsqlOut` argument, with the value the stub sets:

<!-- snippet: testing/procedure -->
```java
when(() -> freights.quote(eq("CE"), out(new BigDecimal("99.99"))));
```

**Match arguments.** A plain value matches itself; `eq`, `any` and `isNull` work as in Mockito. When several stubs
match a call, the last one wins, so write the general ones first:

<!-- snippet: testing/matchers -->
```java
when(freights.calculate(eq("SP"), any())).thenReturn(new BigDecimal("10.00"));
// the last stub that matches wins: this one, for a null destination
when(freights.calculate(eq("SP"), isNull())).thenReturn(new BigDecimal("15.00"));
```

**Stub a routine more than once.** Each `when(...)` adds a stub, and a test can make as many as it needs:

| Stubs of the same routine | What a call gets |
|---|---|
| for different arguments, such as `("SP", "CE")` and `("RJ", "BA")` | each one answers its own arguments |
| for the same arguments, made twice | the last one: a later stub replaces an earlier one, as in Mockito |
| a general one, such as `(eq("SP"), any())`, and a specific one, such as `(eq("SP"), isNull())` | the last one that matches: make the general ones first |

Each stub recompiles the package, in about ten milliseconds, so make them all before the code under test runs, as
[Things to keep in mind](#things-to-keep-in-mind) explains. Unlike Mockito, `thenReturn` does not chain, so one stub
cannot answer differently on the first call and on the second, yet.

### Verifying calls

`verify` checks how many times the stubbed routine was called in this test, whether from Java or from other PL/SQL:

<!-- snippet: testing/verify -->
```java
verify(freights).calculate("SP", "CE");
verify(freights, times(2)).calculate(eq("SP"), any());
verify(freights, never()).calculate("RJ", "BA");
```

### With JUnit 5, without Spring

`PlsqlMockExtension` does what `@EnablePlsqlMocks` does, for a test without Spring. Give it the `DataSource` the
application uses in tests, wrapped by `PlsqlMocks.wrap`, and create the code under test with it:

<!-- snippet: testing/junit -->
```java
// the DataSource the application uses in tests, wrapped
static DataSource dataSource = PlsqlMocks.wrap(pool);

@RegisterExtension
static PlsqlMockExtension mocks = PlsqlMockExtension.on(() -> dataSource);

CheckoutService checkout = new CheckoutService(PlsqlPackages.create(Freights.class, dataSource));

@Test
void adds_the_freight_to_the_subtotal() {
    // arrange
    Freights freights = mocks.mock(Freights.class);
    when(freights.calculate("SP", "CE")).thenReturn(new BigDecimal("24.90"));

    // act
    BigDecimal total = checkout.total(new BigDecimal("100.00"), "SP", "CE");

    // assert
    assertThat(total).isEqualByComparingTo("124.90");
}
```

A method can also take the test's `PlsqlMocks` as a parameter, `@Test`, `@BeforeEach` and `@AfterEach` alike; not
`@BeforeAll`, which runs before any test, and fails saying so. In a Spring test, a parameter takes it with
`@Autowired`, as Spring asks of every parameter it fills, so `void test(@Autowired PlsqlMocks mocks)`; without the
annotation, JUnit finds no one to fill it.

### With any other test framework

`PlsqlMocks` works on its own, so JUnit 4, TestNG or Spock use it directly: call `begin()` before each test and
`end()` after it, or use a `try` block:

<!-- snippet: testing/without-extension -->
```java
// the DataSource the application uses in tests, wrapped
DataSource dataSource = PlsqlMocks.wrap(pool);
CheckoutService checkout = new CheckoutService(PlsqlPackages.create(Freights.class, dataSource));

try (PlsqlMocks mocks = PlsqlMocks.on(dataSource)) {
    // begins the test: from here, stubs can be made
    mocks.begin();

    // arrange
    Freights freights = mocks.mock(Freights.class);
    when(freights.calculate("SP", "CE")).thenReturn(new BigDecimal("24.90"));

    // act
    BigDecimal total = checkout.total(new BigDecimal("100.00"), "SP", "CE");

    // assert
    assertThat(total).isEqualByComparingTo("124.90");
}
```

## Things to keep in mind

- **The mock only records; the code under test answers.** Unlike a Mockito mock, the object from `mocks.mock(...)`
  does not answer calls: it tells `when` and `verify` which routine and which arguments they are about. The stub
  lives in the database, so it is the code under test, or the application's interface, that gets the stubbed answer.
  A call on the mock that is neither stubbed nor verified, such as `freights.calculate("SP", "CE")` on its own, fails
  the test, saying so, at the next call on a mock or when the test ends.
- **Stub first, then act.** Make the stubs of a package before the test calls it. A stub made after the package was
  used fails the next call there once, with `ORA-04068`; see [How it works](#how-it-works).
- **A call no stub matches fails,** with `ORA-20999`, naming the routine and showing the arguments, so a missing stub
  never passes unnoticed. Use `any()` for a stub that should answer every call.
- **A stubbed routine runs none of its real code.** What that code would have called, it no longer calls.
- **`verify` counts stubbed routines only.** A routine with no stub in the test records nothing, so verifying it is
  refused.
- **Tests that mock run one at a time** on a database: two tests cannot mock the same database at once.
- **The application and the mocks share the wrapped `DataSource`.** In Spring, that is the context's; without Spring,
  give the application and the extension the same one, from `PlsqlMocks.wrap`.
- **The pool needs one connection to spare,** for the mocks' own work, besides those the test holds.
- **In Spring, inject `DataSource`, not the pool's type,** such as `HikariDataSource`: the bean is wrapped. Use
  `unwrap` to reach the pool.
- **What can be stubbed, so far:** routines declared in a package spec, in the test user's schema, answering with
  scalar values. A parameter of an object type, a collection or a record is matched with `any()` only, and a stub
  cannot return one yet. A private routine has no method to stub it through, and a wrapped body cannot be read.
- **A routine that takes or returns a `%ROWTYPE` cannot be called in a test with mocks,** stubbed or not: in the
  test's edition, Oracle cannot describe the type for the driver, and the call fails with `ORA-17060`. A `RECORD`
  declared in a package and an object type work. [Issue #31](https://github.com/rponte/penitent/issues/31) has the
  measurements and the way around it being studied.

## Seeing what the mocks do

The mocks log what they do through SLF4J, under `io.github.rponte.penitent.mock`, so a test that does not behave as
expected can be followed step by step. Turn it on in the tests' configuration:

<!-- snippet: untested -->
```yaml
# src/test/resources/application.yaml
logging:
  level:
    io.github.rponte.penitent.mock: debug
    io.github.rponte.penitent.call: debug   # the calls too, to see the stubbed routine being called
```

The first mock above then logs its test from beginning to end:

<!-- snippet: untested -->
```text
DEBUG Began mocks         : edition PENITENT_MOCK_5F2A91C3D4E6 in 18 ms
DEBUG Stubbed routine     : PLSQL_TEST.FREIGHTS.CALCULATE('SP', 'CE') -> RETURN 24.90 in 11 ms
DEBUG Calling routine     : PLSQL_TEST.FREIGHTS.CALCULATE <- Freights.calculate
DEBUG -> SQL              : {? = call PLSQL_TEST.FREIGHTS.CALCULATE(?, ?)}
DEBUG <- completed        : 3 ms
DEBUG Ended mocks         : edition PENITENT_MOCK_5F2A91C3D4E6 dropped in 15 ms
```

- **At `DEBUG`:** the edition of each test, each stub, with what it answers and to which arguments, and each `verify`
  that passed.
- **At `TRACE`:** also the PL/SQL generated for each stub, the first thing to read when a stub does not answer as
  expected.
- **At `WARN`:** only what is out of the ordinary, such as an edition left by a run that died, dropped when the next
  test begins.
- **At `INFO`,** the usual default, nothing: the tests stay quiet.

## How it works

The stub is not a Java trick: it is PL/SQL, compiled into the database, where only the test can see it.

- **An edition per test.** Oracle's
  [edition-based redefinition](https://docs.oracle.com/en/database/oracle/oracle-database/23/adfns/editions.html)
  lets a session see its own version of a package body, while every other session keeps seeing the real one. Each
  test gets an edition of its own, created before it and dropped after it.
- **Only the stubbed routine is rewritten.** The library reads the package body the database has, parses it, and
  replaces the stubbed routine's code with generated code that records the call, checks the stubs and answers. Every
  other routine, and the routine's own declaration, stay as written, and the result is compiled into the test's
  edition. A stub made after the package was used recompiles it, which is what discards the package's state in the
  sessions that ran it.
- **The connections enter the edition through the `DataSource`.** An edition belongs to a session, and a pool reuses
  its sessions, so the wrapped `DataSource` moves each connection into the test's edition when it hands it out, and
  back when it is returned. In Spring, the edition begins before the test transaction and ends after it, and the stubs
  compile on a connection of their own, so they never commit the test transaction.
- **Calls are recorded in a table,** by an autonomous transaction, so `verify` sees them even when the test rolls back.

[Architecture decision 0001](adr/0001-mocking-plsql-with-editions.md) has the design, the alternatives that were left
aside, and what was measured to back each choice.

## Preparing a shared test database

`PlsqlMocks.prepare` suits a database that exists only for the tests. On one that lives longer, apply the two scripts
yourself, once. Both come in the jar, under `io/github/rponte/penitent/mock/`, ready for a migration, a Flyway callback
or a container init script:

| Script | Run by | What it does |
|---|---|---|
| `penitent-mock-admin.sql` | a DBA | enables editions for the schema's packages, and grants `CREATE ANY EDITION` and `DROP ANY EDITION` |
| `penitent-mock-schema.sql` | the schema that owns the packages | creates the table `PENITENT_MOCK_CALLS` and the package `PENITENT_MOCK`, which record the calls |

> **Only on a test database.** `ENABLE EDITIONS` cannot be undone. The admin script limits it to packages, so tables
> and object types are left as they are, but it is still a change for good to the schema.

The library never applies them by itself: when a test begins, it checks them, and if something is missing, the test
fails with a message that lists everything missing and names the scripts.

## See also

- [Transactions and connections](transactions.md): how a test's calls take part in its transaction.
- [Spring and Spring Boot](spring.md): the interfaces as Spring beans.
- [Architecture decision 0001](adr/0001-mocking-plsql-with-editions.md): the design behind the mocks.
