package dev.graphnous.scanner.sql;

import dev.graphnous.scanner.sql.model.Migration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationsTest {

    @Test
    void readsAVersionedMigration() {
        final var migration = Migrations.of("V13__create_notification_recipients.sql");

        assertThat(migration.getKind()).isEqualTo(Migration.Kind.VERSIONED);
        assertThat(migration.getVersion()).isEqualTo("13");
        assertThat(migration.getDescription()).isEqualTo("create notification recipients");
    }

    @Test
    void takesUnderscoresInAVersionAsDots() {
        assertThat(Migrations.of("V2_1__split.sql").getVersion()).isEqualTo("2.1");
        assertThat(Migrations.of("V2.10__split.sql").getVersion()).isEqualTo("2.10");
    }

    @Test
    void readsUndoAndRepeatableMigrations() {
        final var undo = Migrations.of("U13__create_notification_recipients.sql");
        final var repeatable = Migrations.of("R__views.sql");

        assertThat(undo.getKind()).isEqualTo(Migration.Kind.UNDO);
        assertThat(undo.getVersion()).isEqualTo("13");
        assertThat(repeatable.getKind()).isEqualTo(Migration.Kind.REPEATABLE);
        assertThat(repeatable.getVersion()).isNull();
        assertThat(repeatable.getDescription()).isEqualTo("views");
    }

    @Test
    void findsNoMigrationInOtherNames() {
        assertThat(Migrations.of("schema.sql")).isNull();
        assertThat(Migrations.of("v1__lower_case.sql")).isNull();
        assertThat(Migrations.of("V1_missing_separator.sql")).isNull();
        assertThat(Migrations.of("V1__notes.txt")).isNull();
    }
}
