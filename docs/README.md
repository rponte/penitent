# Documentation

New to Penitent? Start with [Getting started](getting-started.md): in about ten minutes, it calls a real PL/SQL
package from plain Java and from Spring Boot. The pages below cover each feature in depth.

## Guides

| Page | What it covers |
|---|---|
| [Calling routines](routines.md) | Packages and standalone routines, names, parameters by position and by name, `DEFAULT`, overloads, `OUT` and `IN OUT` |
| [Types](types.md) | Scalars, booleans and time zones, OBJECT, RECORD and `%ROWTYPE`, collections and cursors |
| [Anonymous blocks](anonymous-blocks.md) | `@PlsqlBlock`, for whatever is not a `package.routine` call |
| [Converters](converters.md) | Your own Java types, and enums stored by code |
| [Settings](settings.md) | `PlsqlSettings`: what applies to every interface |
| [Errors](errors.md) | Which exception arrives, and how to translate it |
| [Validation](validation.md) | Checking every interface against the database up front |
| [Spring and Spring Boot](spring.md) | Transactions, Spring exceptions, interfaces as beans and the starter |
| [Logging](logging.md) | What each call logs, and at which level |

## Reference

| Page | What it covers |
|---|---|
| [Compatibility](compatibility.md) | Every Oracle and Java type pair and behavior, and whether a test covers it |
| [Roadmap](roadmap.md) | What comes next, what waits for a use case, and what is left out by design |
| Javadoc | Built with each module, in its `-javadoc.jar`, with `./mvnw install` |
