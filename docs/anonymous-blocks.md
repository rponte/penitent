# Anonymous blocks

`@PlsqlBlock` runs a block for whatever has no `package.routine` shape, such as an OBJECT member method.
Parameters go in by name, as `:name`, and the return value comes out through `:result`. A block has no signature in
the dictionary, so what the dictionary would say, the annotation says:

```java
// a composite or collection parameter declares its Oracle type
@PlsqlBlock("declare l NAME_LIST_T := :names; begin :result := l.count; end;")
int count(@PlsqlType("NAME_LIST_T") List<String> names);

// a collection :result names its type in resultType
@PlsqlBlock(value = "begin :result := names_pkg.active_names; end;", resultType = "NAME_LIST_T")
List<String> activeNames();

// a cursor opened with OPEN :result FOR needs nothing else
@PlsqlBlock("begin open :result for select street, num from addresses; end;")
List<Address> addresses();
```

- **Types:** `@PlsqlType` on the parameter, or on the class of a composite, names an OBJECT, RECORD (`PACKAGE.RECORD`),
  `TABLE%ROWTYPE` or collection, resolved in the session's schema. The dictionary says which of them it is. `null`
  goes as a `NULL` of that type.
- **Strict:** a `List`, array, `record` or JavaBean parameter without `@PlsqlType` is a `PlsqlMappingException` before
  the call, instead of an `ORA-17004`. So is a `List` `:result` without `resultType` and without `OPEN :result FOR`.
- **`:result`:** a collection becomes a `List<T>`, and a `NULL` one an empty list, as for a routine. A cursor is a
  `List<T>`, read as for a routine. When the block assigns a cursor instead of opening `:result`, declare
  `resultType = "SYS_REFCURSOR"`. `OPEN :result FOR` inside a comment or a literal does not count.
