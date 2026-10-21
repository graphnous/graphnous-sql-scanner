package dev.graphnous.scanner.sql;

import dev.graphnous.scanner.sql.model.File;
import dev.graphnous.scanner.sql.model.Module;
import dev.graphnous.scanner.sql.model.ScanResultSchema;
import dev.graphnous.scanner.sql.model.ScanTarget;
import dev.graphnous.scanner.sql.model.Statement;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Scans the SQL files of a target, such as a folder of Flyway migrations:
 * each file with its statements, as one module.
 */
public class SqlScanner {

    /**
     * Folders of build output, dependencies and version control, whose SQL
     * files are copies or not the project's.
     */
    private static final Set<String> SKIPPED_DIRECTORIES = Set.of(
        "target", "build", "out", "dist", "node_modules", ".git", ".gradle", ".idea", ".mvn"
    );

    private final ScanLog log;
    private final StatementReader reader;

    public SqlScanner(final ScanLog log) {
        this.log = log;
        this.reader = new StatementReader(log);
    }

    /**
     * Scans the target, relative to the repository. A target without a
     * dialect gets the one its SQL writes for, when that tells.
     */
    public ScanResultSchema scan(final Path repository, final ScanTarget target) {
        final var base = repository.resolve(target.getPath()).normalize();

        if (!Files.isDirectory(base)) {
            throw new IllegalArgumentException("The target is not a directory: " + base);
        }

        final var texts = new ArrayList<String>();
        final var module = new Module();
        module.setName(base.getFileName() == null ? "sql" : base.getFileName().toString());
        module.setPath(".");

        for (final var path : sqlFiles(base)) {
            final var file = scanFile(repository.normalize(), base, path, texts);

            if (file != null) {
                module.getFiles().add(file);
            }
        }

        if (target.getDialect() == null) {
            target.setDialect(Dialects.guess(texts));
        }

        final var result = new ScanResultSchema();
        result.setFormat("graphnous-scan-result");
        result.setVersion("2");
        result.setTarget(target);
        result.getModules().add(module);

        return result;
    }

    /**
     * The file with its statements; null when it cannot be read, so one
     * file does not fail the scan.
     */
    private File scanFile(final Path repository, final Path base, final Path path, final List<String> texts) {
        final var file = new File();
        file.setPath(base.relativize(path).toString().replace('\\', '/'));
        file.setLanguage("SQL");
        // From where the file is in the repository, as a target such as
        // src/main/resources/db/migration names the source set itself
        file.setSourceSet(sourceSet(repository.relativize(path).toString().replace('\\', '/')));
        file.setMigration(Migrations.of(path.getFileName().toString()));

        final byte[] content;

        try {
            content = Files.readAllBytes(path);
        } catch (final IOException e) {
            log.warn("Skipping " + file.getPath() + ": could not read it (" + e.getMessage() + ")");
            return null;
        }

        file.setSize(content.length);
        file.setChecksum(sha256(content));
        file.setLineCount(lineCount(content));

        final var text = new String(content, StandardCharsets.UTF_8);
        texts.add(text);

        var unread = 0;

        for (final var piece : SqlSplitter.split(text)) {
            for (final var statement : reader.read(piece)) {
                file.getStatements().add(statement);

                if (!statement.getParsed()) {
                    unread++;
                    log.warn("  " + file.getPath() + ":" + statement.getStartLine() + ": could not read the statement, kept as OTHER");
                }
            }
        }

        log.detail("  " + file.getPath() + ": " + statements(file.getStatements().size())
            + (unread == 0 ? "" : ", " + unread + " not read"));

        return file;
    }

    /**
     * The SQL files under the base, sorted by their path, outside the
     * folders of build output and dependencies.
     */
    static List<Path> sqlFiles(final Path base) {
        final var files = new ArrayList<Path>();

        try {
            Files.walkFileTree(base, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(final Path directory, final BasicFileAttributes attributes) {
                    return !directory.equals(base) && SKIPPED_DIRECTORIES.contains(directory.getFileName().toString())
                        ? FileVisitResult.SKIP_SUBTREE
                        : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attributes) {
                    if (attributes.isRegularFile() && file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".sql")) {
                        files.add(file);
                    }

                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (final IOException e) {
            throw new UncheckedIOException("Could not list the files of " + base, e);
        }

        files.sort(null);

        return files;
    }

    /**
     * Test code under {@code src/test} or another test source set such as
     * {@code src/integrationTest}, production code under {@code src/main};
     * null elsewhere.
     */
    static File.SourceSet sourceSet(final String path) {
        final var segments = ("/" + path).split("/");

        for (var i = 0; i + 1 < segments.length; i++) {
            if (segments[i].equals("src")) {
                final var sourceSet = segments[i + 1].toLowerCase(Locale.ROOT);

                if (sourceSet.equals("main")) {
                    return File.SourceSet.MAIN;
                }

                if (sourceSet.contains("test")) {
                    return File.SourceSet.TEST;
                }
            }
        }

        return null;
    }

    static String statements(final int count) {
        return count + (count == 1 ? " statement" : " statements");
    }

    static String files(final int count) {
        return count + (count == 1 ? " file" : " files");
    }

    /**
     * The number of lines, ended by \n, \r\n or \r. A last line without a
     * line terminator counts as well; an empty file has no lines.
     */
    static int lineCount(final byte[] content) {
        var lines = 0;

        for (var i = 0; i < content.length; i++) {
            if (content[i] == '\n' || content[i] == '\r' && (i + 1 == content.length || content[i + 1] != '\n')) {
                lines++;
            }
        }

        final var last = content.length == 0 ? '\n' : content[content.length - 1];

        return last == '\n' || last == '\r' ? lines : lines + 1;
    }

    /**
     * The SHA-256 of the content as lowercase hex, to tell whether a file
     * changed between scans.
     */
    private static String sha256(final byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    static long unread(final ScanResultSchema result) {
        return result.getModules().stream()
            .flatMap(module -> module.getFiles().stream())
            .flatMap(file -> file.getStatements().stream())
            .filter(statement -> !statement.getParsed())
            .count();
    }

    static long count(final ScanResultSchema result, final Statement.Kind kind) {
        return result.getModules().stream()
            .flatMap(module -> module.getFiles().stream())
            .flatMap(file -> file.getStatements().stream())
            .filter(statement -> kind == null || statement.getKind() == kind)
            .count();
    }
}
