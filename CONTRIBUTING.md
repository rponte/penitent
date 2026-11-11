# Contributing

Thank you for considering a contribution. This page explains how to build Penitent, the rules the code follows, and
how to propose a change.

## Building

You need Java 17 or later and Docker. The tests start their own Oracle through Testcontainers, so there is nothing to install
or configure:

```
./mvnw verify
```

The first run downloads the Oracle image (`gvenzl/oracle-free`, about 1 GB compressed); after that the build takes
under a minute. The CI runs the same command.

The build always runs in the `en-US` locale and the `UTC` time zone, whatever your machine uses, so its result does
not depend on where it runs. The hostile cases are covered on purpose, by tests, as described below.

### Another JDBC driver

The Oracle JDBC driver is `provided`: the application chooses it, so the build runs against more than one. Two
properties pick it, and they are about different things:

| Property | What it chooses | Default |
|---|---|---|
| `oracle.jdbc.artifact` | the driver's artifact: `ojdbc17`, `ojdbc11`, `ojdbc10` or `ojdbc8`. The number is the Java version the jar is built for | `ojdbc17` |
| `oracle.jdbc.version` | the version of that jar, such as `21.24.0.0` or `19.33.0.0` | the Spring Boot BOM's, through its `oracle-database.version` property, which is the driver's version despite its name |

```
./mvnw verify -Doracle.jdbc.artifact=ojdbc11                                  # another artifact, same version
./mvnw verify -Doracle.jdbc.artifact=ojdbc11 -Doracle.jdbc.version=21.24.0.0  # an older driver
./mvnw verify -Doracle.jdbc.artifact=ojdbc8 -Doracle.jdbc.version=19.33.0.0   # the oldest one the build tests
```

Neither changes the database the tests connect to: see the next section. Each driver version comes in its own
artifacts: 23 in `ojdbc17`, `ojdbc11` and `ojdbc8`, 21 in `ojdbc11` and `ojdbc8`, and 19 in `ojdbc10` and `ojdbc8`.

Where an older driver behaves differently, the library takes one code path for every version, usually by reading or
building the bytes of Oracle's types itself, as the `*Codec` classes in `internal.convert` do. Their Javadoc says
which driver needs it and why. A test named `same_as_driver_23` compares each codec with driver 23, the reference,
through `Driver23` in the tests, which calls driver 23 by reflection so that the tests still compile on older drivers,
where they skip themselves.

### Another database

The tests start Oracle 23ai by default. The `oracle.database.image` property starts another image instead, with any
driver:

```
./mvnw verify -Doracle.database.image=gvenzl/oracle-xe:21-slim-faststart                             # Oracle 21c
./mvnw verify -Doracle.database.image=gvenzl/oracle-xe:21-slim-faststart -Doracle.jdbc.artifact=ojdbc11 -Doracle.jdbc.version=21.24.0.0
./mvnw verify -Doracle.database.image=ghcr.io/oracle/adb-free:latest                                 # Oracle 19c
```

The 21c and 19c images are only for `amd64`: on an Apple Silicon machine they run emulated, slower but fine. The 19c
image is Oracle's Autonomous Database Free: it asks for 4 CPUs and 8 GB of memory, so give Docker at least that, and
it takes about a minute and a half to start, each time a module's tests begin. `OracleUnderTest`, in the tests, starts
either kind of image and creates on the Autonomous Database the user that the other images create themselves. A fixture or a test
that needs what only 23ai has, such as SQL `BOOLEAN`, checks the database's version: the fixture with
`DBMS_DB_VERSION.VERSION`, as `V3__additional_types.sql` does, and the test with `assumeSqlBoolean()`, so that it
skips itself on an older database.

### The documentation site

The pages in `docs/` are plain Markdown, and [Zensical](https://zensical.org) builds them into the site, with the
navigation in `zensical.toml`. To see the site while you edit, you need Python 3:

```
scripts/docs.sh          # http://localhost:8000/penitent/, rebuilt on each change
scripts/docs.sh build    # into site/, failing on a broken link or anchor, as the CI does
scripts/docs.sh api      # regenerates the Javadoc at /api, after a change to the code
```

The first run creates a virtual environment in `.venv/`, ignored by git, with the version pinned in
`requirements-docs.txt`. Nothing is installed outside the repository. The site includes the aggregated Javadoc of the
modules at `/api`, generated into `docs/api/`, also ignored by git: `serve` generates it when it is missing, and
`build` always does. Run a single server at a time: a second `serve`, or a `build` while one serves, clears `site/`
under the first one.

The examples on the pages are checked by `./mvnw verify`, not by the site's build: see the rule on the
documentation's examples below.

The `Documentation` workflow builds the site on every push and pull request, and publishes it to
[rponte.github.io/penitent](https://rponte.github.io/penitent/) only after the `Release` workflow of a version tag
succeeds, so the site always describes the latest released version. Run it by hand from a tag to publish that tag's
site again.

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
