# GraphNous SQL Scanner

Scans the SQL files of a project, such as its Flyway migrations, and writes a
[GraphNous scan result](https://graphnous.github.io/graphnous-schemas/schema/scan/scan-result.schema.json):
each file with its statements, as what they do to the tables of the
database: the tables, views and indexes they create, change and drop, with
their columns, types, keys and foreign keys.

The scanner records what each statement does, not the tables the database
ends up with: the Graphnous server applies the migrations in order to know
those, and compares them between scans.

The Graphnous server runs it on the repositories it analyses; it can also
be run on its own, as a jar or as an image. It needs no database and no
network.

## Running

```sh
docker run --rm \
  -v "$PWD:/repository:ro" -v "$PWD/out:/output" \
  ghcr.io/graphnous/graphnous-sql-scanner:0.1.0 \
  --path /repository --target src/main/resources/db/migration \
  --output /output/scan-result.json
```

or, after [building](#building) it:

```sh
java -jar sql-scanner-app/target/sql-scanner.jar \
  --path . --target src/main/resources/db/migration --output scan-result.json
```

| Argument | |
|---|---|
| `--path <dir>` | The repository to scan. Required. |
| `--target <path>` | The folder of SQL files in the repository, relative to it. Default `.`. |
| `--output <file>` | Where to write the scan result. Required. |
| `--dialect <name>` | The database the SQL is written for: `POSTGRESQL`, `MYSQL`, `MARIADB`, `ORACLE`, `SQLSERVER`, `H2` or `SQLITE`. Default: guessed from the SQL; see below. |
| `--dialect-version <version>` | The version of that database, e.g. `17`, as the result's `languageVersion`. Default `unknown`. |
| `--verbose` | Log details, such as each file scanned. |

An empty `--dialect` or `--dialect-version` is taken as not given, so a
caller can always pass them.

## What it finds

- The `.sql` files under the target, sorted by path, outside folders of
  build output and dependencies such as `target`, `build` and
  `node_modules`; as production or test code from where they are in the
  repository, e.g. `src/main` or `src/test`.
- For a migration, where it falls among the others, from its name as Flyway
  names them: `V13__name.sql` (versioned, `V2_1` being version `2.1`),
  `U13__name.sql` (undo) and `R__name.sql` (repeatable).
- The statements of each file, in order, with the lines they are on:
  - `CREATE TABLE`, with its columns: their types as written in lower case,
    e.g. `timestamp(6) with time zone`, whether they take null, their
    defaults, primary keys, unique columns and foreign keys, also those of
    the constraints of the table; and the tables it references.
  - `ALTER TABLE`, with what it changes of the columns: added, dropped,
    renamed, a new type, `NOT NULL` and defaults set or dropped, foreign
    keys added. Renaming the table is a `RENAME_TABLE` of its own.
  - `DROP TABLE`, one for each table it drops; `CREATE VIEW` with the
    tables it reads and `DROP VIEW`, also materialized ones;
    `CREATE INDEX` with the columns it indexes, but not expressions such as
    `lower(name)`, and `DROP INDEX`.
  - Any other statement, such as `INSERT`, `GRANT` or `CREATE FUNCTION`, as
    `OTHER`.

Names are kept as written, without their quotes, and with their schema when
written so, e.g. `billing.invoices`.

The defaults of columns named for a secret, such as `api_token`, and
defaults that hold one, such as a url with a password, are left out, as the
scan result schema describes; the values of `INSERT` statements are never
read.

### Statements it cannot read

The statements are parsed with [JSqlParser](https://github.com/JSQLParser/JSqlParser).
A statement it cannot read is kept, with its lines, as `OTHER` with `parsed`
set to `false`, and the scanner warns of it, so the server can say what it
did not understand rather than guess. Before giving up, the scanner:

- quotes the names of tables and columns and tries again, as JSqlParser
  takes some names, such as `settings` or `excludes`, for keywords;
- reads `CREATE INDEX` and `DROP INDEX` statements itself, as JSqlParser
  does not read some of them, such as `DROP INDEX CONCURRENTLY` and indexes
  of expressions such as `to_tsvector(...)`.

A statement whose table JSqlParser reads differently from the name written
after `TABLE`, as it does for `ALTER TABLE ONLY public.settings`, is read
again with its names quoted, and not read when that does not help either.

Statements are split at semicolons outside comments, strings, quoted names
and the dollar-quoted bodies of PostgreSQL functions. Not supported: the
`GO` separators of SQL Server, `DELIMITER` of MySQL, PL/SQL blocks of Oracle
with semicolons inside, Liquibase changelogs, and `ALTER TABLE IF EXISTS
ONLY`, which JSqlParser does not read.

### The dialect

Without `--dialect`, the scanner guesses the database from what only it
writes, such as `BIGSERIAL` or `JSONB` for PostgreSQL, `AUTO_INCREMENT` or
backticks for MySQL, `NVARCHAR(MAX)` for SQL Server and `VARCHAR2` for
Oracle; it leaves the dialect out when the SQL does not tell, or tells two
databases as often.

## Building

Requires Java 25.

```sh
./mvnw verify
```

The scan result model is generated from the schema at
`graphnous.schema.url` in `pom.xml`; to build against another copy, pass
`-Dgraphnous.schema.url=file:///path/to/scan-result.schema.json`.

The jar is `sql-scanner-app/target/sql-scanner.jar`. Pushing a `v*` tag,
e.g. `v0.1.0`, publishes the image as
`ghcr.io/graphnous/graphnous-sql-scanner:0.1.0`.
