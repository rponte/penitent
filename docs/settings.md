# Settings

Some choices apply to every interface of an application: binding by name, the converters of your domain types, a
translator for your exceptions. Instead of repeating them in each `create`, put them in one `PlsqlSettings` and
pass it to every interface:

<!-- snippet: settings/settings -->
```java
PlsqlSettings settings = PlsqlSettings.defaults()
        .withBind(PlsqlBind.BY_NAME)
        .withConverters(PlsqlConverters.of(
                PlsqlConverter.ofNullable(TaxId.class, String.class, TaxId::value, TaxId::new)));

OrderSearch search = PlsqlPackages.create(OrderSearch.class, dataSource, settings);
CustomerLookup lookup = PlsqlPackages.create(CustomerLookup.class, dataSource, settings);
```

`PlsqlSettings` is immutable: each `with...` returns a new object. "Global" means one object you pass to every
`create`, not static state, so an interface never changes behavior after it is created. With Spring Boot, the starter
builds it for you from `application.yaml`; see [Spring and Spring Boot](spring.md#spring-boot-starter).

## What can be set

| Setting | Method | Default |
|---|---|---|
| How parameters are bound | `withBind(PlsqlBind.BY_NAME)` | `BY_POSITION` |
| Converters of your types | `withConverters(PlsqlConverters.of(...))` | none |
| Exception translator | `withExceptionTranslator(translator)` | `DefaultPlsqlExceptionTranslator`, or the Spring translator with `penitent-spring` |

## What is closer to the call wins

| Setting | From strongest to weakest |
|---|---|
| Binding | `@PlsqlPackage(bind)` or `@PlsqlSchema(bind)` → `PlsqlSettings` → `BY_POSITION` |
| Converters | `@PlsqlConvert` → `PlsqlSettings` → built-in conversions |
| Exceptions | `PlsqlSettings` → the default of the `create` you call |

So an interface that declares its own `bind` keeps it, whatever the settings say:

<!-- snippet: settings/positional -->
```java
@PlsqlPackage(value = "ORDERS_PKG", bind = PlsqlBind.BY_POSITION)
public interface PositionalOrders {

    long countOrders(long customerId, String status);   // still by position
}
```

The annotations' default is `PlsqlBind.DEFAULT`, which means "use the settings".

> **A global `BY_NAME` applies to every interface that does not declare `bind`.** Their parameters must then match
> the routine's by name, with `@PlsqlParam` or with Java names that follow the convention. An interface written for
> binding by position fails on its first call, with a message that lists the routine's parameters, so turn it on
> with [`validate`](validation.md) in a test.

## Adding to the global converters

To give one interface a converter on top of the global ones:

<!-- snippet: settings/adding -->
```java
PlsqlSettings mine = settings.withConverters(settings.converters().with(PlsqlConverters.of(tierConverter)));
```

For the same Java type, the converter passed to `with` wins.

The `create` overloads that take converters or a translator directly are shortcuts for
`PlsqlSettings.defaults().with...`.
