package dev.graphnous.scanner.sql;

import dev.graphnous.scanner.sql.model.Migration;

import java.util.regex.Pattern;

/**
 * Where a migration falls among the others, from the name of its file, as
 * Flyway names them by default: {@code V13__notification_recipients.sql}
 * for a versioned one, {@code U13__...} for its undo and {@code R__...} for
 * a repeatable one.
 */
final class Migrations {

    private static final Pattern VERSIONED = Pattern.compile("([VU])(\\d+(?:[._]\\d+)*)__(.*)\\.sql", Pattern.CASE_INSENSITIVE);

    private static final Pattern REPEATABLE = Pattern.compile("R__(.*)\\.sql", Pattern.CASE_INSENSITIVE);

    private Migrations() {
    }

    /**
     * The migration a file of this name is; null for one that is not named
     * as a migration.
     */
    static Migration of(final String fileName) {
        final var versioned = VERSIONED.matcher(fileName);

        if (versioned.matches() && Character.isUpperCase(fileName.charAt(0))) {
            final var migration = new Migration();
            migration.setKind(versioned.group(1).equals("V") ? Migration.Kind.VERSIONED : Migration.Kind.UNDO);
            // Flyway takes underscores in a version as dots, so V2_1 is 2.1
            migration.setVersion(versioned.group(2).replace('_', '.'));
            migration.setDescription(description(versioned.group(3)));

            return migration;
        }

        final var repeatable = REPEATABLE.matcher(fileName);

        if (repeatable.matches() && fileName.charAt(0) == 'R') {
            final var migration = new Migration();
            migration.setKind(Migration.Kind.REPEATABLE);
            migration.setDescription(description(repeatable.group(1)));

            return migration;
        }

        return null;
    }

    private static String description(final String written) {
        return written.replace('_', ' ').strip();
    }
}
