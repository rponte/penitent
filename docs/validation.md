# Validation

Each method is checked against the dictionary on its first call, so a typo in a rarely used method only fails when it
first runs. `PlsqlPackages.validate` checks every method of an interface at once, without calling any
routine:

```java
Orders orders = PlsqlPackages.create(Orders.class, dataSource);
PlsqlPackages.validate(orders);
```

- **What it checks:** what a call checks before running. The routine exists, the parameters and the arity, procedure
  or function, `OUT` parameters, and the composite types the method declares, nested ones included. An anonymous
  block has no signature in the dictionary, so only its placeholders are checked against the method.
- **What stays for the call:** what depends on a value. That means a cursor's columns, and a composite sent as a
  subclass of the declared type.
- **Every error at once:** a `PlsqlMappingException` lists every method that does not match, one per line, so you do
  not fix one only to find the next. A database failure is not a mapping error: it stops the validation and is
  translated as on a call.
- **Cache:** it uses one connection and leaves what it read cached, so the first calls do not read the dictionary.
- **Cost:** a call reads the dictionary only the first time, with about 5 queries for a scalar routine and up to about
  16 for nested composites. The validation pays this for every method at once. The count is covered by a test, and
  reducing it is in the [Roadmap](roadmap.md#metadata-cache).
