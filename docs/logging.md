# Logging

When a call does not do what you expected, the first thing to see is what was actually sent. Penitent logs each
call through SLF4J: the routine, the SQL, the values in and out, the time it took and the failure, if any. It uses
two loggers, so you can see the SQL without exposing the data:

| Logger | Level | What it logs |
|---|---|---|
| `io.github.rponte.penitent.call` | `DEBUG` | The routine and the Java method, the SQL as sent to the driver or the block text, the elapsed time, the failure and the dictionary lookup |
| `io.github.rponte.penitent.bind` | `TRACE` | The values sent, the return value and the `OUT` values |

<!-- snippet: untested -->
```yaml
# application.yaml of a Spring Boot application
logging:
  level:
    io.github.rponte.penitent.call: debug
    io.github.rponte.penitent.bind: trace   # only where the data may appear in the log
```

Without an SLF4J implementation on the classpath, SLF4J warns once and discards the log. Spring Boot already ships
Logback.

## What a call looks like

The first call of `normalizeAddress`, from [Types](types.md#object):

<!-- snippet: composite -->
```text
DEBUG Read dictionary     : SHOP.CUSTOMERS_PKG.NORMALIZE_ADDRESS in 38 ms
DEBUG Calling routine     : SHOP.CUSTOMERS_PKG.NORMALIZE_ADDRESS <- Customers.normalizeAddress
DEBUG -> SQL              : {? = call SHOP.CUSTOMERS_PKG.NORMALIZE_ADDRESS(?)}
TRACE -> with bind values : P_ADDRESS=SHOP.POSTAL_ADDRESS_T('rua das flores 10', 'Fortaleza', '60000-000')
TRACE <- result           : SHOP.POSTAL_ADDRESS_T('Rua Das Flores 10', 'FORTALEZA', '60000000')
DEBUG <- completed        : 4 ms
```

- **`Read dictionary`** only shows up on the first call of each method, which is why that call takes longer. See
  [How Penitent works](concepts.md#what-happens-in-a-call).
- **Values are shown as the database sees them:** an OBJECT shows up with its type name and its attributes, in the
  type's order, in both directions.

## Many parameters, and collections

From 3 parameters on, the values are logged one per line, with the names aligned. So is the SQL of a call bound
[by name](routines.md#binding-by-name). A collection with more than one element is logged one element per line:

<!-- snippet: by-name-and-list -->
```text
DEBUG Read dictionary     : SHOP.ORDERS_PKG.COUNT_ORDERS in 35 ms
DEBUG Calling routine     : SHOP.ORDERS_PKG.COUNT_ORDERS <- OrderSearch.countOrders
DEBUG -> SQL              : {? = call SHOP.ORDERS_PKG.COUNT_ORDERS(
DEBUG                         P_CUSTOMER_ID => ?,
DEBUG                         P_STATUS      => ?,
DEBUG                         P_MIN_TOTAL   => ?
DEBUG                       )}
TRACE -> with bind values : 3 parameters
TRACE                         P_CUSTOMER_ID = 501,
TRACE                         P_STATUS      = 'OPEN',
TRACE                         P_MIN_TOTAL   = 100
TRACE <- result           : 0
DEBUG <- completed        : 3 ms
DEBUG Read dictionary     : SHOP.CUSTOMERS_PKG.TAGS_OF in 22 ms
DEBUG Calling routine     : SHOP.CUSTOMERS_PKG.TAGS_OF <- Customers.tagsOf
DEBUG -> SQL              : {? = call SHOP.CUSTOMERS_PKG.TAGS_OF(?)}
TRACE -> with bind values : P_ID=1
TRACE <- result           : [
TRACE                         'vip',
TRACE                         'newsletter'
TRACE                       ]
DEBUG <- completed        : 2 ms
```

Bound by position, the SQL stays on one line, since one `?` per line would show nothing. Either way, the driver
always receives the SQL on one line: the line breaks are only in the log.

## `OUT` values and failures

`OUT` values are logged on their own line, before the return value, and an `IN OUT` shows up in both directions. A
failure is logged on one line, without a stack trace, because the exception already reaches the caller:

<!-- snippet: out-and-failure -->
```text
DEBUG Read dictionary     : SHOP.ORDERS_PKG.APPLY_DISCOUNT in 31 ms
DEBUG Calling routine     : SHOP.ORDERS_PKG.APPLY_DISCOUNT <- Orders.applyDiscount
DEBUG -> SQL              : {call SHOP.ORDERS_PKG.APPLY_DISCOUNT(?)}
TRACE -> with bind values : P_TOTAL=200
TRACE <- OUT values       : P_TOTAL=180
DEBUG <- completed        : 2 ms
DEBUG Read dictionary     : SHOP.ORDERS_PKG.ORDER_STATUS in 9 ms
DEBUG Calling routine     : SHOP.ORDERS_PKG.ORDER_STATUS <- Orders.orderStatus
DEBUG -> SQL              : {? = call SHOP.ORDERS_PKG.ORDER_STATUS(?)}
TRACE -> with bind values : P_ORDER_ID=-1
DEBUG <- failed           : 1 ms: SQLException: ORA-01403: no data found | ORA-06512: at "SHOP.ORDERS_PKG", line 26 | ORA-06512: at line 1 | https://docs.oracle.com/error-help/db/ora-01403/
```

The message is the driver's, as it reaches the library. Driver 23 ends it with the link to the error's page;
drivers before it leave the link out.

## Good to know

- **Truncation:** text over 2000 characters and collections over 5 elements are truncated, with their size. LOBs and
  binary values are shown with their size only.
- **Cursors** are shown through the rows' `toString`, because a cursor cannot be read twice. A JavaBean without
  `toString` shows up as `Class@hash`.
- **One line, one record:** every log line, including the continuation of a collection or of the SQL, is a separate
  log record with the logger's prefix, so that `grep` still finds the logger and the level. That is also why this log
  is meant for development, staging and tests: in a log aggregator, one call becomes several records.
- **No forged lines:** a line break inside a value is logged as `\n`, so no value can create a line that looks like
  it came from Penitent.
- **One warning:** `io.github.rponte.penitent.call` also logs at `WARN`, once per method, when driver 23 runs against a
  database before 23ai and the method gets a `BOOLEAN` back, whose `null` that driver reads as `true` or `false`.
  [Database versions](compatibility.md#a-null-boolean-from-driver-23-before-23ai) shows the ways around it. The
  warning shows at the usual levels, `INFO` or `WARN`, without turning the call log on.
