# Spring and Spring Boot

The `penitent-spring` module makes the call take part in the current transaction and delivers database failures as
`DataAccessException`, with the same classes as a `JdbcTemplate`. The one exception is a routine error, which arrives
as `PlsqlRoutineRaisedException`, the same exception used without Spring. Because of that, a duplicate key inside the
routine is not a `DuplicateKeyException`: it is `getCode() == 1`. A deadlock, on the other hand, is still a
`PessimisticLockingFailureException`.

```java
FreightCalculator freight = SpringPlsqlPackages.create(FreightCalculator.class, dataSource);
```

Converters work the same way: `SpringPlsqlPackages.create(type, dataSource, converters)`.

## Interfaces as beans

`@EnablePlsqlPackages` registers a bean for each interface found in the given packages and their
subpackages, so a service injects the interface instead of creating it:

```java
@Configuration
@EnablePlsqlPackages("com.example.plsql")
class PlsqlConfig {
}

@Service
class FreightService {

	private final FreightCalculator freight;

	FreightService(FreightCalculator freight) {
		this.freight = freight;
	}
}
```

- **What is found:** interfaces with `@PlsqlPackage` or `@PlsqlSchema`, and interfaces that declare a `@PlsqlBlock`
  method, even without an annotation on the type. The scan reads the class files without loading them, so it only
  sees the methods an interface declares, not the inherited ones. Any other interface in the package is ignored.
- **Nested interfaces** are skipped unless `considerNested = true`, as in Spring Data: an interface nested in a test
  class is usually a fixture, sometimes broken on purpose, and would otherwise stop the context.
- **How it is created:** with `SpringPlsqlPackages.create`, so the bean takes part in the transaction and translates
  exceptions as above. It is a singleton, with a single metadata cache.
- **When it is created:** at startup, even if nothing injects it, so a mapping error such as a missing container stops
  the context. The routines are still checked against the dictionary on their first call.
- **Validation at startup:** with `@EnablePlsqlPackages(validateOnStartup = true)`, every interface it found goes
  through [`PlsqlPackages.validate`](validation.md) once the context's singletons exist. A mismatch stops the context,
  naming every interface and method that does not match. It is off by default, because with it on the context does
  not start without the database.
- **`DataSource`:** the only one in the context, or the bean named in `@EnablePlsqlPackages(dataSource = "...")`.
- **Bean name:** the interface's fully qualified name.
- **Settings:** a `PlsqlSettings` bean, if the context has one, applies to every registered interface. Without a
  translator in it, failures still arrive in the Spring hierarchy.

  ```java
  @Bean
  PlsqlSettings plsqlSettings() {
  	return PlsqlSettings.defaults().withConverters(PlsqlConverters.of(new TaxIdConverter()));
  }
  ```
- **Converters for a single interface** cannot be given to a registered bean. Use `@PlsqlConvert` on the parameter, or
  create that interface by hand outside the scanned packages: if it is also scanned, there are two beans of the same
  type and injecting it is ambiguous.

## Spring Boot starter

With Spring Boot, the `penitent-spring-boot-starter` dependency is enough: it registers the interfaces of the
application's packages, as `@EnablePlsqlPackages` would, with no annotation.

```xml
<dependency>
	<groupId>io.github.rponte</groupId>
	<artifactId>penitent-spring-boot-starter</artifactId>
	<version>0.21.0</version>
</dependency>
```

```yaml
penitent:
  bind: BY_NAME            # BY_POSITION (default) or BY_NAME, for the interfaces that do not declare one
  validation:
    enabled: true          # checks every interface against the database at startup; off by default
```

- **Which packages:** the application's, the same ones Spring Data uses. That is the package of the
  `@SpringBootApplication` class and its subpackages, plus any added with `@AutoConfigurationPackage(basePackages = ...)`.
  `scanBasePackages` does not count, because it only feeds component scanning.
- **`@EnablePlsqlPackages` still works, and wins:** if the application declares it, the starter does not scan, and the
  annotation's packages are the only ones registered. Use it for interfaces outside the application's packages, or to
  choose a `DataSource` by name.
- **`DataSource`:** a single one, or a primary one. With several and no primary, the starter backs off.
- **Converters and translator in code:** declare a `PlsqlSettingsCustomizer`, the Spring Boot way. The starter builds the
  `PlsqlSettings` from the properties and applies every customizer, in `@Order`:

  ```java
  @Bean
  PlsqlSettingsCustomizer converters() {
  	return settings -> settings.withConverters(PlsqlConverters.of(new TaxIdConverter()));
  }
  ```

  `PlsqlSettings` is immutable, so a customizer returns the settings to use instead of changing the ones it receives.
- **A `PlsqlSettings` bean of the application wins,** as with any Spring Boot autoconfiguration: the starter does not
  build its own, and neither `penitent.bind` nor the customizers apply. Replacing the bean in tests therefore works with
  the production properties in place. Spring Boot would say nothing; here, if `penitent.bind` is set, a warning at startup
  says it is not applied.
- **`penitent.validation.enabled`** validates every interface registered in the context, including those of an
  `@EnablePlsqlPackages`.
