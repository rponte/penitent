# Converters

A `PlsqlConverter<J, D>` converts between the type Java declares (`J`) and a scalar type that would work without a
converter (`D`), such as `String` or `LocalDate`: the library carries it to JDBC. It applies to parameters, return
values, `OUT`, attributes, cursor columns and blocks and, in `List<J>` or `J[]`, per element.

```java
// for the type across the whole interface
PlsqlConverters converters = PlsqlConverters.of(
        PlsqlConverter.ofNullable(TaxId.class, String.class, TaxId::value, TaxId::new));
Orders orders = PlsqlPackages.create(Orders.class, dataSource, converters);

// at a single point, with a class that has a no-argument constructor
void register(@PlsqlConvert(DropTimeZone.class) OffsetDateTime when);
```

The annotation wins over the registry, which wins over the library's built-in conversions. Three rules matter:

- **`NULL` goes through the converter**, because in a legacy database it often means something, such as "not
  provided". With `PlsqlConverter.of` and in your own class, handle `null`; with `ofNullable`, it stays `null`
  without calling anything. Forgetting this fails with the converter, the value and the point in the message. A whole
  collection that is null does not go through the converter.
- **Validation looks at `D`.** A converter with `D = LocalDateTime` drops the time zone on purpose for a `TIMESTAMP`,
  but one with `D = OffsetDateTime` is still rejected. `D` is also what picks the version of an overloaded routine.
- **Scalar values only.** In an OBJECT, RECORD or cursor of records, each attribute or column has its own. A
  collection must have a scalar element, and a converter on the element of a cursor, as in `List<TaxId>`, makes it a
  cursor of that scalar, read from its only column.

## Enum by code

By default, an enum goes to the database by its `name()`. When the database stores a code instead, such as `'A'` for
`Status.ACTIVE` or `2` for `Priority.HIGH`, use `PlsqlConverter.enumByCode`:

```java
PlsqlConverters converters = PlsqlConverters.of(
        PlsqlConverter.enumByCode(Status.class, String.class, Status::code));

// for @PlsqlConvert, which needs a class with a no-argument constructor
public class StatusConverter extends PlsqlEnumCodeConverter<Status, String> {
    public StatusConverter() {
        super(Status.class, String.class, Status::code);
    }
}
```

- **Strict:** two constants with the same code, or a constant without a code, fail when the converter is created.
  Reading a code that no constant has is a `PlsqlMappingException` that lists the known codes.
- **`NULL` stays `null`** in both directions. To give `NULL` a meaning, write a converter with `PlsqlConverter.of`.
- **Codes as the database returns them:** a text code ignores trailing spaces, as a `CHAR` column pads it, and a
  `BigDecimal` code ignores its scale.
