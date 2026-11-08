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

### The documentation site

The pages in `docs/` are plain Markdown, and [Zensical](https://zensical.org) builds them into the site, with the
navigation in `zensical.toml`. To see the site while you edit, you need Python 3:

```
scripts/docs.sh          # http://localhost:8000/penitent/, rebuilt on each change
scripts/docs.sh build    # into site/, failing on a broken link or anchor, as the CI does
```

The first run creates a virtual environment in `.venv/`, ignored by git, with the version pinned in
`requirements-docs.txt`. Nothing is installed outside the repository. Run a single server at a time: a second
`serve` fails on the port, but clears `site/` first, and the first one then serves an empty folder.

The examples on the pages are checked by `./mvnw verify`, not by the site's build: see the rule on the
documentation's examples below.

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
- **The documentation's examples are checked by the build.** Each block in `docs/` is preceded by a hidden
  `<!-- snippet: name -->` that says where it comes from:
  - a `java` block is the region `// snippet: name` … `// end-snippet` of a `Docs*Test` class, and must match it;
  - a `text` block, such as an error message or a log, is read by a test with `Docs.assertPagesShow("page.md#name")`,
    which compares it with what the library printed;
  - an `sql` block is named `fixture`, and its lines must exist in the fixtures from `V15__documentation_examples.sql` on;
  - anything else is named `untested`.

  `DocsPagesTest` fails on a block without a name, a page that differs from its region, and a region no page shows.
  To change an example, change the region and the page together; to add one, write the region first.
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
