package dev.graphnous.scanner.sql;

import dev.graphnous.scanner.sql.model.ScanTarget;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The database migrations are written for, as told by what only it
 * writes, such as {@code BIGSERIAL} for PostgreSQL or {@code AUTO_INCREMENT}
 * for MySQL: a guess, for when the dialect is not given.
 */
final class Dialects {

    /**
     * What each database writes that the others do not, as whole words in
     * any case.
     */
    private static final Map<ScanTarget.Dialect, List<Pattern>> MARKS = new LinkedHashMap<>();

    static {
        MARKS.put(ScanTarget.Dialect.POSTGRESQL, patterns(
            "\\b(BIG)?SERIAL\\b", "\\bTIMESTAMPTZ\\b", "\\bJSONB\\b", "::\\s*[a-z]", "\\$\\$", "\\bplpgsql\\b",
            "\\bgen_random_uuid\\s*\\(", "\\bNULLS\\s+NOT\\s+DISTINCT\\b", "\\bCREATE\\s+EXTENSION\\b"
        ));
        MARKS.put(ScanTarget.Dialect.MYSQL, patterns(
            "\\bAUTO_INCREMENT\\b", "\\bENGINE\\s*=", "`", "\\bUNSIGNED\\b", "\\bTINYINT\\s*\\(\\s*1\\s*\\)"
        ));
        MARKS.put(ScanTarget.Dialect.SQLSERVER, patterns(
            "\\bNVARCHAR\\s*\\(\\s*MAX\\s*\\)", "\\bIDENTITY\\s*\\(", "\\bUNIQUEIDENTIFIER\\b", "\\bDATETIME2\\b",
            "^\\s*GO\\s*$", "\\[dbo\\]"
        ));
        MARKS.put(ScanTarget.Dialect.ORACLE, patterns(
            "\\bVARCHAR2\\b", "\\bNUMBER\\s*\\(", "\\bCLOB\\b", "\\bSYSDATE\\b", "\\bNOCACHE\\b"
        ));
        MARKS.put(ScanTarget.Dialect.SQLITE, patterns(
            "\\bAUTOINCREMENT\\b", "\\bWITHOUT\\s+ROWID\\b", "\\bPRAGMA\\b"
        ));
    }

    private Dialects() {
    }

    /**
     * The database most of the texts write for; null when none writes
     * what tells one apart, or two tell apart as often.
     */
    static ScanTarget.Dialect guess(final Collection<String> texts) {
        ScanTarget.Dialect best = null;
        var bestCount = 0;
        var tie = false;

        for (final var entry : MARKS.entrySet()) {
            var count = 0;

            for (final var text : texts) {
                for (final var pattern : entry.getValue()) {
                    if (pattern.matcher(text).find()) {
                        count++;
                    }
                }
            }

            if (count > bestCount) {
                best = entry.getKey();
                bestCount = count;
                tie = false;
            } else if (count > 0 && count == bestCount) {
                tie = true;
            }
        }

        return tie ? null : best;
    }

    private static List<Pattern> patterns(final String... regexes) {
        return java.util.Arrays.stream(regexes)
            .map(regex -> Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.MULTILINE))
            .toList();
    }
}
