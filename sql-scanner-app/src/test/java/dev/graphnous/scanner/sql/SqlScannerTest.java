package dev.graphnous.scanner.sql;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.graphnous.scanner.sql.model.File;
import dev.graphnous.scanner.sql.model.Migration;
import dev.graphnous.scanner.sql.model.ScanTarget;
import dev.graphnous.scanner.sql.model.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class SqlScannerTest {

    @TempDir
    Path repository;

    private final RecordingScanLog log = new RecordingScanLog();

    @Test
    void scansTheMigrationsOfATarget() throws IOException {
        write("src/main/resources/db/migration/V1__create_scans.sql", """
            CREATE TABLE scans (
                id BIGSERIAL PRIMARY KEY,
                status TEXT NOT NULL
            );
            """);
        write("src/main/resources/db/migration/V2__add_scan_notes.sql", "ALTER TABLE scans ADD COLUMN note TEXT;\n");
        write("src/main/resources/db/migration/R__views.sql", "CREATE OR REPLACE VIEW open_scans AS SELECT id FROM scans;\n");

        final var result = scan("src/main/resources/db/migration", null);
        final var module = result.getModules().getFirst();

        assertThat(result.getFormat()).isEqualTo("graphnous-scan-result");
        assertThat(result.getTarget().getLanguage()).isEqualTo(ScanTarget.Language.SQL);
        assertThat(result.getTarget().getDialect()).isEqualTo(ScanTarget.Dialect.POSTGRESQL);
        assertThat(module.getName()).isEqualTo("migration");
        assertThat(module.getFiles())
            .extracting(File::getPath, File::getLanguage, File::getSourceSet, file -> file.getMigration().getKind(), File::getLineCount)
            .containsExactly(
                tuple("R__views.sql", "SQL", File.SourceSet.MAIN, Migration.Kind.REPEATABLE, 1),
                tuple("V1__create_scans.sql", "SQL", File.SourceSet.MAIN, Migration.Kind.VERSIONED, 4),
                tuple("V2__add_scan_notes.sql", "SQL", File.SourceSet.MAIN, Migration.Kind.VERSIONED, 1)
            );
        assertThat(module.getFiles().get(1).getStatements())
            .extracting(Statement::getKind, Statement::getTable, Statement::getStartLine, Statement::getEndLine)
            .containsExactly(tuple(Statement.Kind.CREATE_TABLE, "scans", 1, 4));
        assertThat(module.getFiles().get(1).getChecksum()).hasSize(64);
    }

    @Test
    void keepsTheDialectItIsGiven() throws IOException {
        write("V1__create.sql", "CREATE TABLE a (id BIGSERIAL PRIMARY KEY);\n");

        assertThat(scan(".", ScanTarget.Dialect.H_2).getTarget().getDialect()).isEqualTo(ScanTarget.Dialect.H_2);
    }

    @Test
    void scansSqlFilesOutsideBuildOutputAndTellsTestCodeApart() throws IOException {
        write("src/main/resources/db/migration/V1__create.sql", "CREATE TABLE a (id INT);\n");
        write("src/test/resources/fixtures/data.sql", "INSERT INTO a VALUES (1);\n");
        write("target/classes/db/migration/V1__create.sql", "CREATE TABLE a (id INT);\n");
        write("node_modules/some/schema.sql", "CREATE TABLE b (id INT);\n");
        write("docs/schema.SQL", "CREATE TABLE c (id INT);\n");

        final var files = scan(".", null).getModules().getFirst().getFiles();

        assertThat(files).extracting(File::getPath, File::getSourceSet, File::getMigration).containsExactly(
            tuple("docs/schema.SQL", null, null),
            tuple("src/main/resources/db/migration/V1__create.sql", File.SourceSet.MAIN, files.get(1).getMigration()),
            tuple("src/test/resources/fixtures/data.sql", File.SourceSet.TEST, null)
        );
        assertThat(files.get(1).getMigration().getVersion()).isEqualTo("1");
    }

    @Test
    void warnsOfStatementsItCannotRead() throws IOException {
        write("V1__broken.sql", "CREATE TABLE ok (id INT);\n\nCREATE TABLE broken (id INT,,);\n");

        final var statements = scan(".", null).getModules().getFirst().getFiles().getFirst().getStatements();

        assertThat(statements).extracting(Statement::getKind, Statement::getParsed, Statement::getStartLine)
            .containsExactly(tuple(Statement.Kind.CREATE_TABLE, true, 1), tuple(Statement.Kind.OTHER, false, 3));
        assertThat(log.warnings).containsExactly("  V1__broken.sql:3: could not read the statement, kept as OTHER");
    }

    @Test
    void failsForATargetThatIsNotADirectory() {
        assertThatThrownBy(() -> scan("missing", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not a directory");
    }

    @Test
    void writesWhatIsEmptyNowhere() throws IOException {
        write("V1__add.sql", "ALTER TABLE a ADD COLUMN b TEXT;\n");

        final var output = repository.resolve("scan-result.json");
        new ScanResultWriter(new ObjectMapper()).write(scan(".", null), output);

        final var json = new ObjectMapper().readTree(output.toFile());
        final var file = json.at("/modules/0/files/0");
        final var statement = file.at("/statements/0");

        assertThat(file.has("classes")).isFalse();
        assertThat(file.has("calls")).isFalse();
        assertThat(statement.has("columns")).isFalse();
        assertThat(statement.has("references")).isFalse();
        assertThat(statement.get("parsed").asBoolean()).isTrue();
        assertThat(statement.at("/changes/0/definition/name").asText()).isEqualTo("b");
    }

    @Test
    void countsLinesAsTheyEnd() {
        assertThat(SqlScanner.lineCount(new byte[0])).isZero();
        assertThat(SqlScanner.lineCount("a".getBytes())).isEqualTo(1);
        assertThat(SqlScanner.lineCount("a\n".getBytes())).isEqualTo(1);
        assertThat(SqlScanner.lineCount("a\r\nb".getBytes())).isEqualTo(2);
    }

    private dev.graphnous.scanner.sql.model.ScanResultSchema scan(final String path, final ScanTarget.Dialect dialect) {
        final var target = new ScanTarget();
        target.setPath(path);
        target.setLanguage(ScanTarget.Language.SQL);
        target.setLanguageVersion("unknown");
        target.setDialect(dialect);

        return new SqlScanner(log).scan(repository, target);
    }

    private void write(final String path, final String content) throws IOException {
        final var file = repository.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
