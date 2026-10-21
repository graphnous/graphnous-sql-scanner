package dev.graphnous.scanner.sql;

import dev.graphnous.scanner.sql.model.ColumnChange;
import dev.graphnous.scanner.sql.model.ColumnDefinition;
import dev.graphnous.scanner.sql.model.ForeignKey;
import dev.graphnous.scanner.sql.model.Statement;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class StatementReaderTest {

    private final RecordingScanLog log = new RecordingScanLog();
    private final StatementReader reader = new StatementReader(log);

    @Test
    void readsTheColumnsOfACreateTable() {
        final var statement = one("""
            CREATE TABLE notification_recipients (
                id              UUID PRIMARY KEY,
                notification_id UUID NOT NULL REFERENCES notifications (id) ON DELETE CASCADE,
                user_id         UUID,
                is_read         BOOLEAN NOT NULL DEFAULT false,
                read_at         TIMESTAMP(6) WITH TIME ZONE,
                CONSTRAINT uq_notification_recipients UNIQUE NULLS NOT DISTINCT (notification_id, user_id)
            )""");

        assertThat(statement.getKind()).isEqualTo(Statement.Kind.CREATE_TABLE);
        assertThat(statement.getParsed()).isTrue();
        assertThat(statement.getTable()).isEqualTo("notification_recipients");
        assertThat(statement.getReferences()).containsExactly("notifications");
        assertThat(statement.getColumns())
            .extracting(ColumnDefinition::getName, ColumnDefinition::getType, ColumnDefinition::getNullable, ColumnDefinition::getPrimaryKey, ColumnDefinition::getDefault)
            .containsExactly(
                tuple("id", "uuid", false, true, null),
                tuple("notification_id", "uuid", false, null, null),
                tuple("user_id", "uuid", true, null, null),
                tuple("is_read", "boolean", false, null, "false"),
                tuple("read_at", "timestamp(6) with time zone", true, null, null)
            );

        final var foreignKey = statement.getColumns().get(1).getForeignKey();
        assertThat(foreignKey.getTable()).isEqualTo("notifications");
        assertThat(foreignKey.getColumn()).isEqualTo("id");
        assertThat(foreignKey.getOnDelete()).isEqualTo(ForeignKey.OnDelete.CASCADE);
    }

    @Test
    void appliesTheConstraintsOfTheTableToItsColumns() {
        final var statement = one("""
            CREATE TABLE IF NOT EXISTS billing.lines (
                invoice_id BIGINT,
                position   INT,
                code       VARCHAR(32),
                product_id BIGINT,
                PRIMARY KEY (invoice_id, position),
                UNIQUE (code),
                CONSTRAINT fk_product FOREIGN KEY (product_id) REFERENCES catalog.products (id) ON DELETE SET NULL
            )""");

        assertThat(statement.getTable()).isEqualTo("billing.lines");
        assertThat(statement.getIfExists()).isTrue();
        assertThat(statement.getReferences()).containsExactly("catalog.products");
        assertThat(statement.getColumns())
            .extracting(ColumnDefinition::getName, ColumnDefinition::getNullable, ColumnDefinition::getPrimaryKey, ColumnDefinition::getUnique)
            .containsExactly(
                tuple("invoice_id", false, true, null),
                tuple("position", false, true, null),
                tuple("code", true, null, true),
                tuple("product_id", true, null, null)
            );
        assertThat(statement.getColumns().get(3).getForeignKey())
            .extracting(ForeignKey::getTable, ForeignKey::getColumn, ForeignKey::getOnDelete)
            .containsExactly("catalog.products", "id", ForeignKey.OnDelete.SET_NULL);
    }

    @Test
    void readsColumnsNamedAfterKeywords() {
        // JSqlParser takes excludes for a keyword unless it is quoted
        final var added = one("ALTER TABLE scans ADD COLUMN excludes TEXT");
        final var created = one("CREATE TABLE scans (id UUID, excludes TEXT NOT NULL)");

        assertThat(added.getParsed()).isTrue();
        assertThat(added.getChanges()).extracting(ColumnChange::getKind, ColumnChange::getColumn)
            .containsExactly(tuple(ColumnChange.Kind.ADD, "excludes"));
        assertThat(created.getParsed()).isTrue();
        assertThat(created.getColumns()).extracting(ColumnDefinition::getName, ColumnDefinition::getNullable)
            .containsExactly(tuple("id", true), tuple("excludes", false));
    }

    @Test
    void readsTablesNamedAfterKeywords() {
        // JSqlParser gives up on settings unless it is quoted
        final var created = one("CREATE TABLE settings (id INT PRIMARY KEY, owner_id INT REFERENCES settings (id) ON DELETE CASCADE)");
        final var altered = one("ALTER TABLE ONLY public.settings ADD COLUMN note TEXT");
        final var indexed = one("CREATE INDEX ix_settings ON settings (owner_id)");

        assertThat(created).extracting(Statement::getKind, Statement::getTable, Statement::getParsed)
            .containsExactly(Statement.Kind.CREATE_TABLE, "settings", true);
        assertThat(created.getColumns().get(1).getForeignKey().getOnDelete()).isEqualTo(ForeignKey.OnDelete.CASCADE);
        assertThat(altered).extracting(Statement::getKind, Statement::getTable, Statement::getParsed)
            .containsExactly(Statement.Kind.ALTER_TABLE, "public.settings", true);
        assertThat(indexed).extracting(Statement::getKind, Statement::getTable, Statement::getParsed)
            .containsExactly(Statement.Kind.CREATE_INDEX, "settings", true);
    }

    @Test
    void quotesOnlyNamesOfTables() {
        assertThat(StatementReader.quoteTableNames("CREATE TABLE IF NOT EXISTS billing.settings (a INT REFERENCES b (id) ON DELETE CASCADE)"))
            .isEqualTo("CREATE TABLE IF NOT EXISTS \"billing\".\"settings\" (a INT REFERENCES \"b\" (id) ON DELETE CASCADE)");
        assertThat(StatementReader.quoteTableNames("ALTER TABLE \"settings\" ADD COLUMN a INT"))
            .isEqualTo("ALTER TABLE \"settings\" ADD COLUMN a INT");
    }

    @Test
    void readsWhatAnAlterTableChangesOfTheColumns() {
        final var statement = one("""
            ALTER TABLE scans
                ADD COLUMN base_scan_id UUID REFERENCES scans (id) ON DELETE SET NULL,
                DROP COLUMN IF EXISTS legacy,
                ALTER COLUMN status TYPE VARCHAR(64),
                ALTER COLUMN status SET NOT NULL,
                ALTER COLUMN note DROP NOT NULL,
                ALTER COLUMN status SET DEFAULT 'PENDING',
                ALTER COLUMN note DROP DEFAULT""");

        assertThat(statement.getKind()).isEqualTo(Statement.Kind.ALTER_TABLE);
        assertThat(statement.getTable()).isEqualTo("scans");
        assertThat(statement.getChanges()).extracting(ColumnChange::getKind, ColumnChange::getColumn).containsExactly(
            tuple(ColumnChange.Kind.ADD, "base_scan_id"),
            tuple(ColumnChange.Kind.DROP, "legacy"),
            tuple(ColumnChange.Kind.ALTER_TYPE, "status"),
            tuple(ColumnChange.Kind.SET_NOT_NULL, "status"),
            tuple(ColumnChange.Kind.DROP_NOT_NULL, "note"),
            tuple(ColumnChange.Kind.SET_DEFAULT, "status"),
            tuple(ColumnChange.Kind.DROP_DEFAULT, "note")
        );

        final var added = statement.getChanges().get(0).getDefinition();
        assertThat(added.getType()).isEqualTo("uuid");
        assertThat(added.getNullable()).isTrue();
        assertThat(added.getForeignKey().getOnDelete()).isEqualTo(ForeignKey.OnDelete.SET_NULL);
        assertThat(statement.getChanges().get(2).getDefinition().getType()).isEqualTo("varchar(64)");
        assertThat(statement.getChanges().get(2).getDefinition().getNullable()).isNull();
        assertThat(statement.getChanges().get(5).getDefinition().getDefault()).isEqualTo("'PENDING'");
        // Its own table is not another one it depends on
        assertThat(statement.getReferences()).isEmpty();
    }

    @Test
    void readsAddedForeignKeys() {
        final var statement = one("ALTER TABLE scans ADD CONSTRAINT fk_project FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE");

        assertThat(statement.getChanges()).singleElement().satisfies(change -> {
            assertThat(change.getKind()).isEqualTo(ColumnChange.Kind.ADD_FOREIGN_KEY);
            assertThat(change.getColumn()).isEqualTo("project_id");
            assertThat(change.getDefinition().getForeignKey())
                .extracting(ForeignKey::getTable, ForeignKey::getColumn, ForeignKey::getOnDelete)
                .containsExactly("projects", "id", ForeignKey.OnDelete.CASCADE);
        });
        assertThat(statement.getReferences()).containsExactly("projects");
    }

    @Test
    void readsRenames() {
        final var column = one("ALTER TABLE scans RENAME COLUMN status TO state");
        final var table = one("ALTER TABLE scans RENAME TO scan_runs");

        assertThat(column.getChanges()).extracting(ColumnChange::getKind, ColumnChange::getColumn, ColumnChange::getNewName)
            .containsExactly(tuple(ColumnChange.Kind.RENAME, "status", "state"));
        assertThat(table.getKind()).isEqualTo(Statement.Kind.RENAME_TABLE);
        assertThat(table.getTable()).isEqualTo("scans");
        assertThat(table.getNewName()).isEqualTo("scan_runs");
    }

    @Test
    void readsAChangeOfMySql() {
        final var statement = one("ALTER TABLE scans CHANGE status state VARCHAR(16) NOT NULL");

        assertThat(statement.getChanges()).extracting(ColumnChange::getKind, ColumnChange::getColumn, ColumnChange::getNewName).containsExactly(
            tuple(ColumnChange.Kind.RENAME, "status", "state"),
            tuple(ColumnChange.Kind.ALTER_TYPE, "state", null)
        );
        assertThat(statement.getChanges().get(1).getDefinition().getNullable()).isFalse();
    }

    @Test
    void readsOneDropForEachTable() {
        final var statements = read("DROP TABLE IF EXISTS scans, \"Old Scans\" CASCADE");

        assertThat(statements).extracting(Statement::getKind, Statement::getTable, Statement::getIfExists).containsExactly(
            tuple(Statement.Kind.DROP_TABLE, "scans", true),
            tuple(Statement.Kind.DROP_TABLE, "Old Scans", true)
        );
    }

    @Test
    void readsDropsOfViewsAndIndexes() {
        assertThat(one("DROP VIEW open_scans").getKind()).isEqualTo(Statement.Kind.DROP_VIEW);
        assertThat(one("DROP MATERIALIZED VIEW totals").getKind()).isEqualTo(Statement.Kind.DROP_VIEW);

        final var index = one("DROP INDEX IF EXISTS idx_scans_status");
        assertThat(index.getKind()).isEqualTo(Statement.Kind.DROP_INDEX);
        // It names the index, not its table
        assertThat(index.getTable()).isNull();
    }

    @Test
    void readsTheTablesAViewReads() {
        final var statement = one("""
            CREATE OR REPLACE VIEW open_scans AS
            SELECT s.id, p.name FROM scans s JOIN projects p ON p.id = s.project_id WHERE s.status <> 'COMPLETED'""");

        assertThat(statement.getKind()).isEqualTo(Statement.Kind.CREATE_VIEW);
        assertThat(statement.getTable()).isEqualTo("open_scans");
        assertThat(statement.getReferences()).containsExactly("projects", "scans");
    }

    @Test
    void readsTheColumnsOfAnIndex() {
        final var statement = one("CREATE UNIQUE INDEX ux_projects ON projects USING btree (lower(name), system_id) WHERE system_id IS NOT NULL");

        assertThat(statement.getKind()).isEqualTo(Statement.Kind.CREATE_INDEX);
        assertThat(statement.getTable()).isEqualTo("projects");
        // Not the expression
        assertThat(statement.getColumns()).extracting(ColumnDefinition::getName).containsExactly("system_id");
    }

    @Test
    void readsIndexesJSqlParserDoesNot() {
        final var created = one("""
            CREATE INDEX IF NOT EXISTS idx_users_txt ON public.users USING gin (
                to_tsvector('english', username || ' ' || firstname), deleteat DESC, "Email" text_pattern_ops
            )""");
        final var dropped = read("DROP INDEX CONCURRENTLY IF EXISTS idx_a, idx_b");

        assertThat(created).extracting(Statement::getKind, Statement::getTable, Statement::getParsed)
            .containsExactly(Statement.Kind.CREATE_INDEX, "public.users", true);
        assertThat(created.getColumns()).extracting(ColumnDefinition::getName).containsExactly("deleteat", "Email");
        assertThat(dropped).extracting(Statement::getKind, Statement::getTable, Statement::getIfExists, Statement::getParsed)
            .containsExactly(
                tuple(Statement.Kind.DROP_INDEX, null, true, true),
                tuple(Statement.Kind.DROP_INDEX, null, true, true)
            );
    }

    @Test
    void keepsOtherStatementsWithoutParsingThem() {
        for (final var sql : List.of(
            "INSERT INTO notification_recipients (id) SELECT gen_random_uuid() FROM notifications WHERE is_read",
            "UPDATE scans SET git_requested_revision = git_revision",
            "CREATE FUNCTION touch() RETURNS trigger AS $$ BEGIN RETURN NEW; END; $$ LANGUAGE plpgsql",
            "CREATE EXTENSION IF NOT EXISTS pgcrypto",
            "GRANT SELECT ON scans TO reader"
        )) {
            assertThat(one(sql)).extracting(Statement::getKind, Statement::getParsed).containsExactly(Statement.Kind.OTHER, true);
        }
    }

    @Test
    void keepsAStatementItCannotReadAsNotParsed() {
        final var statement = reader.read(new SqlSplitter.Piece("CREATE TABLE broken (id UUID PRIMARY KEY,,, oops", 3, 4)).getFirst();

        assertThat(statement.getKind()).isEqualTo(Statement.Kind.OTHER);
        assertThat(statement.getParsed()).isFalse();
        assertThat(statement.getStartLine()).isEqualTo(3);
        assertThat(statement.getEndLine()).isEqualTo(4);
    }

    @Test
    void leavesOutTheDefaultsOfSecrets() {
        final var statement = one("""
            CREATE TABLE settings (
                api_token  TEXT DEFAULT 'abc123',
                url        TEXT DEFAULT 'jdbc:postgresql://app:secret@db/orders',
                retries    INT DEFAULT 3
            )""");

        assertThat(statement.getColumns()).extracting(ColumnDefinition::getName, ColumnDefinition::getDefault).containsExactly(
            tuple("api_token", null),
            tuple("url", null),
            tuple("retries", "3")
        );
    }

    @Test
    void unquotesNames() {
        assertThat(StatementReader.qualified("\"billing\".\"invoices\"")).isEqualTo("billing.invoices");
        assertThat(StatementReader.qualified("`orders`")).isEqualTo("orders");
        assertThat(StatementReader.qualified("[dbo].[orders]")).isEqualTo("dbo.orders");
        assertThat(StatementReader.qualified("\"a.b\"")).isEqualTo("a.b");
    }

    private Statement one(final String sql) {
        final var statements = read(sql);

        assertThat(statements).hasSize(1);

        return statements.getFirst();
    }

    private List<Statement> read(final String sql) {
        return reader.read(new SqlSplitter.Piece(sql, 1, (int) sql.lines().count()));
    }
}
