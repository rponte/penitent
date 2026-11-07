# Settings

`PlsqlSettings` gathers what applies to every interface created with it: the converters, the default
`bind` and the exception translator. It is immutable, and "global" means one object passed to every `create`, not static
state: a proxy never changes behavior after it is created.

```java
PlsqlSettings settings = PlsqlSettings.defaults()
        .withBind(PlsqlBind.BY_NAME)
        .withConverters(PlsqlConverters.of(new TaxIdConverter()));

Orders orders = PlsqlPackages.create(Orders.class, dataSource, settings);
```

What is declared closer to the call wins:

| Item | From strongest to weakest |
|---|---|
| `bind` | `@PlsqlPackage(bind)` or `@PlsqlSchema(bind)` → `PlsqlSettings.bind()` → `BY_POSITION` |
| Converters | `@PlsqlConvert` → `PlsqlSettings.converters()` → built-in conversions |
| Exceptions | `PlsqlSettings.exceptionTranslator()` → the creator's default: `DefaultPlsqlExceptionTranslator`, or the Spring translator with `penitent-spring` |

- **`PlsqlBind.DEFAULT`** is now the annotations' default and means "use the settings". An interface that declares
  `BY_POSITION` or `BY_NAME` keeps it whatever the settings say. Without settings, the default is still `BY_POSITION`.
- **Adding converters** to the global ones: `settings.withConverters(settings.converters().with(mine))`. For the same
  Java type, the one passed to `with` wins.
- The `create` overloads that take converters or a translator still work, and are shortcuts for
  `PlsqlSettings.defaults().with...`.
