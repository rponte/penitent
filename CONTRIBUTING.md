# Contributing

Thank you for considering a contribution. This page explains how to build Penitent, the rules the code follows, and
how to propose a change.

## Building

You need Java 21 and Docker. The tests start their own Oracle through Testcontainers, so there is nothing to install
or configure:

```
./mvnw verify
```

The first run downloads the Oracle image (`gvenzl/oracle-free`, about 1 GB compressed); after that the build takes
under a minute. The CI runs the same command.

The build always runs in the `en-US` locale and the `UTC` time zone, whatever your machine uses, so its result does
not depend on where it runs. The hostile cases are covered on purpose, by tests, as described below.

## Rules

- **Everything in English:** code, identifiers, messages, logs, Javadoc, comments, tests and documentation.
- **Strict:** when the Java side and the database disagree, the library fails with a message that says what it looked
  up and what exists. It never guesses, and never converts a value in a way that could lose information without an
  error.
- **Every behavior has a test against a real Oracle.** The integration tests live in
  `penitent-core/src/test/java/io/github/rponte/penitent/integration`, outside the library's package, so they only see
  the public API, as any user does. The PL/SQL they call is in `penitent-core/src/test/resources/db/fixtures`, as
  Flyway migrations: add a new file for a new feature.
- **The compatibility table changes in the same commit as the behavior.** Each row of
  [`docs/compatibility.md`](docs/compatibility.md) is backed by a test; add or update the row with the change.
- **The documentation's examples have tests.** Each example in `docs/`, and each message a page quotes, has a copy
  in a `Docs*Test` class, which runs it against the fixtures from `V15__documentation_examples.sql` on. The build only
  runs the copy and does not read the page, so change the page and its test in the same commit, and give a new
  example a test before it goes on a page.
- **Locale and time zone:** the driver derives the session's language, territory and time zone from the JVM. A new path
  where a value may cross the driver as text, or where a date or time crosses it, gets a case in `LocaleTest` or in
  `JvmTimeZoneTest`, which run the hostile settings explicitly. The build also runs
  [forbiddenapis](https://github.com/policeman-tools/forbidden-apis), which fails on any API that depends on the
  default locale, charset or time zone: pass `Locale.ROOT`, a charset or a zone explicitly.
- **Assertions on dates with a time zone:** AssertJ compares `ZonedDateTime` and `OffsetDateTime` by instant, so a lost
  region or offset would pass. Compare them with `equals`, as the `assertExactly` helpers do.
- **Public API and internals:** what is under `io.github.rponte.penitent.internal` may change in any version, and is
  left out of the Javadoc. Everything else is API: it needs Javadoc, and the build fails on any Javadoc problem except
  missing `@param` and `@return` tags.
- **First make it work, then make it fast.** An optimization comes with a test that measures it, as
  `DictionaryQueriesTest` does for the queries to the dictionary.

## Proposing a change

- For anything beyond a small fix, open an issue first, so the design can be agreed before the code.
- Keep a pull request to one topic. The CI must pass.
- Write the commit message as a short subject in the imperative, then a body that explains why.
- Add a line to the `Unreleased` section of [`CHANGELOG.md`](CHANGELOG.md) when the change is visible to users.

## Reporting a bug

Open an issue with the Oracle, driver and Java versions, the PL/SQL signature, the Java interface, and the full
message of the exception. If the bug is a security vulnerability, follow [`SECURITY.md`](SECURITY.md) instead.

## License

By contributing, you agree that your contributions are licensed under the [Apache License 2.0](LICENSE).
