# Mocking PL/SQL in tests

A test of code that calls PL/SQL often needs a routine to answer in a certain way: a freight calculation that returns
a fixed value, a call to an external system that fails, an error that is hard to provoke for real. With
`penitent-test`, you stub that one routine, in the style of Mockito, from the same interface the application calls it
through, while the test still runs against your real Oracle, with the real schema and the real SQL and PL/SQL around
the call.

<!-- snippet: testing/without-extension -->
```java
DataSource dataSource = PlsqlMocks.wrap(pool);                        // what the application uses in tests
Freights freights = PlsqlPackages.create(Freights.class, dataSource);

try (PlsqlMocks mocks = PlsqlMocks.on(dataSource)) {
    mocks.begin();                                                    // the test's edition

    Freights mock = mocks.mock(Freights.class);
    when(mock.calculate("SP", "CE")).thenReturn(new BigDecimal("24.90"));

    assertThat(freights.calculate("SP", "CE")).isEqualByComparingTo("24.90");
    verify(mock).calculate("SP", "CE");
}                                                                     // end(): the edition is dropped
```

The stub is real PL/SQL. The library reads the package body your database has, rewrites only the stubbed routine, and
compiles it into an [edition](https://docs.oracle.com/en/database/oracle/oracle-database/23/adfns/editions.html) of
the test's own. So the code under test sees the mock wherever it calls the routine from, Java or other PL/SQL, while
every other routine of the package stays real, and every session outside the test keeps seeing the real one.
[Architecture decision 0001](adr/0001-mocking-plsql-with-editions.md) has the design and what was measured to back it.

> **Measured on Oracle 23ai.** Oracle 21c and 19c are not measured yet; on them, the module's own tests are skipped.

## Setting up

### The dependency

`penitent-test` is for tests only: declare it with the `test` scope, never in production code.

<!-- snippet: untested -->
```xml
<dependency>
    <groupId>io.github.rponte</groupId>
    <artifactId>penitent-test</artifactId>
    <version>0.29.0-SNAPSHOT</version>
    <scope>test</scope>
</dependency>
```

JUnit 5 and Spring are optional dependencies of the module: it uses the versions your project already has, and a
project without them never needs them.

### The prerequisites

The test database needs two scripts, applied once. Both come in the jar, under `io/github/rponte/penitent/mock/`, so a
migration, a Flyway callback or a container init script can apply them as they are:

| Script | Run by | What it does |
|---|---|---|
| `penitent-mock-admin.sql` | a DBA | enables editions for the schema's packages, and grants `CREATE ANY EDITION` and `DROP ANY EDITION` |
| `penitent-mock-schema.sql` | the schema that owns the packages | creates the table `PENITENT_MOCK_CALLS` and the package `PENITENT_MOCK`, which record the calls for `verify` |

> **Only on a test database.** `ENABLE EDITIONS` cannot be undone. The admin script limits it to packages, so tables
> and object types are left as they are, but it is still a change for good to the schema.

The library never applies them by itself. When a test begins, it checks them and, if something is missing, fails with
a message that lists everything missing and names the scripts. On a disposable database, such as a Testcontainers one,
`prepare` applies both, and running it again is harmless:

<!-- snippet: testing/prepare -->
```java
PlsqlMocks.prepare(admin, pool, "PLSQL_TEST");   // admin: a DBA's DataSource; pool: the schema's own
```

## Stubbing and verifying

The examples use an interface like any other of Penitent:

<!-- snippet: testing/interface -->
```java
@PlsqlPackage("FREIGHTS")
interface Freights {
    BigDecimal calculate(String pOrigin, String pDestination);

    void quote(String pUf, PlsqlOut<BigDecimal> pValue);
}
```

`mock` gives an object whose calls are only recorded; `when` turns the recorded call into a stub, compiled at once.
From then on, the routine answers as stubbed, to Java and to PL/SQL alike.

**Values.** `thenReturn` returns a value. Numbers, text, `LocalDate` and `null` are measured; booleans and
`LocalDateTime` are written as PL/SQL too, but not measured yet, and any other type is refused with a clear message.

**Errors.** `thenThrow` raises an application error, as `RAISE_APPLICATION_ERROR` does, and `thenRaise` one of
Oracle's predefined exceptions, by name. Both arrive as `PlsqlRoutineRaisedException`, as a real error would:

<!-- snippet: testing/errors -->
```java
when(mock.calculate("SP", "XX")).thenThrow(-20200, "Cannot calculate freight to XX");
when(mock.calculate("SP", "AM")).thenRaise("NO_DATA_FOUND");
```

**Procedures and `OUT` parameters.** A call that returns nothing goes in a lambda, and `out(value)` stands for the
`PlsqlOut` argument, with the value the stub sets it to:

<!-- snippet: testing/procedure -->
```java
when(() -> mock.quote(eq("CE"), out(new BigDecimal("99.99"))));
```

**Matchers.** A plain value matches itself; `eq`, `any` and `isNull` work as in Mockito, and a call uses matchers for
all of its arguments or for none. When several stubs match a call, the last one wins, so write the general ones first:

<!-- snippet: testing/matchers -->
```java
when(mock.calculate(eq("SP"), any())).thenReturn(new BigDecimal("10.00"));
when(mock.calculate(eq("SP"), isNull())).thenReturn(new BigDecimal("15.00"));   // the last that matches wins
```

**Strict.** A call that no stub matches is an error, `ORA-20999`, that names the routine and shows the arguments, so a
missing stub never passes unnoticed.

**Verify.** `verify` counts the calls the stubbed routine received in this test, made from Java or from PL/SQL:

<!-- snippet: testing/verify -->
```java
verify(mock).calculate("SP", "CE");
verify(mock, times(2)).calculate(eq("SP"), any());
verify(mock, never()).calculate("RJ", "BA");
```

Only a stubbed routine records its calls: a `verify` of one with no stub in the test is refused, since it would always
count zero.

## With JUnit 5

`PlsqlMockExtension` gives each test an edition of its own. It begins before the test's `@BeforeEach` methods, so a
stub can be made there, and ends after its `@AfterEach` ones, whether the test passed or failed. Register it with the
`DataSource` from `PlsqlMocks.wrap`, the one the application uses in tests:

<!-- snippet: testing/junit -->
```java
@RegisterExtension
static PlsqlMockExtension mocks = PlsqlMockExtension.on(() -> dataSource);   // from PlsqlMocks.wrap

@Test
void calculates_freight() {
    Freights mock = mocks.mock(Freights.class);
    when(mock.calculate("SP", "CE")).thenReturn(new BigDecimal("24.90"));

    assertThat(freights.calculate("SP", "CE")).isEqualByComparingTo("24.90");
}
```

A test can also take its `PlsqlMocks` as a parameter.

## With Spring

`@EnablePlsqlMocks` on the test class is all it takes:

<!-- snippet: testing/spring -->
```java
@SpringJUnitConfig(Application.class)   // or @SpringBootTest
@EnablePlsqlMocks
@Transactional
class FreightServiceTest {

    @Autowired PlsqlMocks mocks;
    @Autowired Freights freights;

    @Test
    void calculates_freight() {
        when(mocks.mock(Freights.class).calculate("SP", "CE")).thenReturn(new BigDecimal("24.90"));

        assertThat(freights.calculate("SP", "CE")).isEqualByComparingTo("24.90");
    }
}
```

- **The context's `DataSource` is wrapped,** so every connection the application takes from it, through Penitent's
  interfaces, a `JdbcTemplate` or anything else, enters the test's edition. With several `DataSource` beans and none primary,
  name the one to use: `@EnablePlsqlMocks(dataSource = "...")`.
- **The edition begins before Spring's test transaction, and ends after it.** The stubs compile on a connection of
  their own, so they never commit the test transaction, and its rollback works as usual.
- **The context is not refreshed for each test:** it is wrapped once, and cached as usual. Classes with the
  annotation share one context, apart from the classes without it.

## Without JUnit 5

The example at the top of the page uses `PlsqlMocks` directly, which works with any test framework, such as JUnit 4,
TestNG or Spock: wrap the application's `DataSource`, call `begin()` before each test and `end()` after it, or use a
`try` block, as there.

## How a mock behaves

| Case | Behavior |
|---|---|
| The other routines of the package, public and private, and the other overloads | stay real |
| A real routine that calls the stubbed one, in the same package or another | gets the mock |
| The stubbed routine itself | runs none of its real code: what that code called is no longer called by it |
| A session outside the test's edition | sees the real routine |

## Limits

- **Stub a package before the test uses it.** Each stub recompiles the package body, which discards the package's
  state in every session that already ran it: the next call there fails once with `ORA-04068`, and the one after it
  sees the stub. Making the stubs first, then acting, never meets it.
- **One test mocks a database at a time.** An edition can have only one child, so tests that mock run one after the
  other, and mocks that begin while others are on in the same database are refused.
- **The application and the mocks share one wrapped `DataSource`,** since the test's edition is kept in it. Wrapping
  the same pool again gives the same object; another pool of the same database does not follow the test's edition.
- **The pool needs a connection to spare,** for the mocks' own work, besides those the test holds at once.
- **In Spring, the `DataSource` bean is no longer of the pool's type,** such as `HikariDataSource`: inject
  `DataSource`, or use `unwrap`.
- **A private routine cannot be stubbed,** since no method of the interface stands for it, and **a wrapped body cannot
  be parsed,** so it is refused.
- **Packages of the test user's schema only,** and scalar values. Other objects, such as views or standalone routines,
  object types, collections and cursors are not supported yet.

## See also

- [Architecture decision 0001](adr/0001-mocking-plsql-with-editions.md): the design, the measurements and the open
  questions.
- [Spring and Spring Boot](spring.md): the interfaces as Spring beans.
