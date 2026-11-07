# Logging

The library logs each call through SLF4J, using two loggers. That way you can see the SQL without exposing the data:

| Logger | Level | What it logs |
|---|---|---|
| `io.github.rponte.penitent.call` | debug | The routine and the Java method, the SQL as sent to the driver or the block text, the elapsed time, the failure and the dictionary lookup |
| `io.github.rponte.penitent.bind` | trace | The values sent, the return value and the `OUT` values |

```yaml
# application.yaml of a Spring Boot project
logging:
  level:
    io.github.rponte.penitent.call: debug
    io.github.rponte.penitent.bind: trace   # only where the data may appear in the log
```

```text
DEBUG Read dictionary     : PLSQL_TEST.FIXTURE_COMPOSITES.ECHO_ADDRESS in 41 ms
DEBUG Calling routine     : PLSQL_TEST.FIXTURE_COMPOSITES.ECHO_ADDRESS <- Composites.echoAddress
DEBUG -> SQL              : {? = call PLSQL_TEST.FIXTURE_COMPOSITES.ECHO_ADDRESS(?)}
TRACE -> with bind values : P_VALUE=PLSQL_TEST.ADDRESS_T('Street A', NULL)
TRACE <- result           : PLSQL_TEST.ADDRESS_T('Street A', NULL)
DEBUG <- completed        : 3 ms
```

`OUT` values are logged on one line, before the return value, and an `IN OUT` shows up in both directions. From 3
parameters on, the values are logged one per line, with the names aligned, and so is the SQL of a `BY_NAME` call. By
position, the SQL stays on one line, because one `?` per line would show nothing. The driver always receives the SQL
on one line:

```text
DEBUG -> SQL              : {call SALES.SALES_ORDERS_V2_PKG.PROCESS_ORDER(
DEBUG                         P_VORDER_NUMBER      => ?,
DEBUG                         P_NCUSTOMER_ID       => ?,
DEBUG                         P_RLIST_PRODUCT_TYPE => ?
DEBUG                       )}
TRACE -> with bind values : 3 parameters
TRACE                         P_VORDER_NUMBER      = '2018/801',
TRACE                         P_NCUSTOMER_ID       = 1,
TRACE                         P_RLIST_PRODUCT_TYPE = [
TRACE                           '01',
TRACE                           '02'
TRACE                         ]
```

A collection with more than one element is logged one element per line:

```text
TRACE -> with bind values : P_A=5, P_ACCUMULATED=10
TRACE <- OUT values       : P_DOUBLED=10, P_ACCUMULATED=15
TRACE <- result           : [
TRACE                         PLSQL_TEST.ADDRESS_T('Street A', 1),
TRACE                         PLSQL_TEST.ADDRESS_T('Street B', 2)
TRACE                       ]
```

- **Values are shown as the database sees them:** an OBJECT shows up with the type name and the attributes, in the
  type's order, in both directions. The exception is a cursor, which is shown through the rows' `toString`, because
  it cannot be read twice: a JavaBean without `toString` shows up as `Class@hash`.
- **Truncation:** text over 2000 characters and collections over 5 elements are truncated, with their size. LOBs and
  binary values are shown with their size only.
- **One line, one record:** every log line, including the continuation of a collection or a block, is a separate
  record with the logger's prefix, so that `grep` still finds the logger and the level. For that reason, the log is
  meant for development, staging and tests: in a log aggregator, one call becomes several records. A line break
  inside a value is logged as `\n`, so that no value can create a line that looks like it came from the library.
- **Failure:** logged on one line, without a stack trace, because the exception already reaches the caller.
- **Dictionary:** only read on the first call of each method, which is why that call takes longer.
