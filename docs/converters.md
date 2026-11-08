# Converters

Your domain has types that the database does not know: a `TaxId` value object, a `Tier` enum stored as a one-letter
code. A converter teaches Penitent to carry them, so your interfaces speak your domain's types instead of `String`s.

## A value object

The customers table stores a tax id as a `VARCHAR2`, and the domain has a value object for it:

<!-- snippet: fixture -->
```sql
FUNCTION find_by_tax_id(p_tax_id VARCHAR2) RETURN NUMBER;
```

<!-- snippet: converters/value-object -->
```java
public record TaxId(String value) {
}

@PlsqlPackage("CUSTOMERS_PKG")
public interface Customers {

    Long findByTaxId(TaxId taxId);
}
```

Register a converter between `TaxId` and `String`, a type Penitent already knows how to send:

<!-- snippet: converters/registry -->
```java
PlsqlConverters converters = PlsqlConverters.of(
        PlsqlConverter.ofNullable(TaxId.class, String.class, TaxId::value, TaxId::new));

Customers customers = PlsqlPackages.create(Customers.class, dataSource, converters);

customers.findByTaxId(new TaxId("12345678901"));   // 1
```

A `PlsqlConverter<J, D>` converts between the type your Java code declares (`J`, here `TaxId`) and a scalar type that
would work without a converter (`D`, here `String`). It applies wherever a scalar value goes: parameters, return
values, `OUT` parameters, attributes of a composite, cursor columns and blocks. In a `List<J>` or a `J[]`, it applies
to each element.

So the same registry also reads the `TAX_ID` attribute of a `%ROWTYPE` into a `TaxId`:

<!-- snippet: converters/rowtype -->
```java
public record Customer(Long id, String name, TaxId taxId, Tier tier,
                       @PlsqlBoolean(trueValue = "S", falseValue = "N") boolean vip,
                       LocalDate bornOn, LocalDateTime createdAt) {
}

Customer findCustomer(long id);   // customers.findCustomer(1).taxId() is TaxId[value=12345678901]
```

## Enums by code

By default, an enum goes to the database by its `name()`, as the `OrderStatus` of [Types](types.md#cursors) does. When
the database stores a code instead, such as `'G'` for `Tier.GOLD`, use `PlsqlConverter.enumByCode`:

<!-- snippet: converters/tier -->
```java
public enum Tier {
    GOLD("G"), SILVER("S");

    private final String code;

    Tier(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
```

<!-- snippet: converters/registry-with-tier -->
```java
PlsqlConverters converters = PlsqlConverters.of(
        PlsqlConverter.ofNullable(TaxId.class, String.class, TaxId::value, TaxId::new),
        PlsqlConverter.enumByCode(Tier.class, String.class, Tier::code));
```

<!-- snippet: converters/tier-methods -->
```java
Tier tierOf(long id);         // customers.tierOf(1) is GOLD
int countByTier(Tier tier);   // customers.countByTier(Tier.SILVER) sends 'S'
```

- **Strict:** two constants with the same code, or a constant without a code, fail when the converter is created.
  Reading a code that no constant has is a `PlsqlMappingException` that lists the known codes.
- **`NULL` stays `null`** in both directions. To give `NULL` a meaning, write a converter with `PlsqlConverter.of`.
- **Codes as the database returns them:** a text code ignores trailing spaces, as a `CHAR` column pads it, and a
  `BigDecimal` code ignores its scale.

## At a single point

To convert a single parameter, attribute or return value, without a registry, annotate it with `@PlsqlConvert` and a
converter class that has a no-argument constructor:

<!-- snippet: converters/annotation -->
```java
public class TaxIdConverter implements PlsqlConverter<TaxId, String> {

    @Override
    public String toDatabase(TaxId taxId) {
        return taxId == null ? null : taxId.value();
    }

    @Override
    public TaxId fromDatabase(String value) {
        return value == null ? null : new TaxId(value);
    }
}

Long findByTaxId(@PlsqlConvert(TaxIdConverter.class) TaxId taxId);
```

For an enum by code, extend `PlsqlEnumCodeConverter`:

<!-- snippet: converters/tier-converter -->
```java
public class TierConverter extends PlsqlEnumCodeConverter<Tier, String> {
    public TierConverter() {
        super(Tier.class, String.class, Tier::code);
    }
}
```

When more than one applies, the closest one wins: `@PlsqlConvert`, then the registry, then the built-in conversions.
To register converters for every interface at once, put them in [Settings](settings.md).

## Rules worth knowing

- **`NULL` goes through the converter,** because in a legacy database it often means something, such as "not
  provided". With `PlsqlConverter.ofNullable`, `null` stays `null` without calling your functions. With
  `PlsqlConverter.of` and in your own class, your code receives the `null`, and forgetting that fails with a message
  that names the converter, the value and the parameter:

  <!-- snippet: converter-null -->
  ```text
  findByTaxId -> SHOP.CUSTOMERS_PKG.FIND_BY_TAX_ID: parameter P_TAX_ID: the converter of TaxId failed to write NULL: java.lang.NullPointerException
  ```

  A whole collection that is `null` does not go through the converter.
- **Penitent checks `D` against the dictionary.** A converter with `D = LocalDateTime` can drop the time zone on
  purpose for a `TIMESTAMP`, but one with `D = OffsetDateTime` is still rejected. `D` is also what picks the version of
  an overloaded routine.
- **Scalar values only.** A converter does not replace a whole OBJECT, RECORD or cursor row: each attribute or column
  has its own. A collection must have a scalar element, and a converter on the element of a cursor, as in
  `List<TaxId>`, makes it a cursor of that scalar, read from its only column.

## See also

- [Types](types.md): what Penitent converts without a converter.
- [Settings](settings.md): converters for every interface.
