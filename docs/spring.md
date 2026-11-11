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

## Spring Boot starter

With Spring Boot, the dependency is all you need. It registers the interfaces of the application's packages, as
`@EnablePlsqlPackages` would, with no annotation:

<!-- snippet: untested -->
```xml
<dependency>
    <groupId>io.github.rponte</groupId>
    <artifactId>penitent-spring-boot-starter</artifactId>
    <version>0.26.0</version>
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
