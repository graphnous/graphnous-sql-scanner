package dev.graphnous.scanner.sql;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.graphnous.scanner.sql.model.ScanResultSchema;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

public class ScanResultWriter {

    private final ObjectMapper objectMapper;

    public ScanResultWriter(final ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(
        final ScanResultSchema result,
        final Path output
    ) {
        try {
            objectMapper
                .writerWithDefaultPrettyPrinter()
                .writeValue(output.toFile(), result);
        } catch (IOException e) {
            throw new UncheckedIOException(
                "Failed to write scan result to " + output,
                e
            );
        }
    }
}
