package dev.graphnous.scanner.sql;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.graphnous.scanner.sql.model.ScanTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MainTest {

    @TempDir
    Path directory;

    @Test
    void readsTheArguments() {
        final var options = Main.parseArguments(new String[] {
            "--path", "repository",
            "--target", "src/main/resources/db/migration",
            "--output", "out.json",
            "--dialect", "postgresql",
            "--dialect-version", "17",
            "--verbose"
        });

        assertThat(options.repository()).isEqualTo(Path.of("repository"));
        assertThat(options.target()).isEqualTo("src/main/resources/db/migration");
        assertThat(options.output()).isEqualTo(Path.of("out.json"));
        assertThat(options.dialect()).isEqualTo(ScanTarget.Dialect.POSTGRESQL);
        assertThat(options.dialectVersion()).isEqualTo("17");
        assertThat(options.verbose()).isTrue();
    }

    @Test
    void takesEmptyDialectsAndVersionsAsUnknown() {
        // As a server passes them when it does not know them
        final var options = Main.parseArguments(new String[] {
            "--path", "repository", "--output", "out.json", "--dialect", "", "--dialect-version", ""
        });

        assertThat(options.target()).isEqualTo(".");
        assertThat(options.dialect()).isNull();
        assertThat(options.dialectVersion()).isNull();
        assertThat(Main.parseArguments(new String[] {
            "--path", "repository", "--output", "out.json", "--dialect-version", "unknown"
        }).dialectVersion()).isNull();
    }

    @Test
    void rejectsWhatItDoesNotKnow() {
        assertThatThrownBy(() -> Main.parseArguments(new String[] {"--path", "r", "--output", "o", "--dialect", "db2"}))
            .hasMessageContaining("Unknown dialect: db2");
        assertThatThrownBy(() -> Main.parseArguments(new String[] {"--path", "r", "--output", "o", "--java-version", "21"}))
            .hasMessage("Unknown argument: --java-version");
        assertThatThrownBy(() -> Main.parseArguments(new String[] {"--output", "o"}))
            .hasMessage("Missing required argument: --path");
        assertThatThrownBy(() -> Main.parseArguments(new String[] {"--path", "r"}))
            .hasMessage("Missing required argument: --output");
    }

    @Test
    void writesTheScanResult() throws IOException {
        final var migrations = Files.createDirectories(directory.resolve("db/migration"));
        Files.writeString(migrations.resolve("V1__create.sql"), "CREATE TABLE a (id INT PRIMARY KEY);\n");
        final var output = directory.resolve("scan-result.json");

        Main.main(new String[] {"--path", directory.toString(), "--target", "db/migration", "--output", output.toString(), "--dialect-version", "17"});

        final var json = new ObjectMapper().readTree(output.toFile());

        assertThat(json.at("/target/language").asText()).isEqualTo("SQL");
        assertThat(json.at("/target/languageVersion").asText()).isEqualTo("17");
        assertThat(json.at("/modules/0/files/0/statements/0/table").asText()).isEqualTo("a");
    }
}
