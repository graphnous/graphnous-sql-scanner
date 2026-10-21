package dev.graphnous.scanner.sql;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.graphnous.scanner.sql.model.ScanTarget;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

public class Main {

    /**
     * The language version of a target whose database version is not
     * given, as the scan result requires one.
     */
    static final String UNKNOWN_VERSION = "unknown";

    static void main(final String[] args) {
        final var options = parseArguments(args);
        final var log = ScanLog.console(options.verbose());

        final var target = new ScanTarget();
        target.setPath(options.target());
        target.setLanguage(ScanTarget.Language.SQL);
        target.setLanguageVersion(options.dialectVersion() == null ? UNKNOWN_VERSION : options.dialectVersion());
        target.setDialect(options.dialect());

        log.info("GraphNous SQL Scanner: " + target.getPath() + " in " + options.repository()
            + (options.dialect() == null ? "" : " (" + options.dialect() + ")"));
        log.detail("Output: " + options.output());

        final var result = new SqlScanner(log).scan(options.repository(), target);
        final var files = result.getModules().getFirst().getFiles().size();
        final var unread = SqlScanner.unread(result);

        log.info("Scanned " + SqlScanner.files(files) + ", " + SqlScanner.statements((int) SqlScanner.count(result, null))
            + (unread == 0 ? "" : ", " + unread + " not read")
            + (options.dialect() == null && result.getTarget().getDialect() != null ? ", written for " + result.getTarget().getDialect() : ""));

        new ScanResultWriter(new ObjectMapper()).write(result, options.output());
    }

    static Options parseArguments(final String[] args) {
        Path repository = null;
        String target = null;
        Path output = null;
        ScanTarget.Dialect dialect = null;
        String dialectVersion = null;
        var verbose = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--path" -> repository = Path.of(requireValue(args, ++i, "--path"));
                case "--target" -> target = requireValue(args, ++i, "--target");
                case "--output" -> output = Path.of(requireValue(args, ++i, "--output"));
                case "--dialect" -> dialect = dialect(requireValue(args, ++i, "--dialect"));
                case "--dialect-version" -> dialectVersion = requireValue(args, ++i, "--dialect-version");
                case "--verbose" -> verbose = true;
                default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
            }
        }

        if (repository == null) {
            throw new IllegalArgumentException("Missing required argument: --path");
        }

        if (target == null) {
            target = ".";
        }

        // An empty version is an unknown one, so a caller can always pass it
        if (dialectVersion != null && (dialectVersion.isBlank() || dialectVersion.equalsIgnoreCase(UNKNOWN_VERSION))) {
            dialectVersion = null;
        }

        if (output == null) {
            throw new IllegalArgumentException("Missing required argument: --output");
        }

        return new Options(repository, target, output, dialect, dialectVersion, verbose);
    }

    /**
     * The dialect of its name, in any case; null for an empty one, so a
     * caller can always pass it.
     */
    private static ScanTarget.Dialect dialect(final String name) {
        if (name.isBlank()) {
            return null;
        }

        try {
            return ScanTarget.Dialect.fromValue(name.strip().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown dialect: " + name + ", expected one of "
                + Arrays.stream(ScanTarget.Dialect.values()).map(ScanTarget.Dialect::value).collect(Collectors.joining(", ")));
        }
    }

    private static String requireValue(final String[] args, final int index, final String argument) {
        if (index >= args.length) {
            throw new IllegalArgumentException("Missing value for " + argument);
        }

        return args[index];
    }

    record Options(
        Path repository,
        String target,
        Path output,
        ScanTarget.Dialect dialect,
        String dialectVersion,
        boolean verbose
    ) {}
}
