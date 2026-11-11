# Spring and Spring Boot

In a Spring application, a call to the database is expected to do two things: take part in the current transaction,
and fail with Spring's `DataAccessException`, as a `JdbcTemplate` does. Penitent's core knows nothing about Spring,
so two modules add that:

| Module | What it gives you |
|---|---|
| `penitent-spring` | Calls in the current transaction, Spring exceptions, and `@EnablePlsqlPackages` to register interfaces as beans |
| `penitent-spring-boot-starter` | All of the above with no configuration: every interface in the application becomes a bean |

With Spring Boot, use the starter and skip to [Spring Boot starter](#spring-boot-starter).

## Transactions and exceptions

`SpringPlsqlPackages.create` works like `PlsqlPackages.create`, with Spring's behavior:

<!-- snippet: untested -->
```java
Orders orders = SpringPlsqlPackages.create(Orders.class, dataSource);
```

- **Transactions:** inside a `@Transactional` method, the call uses the transaction's connection. It sees the data
  the transaction has written, and its own changes are committed or rolled back with the rest. See
  [Transactions and connections](transactions.md) for what happens when a call fails inside one.
- **One transaction model:** Spring's. A `PlsqlTransactions` block keeps working outside Spring's transactions, and
  mixing the two, a block inside a Spring transaction or one inside a block, fails with a clear message instead of
  splitting the work in two. See [`PlsqlTransactions` in a Spring application](transactions.md#plsqltransactions-in-a-spring-application)
  and [how to move to Spring's transactions](transactions.md#moving-from-plsqltransactions-to-spring).
- **Exceptions:** infrastructure failures arrive as `DataAccessException`, with the same classes a `JdbcTemplate`
  would throw. A deadlock, for example, is a `PessimisticLockingFailureException`.
- **Routine errors stay as they are:** an error raised by the routine arrives as `PlsqlRoutineRaisedException`, the
  same exception used without Spring, so you handle it by its code (see [Errors](errors.md)). For that reason, a
  duplicate key inside the routine is not a `DuplicateKeyException`: it is a `PlsqlRoutineRaisedException` with
  `getCode() == 1`.
- **Converters and settings:** `SpringPlsqlPackages.create(type, dataSource, converters)`, or with a
  [`PlsqlSettings`](settings.md).

## Interfaces as beans

Instead of calling `create` in each service, `@EnablePlsqlPackages` registers a bean for each interface found in
the given packages and their subpackages, and your services inject them:

<!-- snippet: untested -->
```java
@Configuration
@EnablePlsqlPackages("com.example.shop")
class PlsqlConfig {
}

@Service
class CheckoutService {

    private final Orders orders;

    CheckoutService(Orders orders) {
        this.orders = orders;
    }
}
```

Each bean is created with `SpringPlsqlPackages.create`, so it takes part in transactions and translates exceptions as
above. It is a singleton, with a single dictionary cache.

### What is registered

- **Interfaces with `@PlsqlPackage` or `@PlsqlSchema`,** and interfaces that declare a `@PlsqlBlock` method, even
  without an annotation on the type. Any other interface in the package is ignored.
- **Only declared methods count:** the scan reads the class files without loading them, so it sees the methods an
  interface declares, not the inherited ones.
- **Nested interfaces are skipped** unless `considerNested = true`, as in Spring Data: an interface nested in a test
  class is usually a fixture, sometimes broken on purpose, and would otherwise stop the context.
- **Bean name:** the interface's fully qualified name.

### When and how

- **The beans are created at startup,** even if nothing injects them, so a mapping error such as a missing package
  stops the context. The methods are still checked against the dictionary on their first call.
- **Validation at startup:** with `@EnablePlsqlPackages(validateOnStartup = true)`, every interface found goes through
  [`PlsqlPackages.validate`](validation.md) once the context's singletons exist. A mismatch stops the context, naming
  every interface and method that does not match. It is off by default, because with it on, the context does not
  start without the database.
- **`DataSource`:** the only one in the context, or the bean named in `@EnablePlsqlPackages(dataSource = "...")`.
- **Settings:** a `PlsqlSettings` bean, if the context has one, applies to every registered interface. Without a
  translator in it, failures still arrive as Spring exceptions.

  <!-- snippet: untested -->
  ```java
  @Bean
  PlsqlSettings plsqlSettings() {
      return PlsqlSettings.defaults().withConverters(PlsqlConverters.of(new TaxIdConverter()));
  }
  ```
- **Converters for a single interface** cannot be given to a registered bean. Use `@PlsqlConvert` on the parameter,
  or create that interface by hand outside the scanned packages: if it is also scanned, there are two beans of the
  same type, and injecting it is ambiguous.

## Validation and resilience

An interface registered as a bean is a Spring bean like any other, so what Spring applies around beans applies to it
as well. Two are measured, with their usual setup and nothing specific to Penitent.

### Bean Validation

Put `@Validated` on the interface and the constraints on its parameters and return values. Spring Boot sets up method
validation with `spring-boot-starter-validation`; without Boot, declare a `MethodValidationPostProcessor` bean.

<!-- snippet: untested -->
```java
@PlsqlPackage("CUSTOMERS")
@Validated
public interface Customers {

    void rename(@Positive long id, @NotBlank @Size(max = 60) String name);

    void register(@Valid Customer customer);     // checks the record's own constraints

    @PositiveOrZero
    int countCustomers();
}
```

- **An invalid argument never reaches the database:** a `ConstraintViolationException` arrives before the call takes
  a connection, naming each parameter that failed, such as `rename.name`.
- **`@Valid` checks a record's fields,** with their own constraints, such as `register.customer.name`.
- **A constraint on the return value is checked after the call,** on the value the database sent back: the routine
  has run, and the `ConstraintViolationException` is a runtime exception, so a `@Transactional` method it leaves
  rolls back.

### Resilience4j

With `resilience4j-spring-boot4` and `spring-boot-starter-aspectj`, Resilience4j's annotations work on the
interface's methods, configured by name as usual:

<!-- snippet: untested -->
```java
@PlsqlPackage("PAYMENTS")
public interface Payments {

    @Retry(name = "payments")
    void pay(long id);

    @CircuitBreaker(name = "payments")
    void refund(long id);
}
```

- **`@Retry` makes the call again,** up to the attempts configured, and then lets the last failure through. Each
  attempt is a whole call, with a connection of its own.
- **`@CircuitBreaker` opens after the failures configured,** and then refuses with `CallNotPermittedException`,
  without calling the database.
- **Retry what is transient, and nothing else.** An error raised by the routine, a `PlsqlRoutineRaisedException`, is
  the routine's answer, and a retry gets the same one. Failures that may pass, such as a deadlock or a busy resource,
  arrive as Spring's `TransientDataAccessException`, so that is the class to list:

  <!-- snippet: untested -->
  ```properties
  resilience4j.retry.instances.payments.retry-exceptions=org.springframework.dao.TransientDataAccessException
  ```
- **Inside a transaction, retry the transaction, not the call.** After a deadlock, Oracle rolls back the statement,
  but the transaction around it still holds its earlier work and locks. Put `@Retry` on the method that starts the
  transaction instead, so that each attempt is a new one.
- **`@TimeLimiter` needs an asynchronous method,** one that returns a `CompletionStage`, and the interfaces' methods
  are synchronous today: see [Asynchronous calls](roadmap.md#asynchronous-calls). For a time limit on a call, use the
  driver's or the pool's timeouts.

### `@Transactional` on an interface

An interface's methods take `@Transactional` as any bean's do, with every attribute Spring gives it:

<!-- snippet: untested -->
```java
@PlsqlPackage("PAYMENTS")
public interface Payments {

    @Transactional
    void pay(long id);                    // a transaction of its own, or the caller's

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void audit(long id);                  // always a transaction of its own
}
```

- **Alone, the call runs in a transaction of its own,** committed when it returns.
- **Inside another transaction, it takes part in it,** and that transaction's rollback undoes it.
- **With `REQUIRES_NEW`,** it commits on its own, even when the transaction around it rolls back.
- **Inside a `PlsqlTransactions` block, it is refused,** as any Spring transaction there is: see
  [`PlsqlTransactions` in a Spring application](transactions.md#plsqltransactions-in-a-spring-application).

### Interfaces Spring wraps

For `@Transactional`, `@Validated`, Resilience4j or advice of the application, Spring wraps the interface's bean in a
proxy of its own. That proxy is still the library's interface, all the way through:

- **Validation at startup** and `PlsqlPackages.validate` reach the interface inside Spring's proxy.
- **A view, from `tx.using` or `PlsqlPackages.using`,** keeps every layer Spring put around the bean, in the same
  order, so validation, retries and the application's advice apply to the view as well. Spring's transaction is one
  of those layers, and a view behaves as the bean does:
  - **inside a `PlsqlTransactions` block,** a `@Transactional` method of the view is refused, as any Spring
    transaction there is. Call it outside the block, or leave `@Transactional` off the methods the block uses;
  - **with `PlsqlPackages.using`,** outside a block, Spring begins its transaction on a connection of its own, while
    the call still runs on the connection you gave, in that connection's transaction.

## Spring Boot starter

With Spring Boot, the dependency is all you need. It registers the interfaces of the application's packages, as
`@EnablePlsqlPackages` would, with no annotation:

<!-- snippet: untested -->
```xml
<dependency>
    <groupId>io.github.rponte</groupId>
    <artifactId>penitent-spring-boot-starter</artifactId>
    <version>0.27.0</version>
</dependency>
```

<!-- snippet: untested -->
```yaml
penitent:
  bind: BY_NAME            # BY_POSITION (default) or BY_NAME, for the interfaces that do not declare one
  validation:
    enabled: true          # checks every interface against the database at startup; off by default
```

- **Which packages:** the application's, the same ones Spring Data uses. That is the package of the
  `@SpringBootApplication` class and its subpackages, plus any added with
  `@AutoConfigurationPackage(basePackages = ...)`. `scanBasePackages` does not count, because it only feeds component
  scanning.
- **`@EnablePlsqlPackages` still works, and wins:** if the application declares it, the starter does not scan, and the
  annotation's packages are the only ones registered. Use it for interfaces outside the application's packages, or to
  choose a `DataSource` by name.
- **`DataSource`:** a single one, or a primary one. With several and no primary, the starter backs off.
- **`penitent.validation.enabled`** validates every interface registered in the context, including those of an
  `@EnablePlsqlPackages`.

### Converters and translator in code

Declare a `PlsqlSettingsCustomizer`, the Spring Boot way. The starter builds the `PlsqlSettings` from the properties
and applies every customizer, in `@Order`:

<!-- snippet: untested -->
```java
@Bean
PlsqlSettingsCustomizer converters() {
    return settings -> settings.withConverters(PlsqlConverters.of(new TaxIdConverter()));
}
```

`PlsqlSettings` is immutable, so a customizer returns the settings to use instead of changing the ones it receives.

**A `PlsqlSettings` bean of the application wins,** as with any Spring Boot autoconfiguration: the starter does not
build its own, and neither `penitent.bind` nor the customizers apply. That makes it easy to replace the settings in a
test while the production properties stay in place. Spring Boot would say nothing about the ignored property; here,
if `penitent.bind` is set, a warning at startup says it is not applied.
