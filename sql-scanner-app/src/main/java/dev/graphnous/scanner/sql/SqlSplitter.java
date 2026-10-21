package dev.graphnous.scanner.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Splits the text of a SQL file into its statements, at the semicolons
 * outside comments, strings, quoted names and the dollar-quoted bodies of
 * PostgreSQL functions, keeping the lines each is on. Comments are left
 * out of the statements, as they say nothing a parser needs.
 */
final class SqlSplitter {

    /**
     * A dollar quote, as {@code $$} or {@code $body$}; not a parameter such
     * as {@code $1}.
     */
    private static final Pattern DOLLAR_QUOTE = Pattern.compile("\\$([A-Za-z_][A-Za-z0-9_]*)?\\$");

    private SqlSplitter() {
    }

    /**
     * A statement as written, without its semicolon and comments.
     *
     * @param startLine the line of its first word, starting at 1
     * @param endLine   the line of its semicolon, or of its last character
     *                  when the file ends without one
     */
    record Piece(String text, int startLine, int endLine) {
    }

    static List<Piece> split(final String sql) {
        final var pieces = new ArrayList<Piece>();
        final var current = new StringBuilder();

        var line = 1;
        var startLine = 0;
        var lastLine = 0;
        var i = 0;

        while (i < sql.length()) {
            final var c = sql.charAt(i);
            final var next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';

            if (c == '-' && next == '-') {
                // To the end of the line, whose line break counts below
                final var end = sql.indexOf('\n', i);
                i = end < 0 ? sql.length() : end;
                current.append(' ');
                continue;
            }

            if (c == '/' && next == '*') {
                final var end = sql.indexOf("*/", i + 2);
                final var after = end < 0 ? sql.length() : end + 2;
                line += lineBreaks(sql, i, after);
                current.append(' ');
                i = after;
                continue;
            }

            final var quoted = quotedEnd(sql, i);

            if (quoted > i) {
                if (startLine == 0) {
                    startLine = line;
                }

                current.append(sql, i, quoted);
                line += lineBreaks(sql, i, quoted);
                lastLine = line;
                i = quoted;
                continue;
            }

            if (c == ';') {
                if (startLine != 0) {
                    pieces.add(new Piece(current.toString().strip(), startLine, line));
                }

                current.setLength(0);
                startLine = 0;
                i++;
                continue;
            }

            if (c == '\n') {
                line++;
            } else if (!Character.isWhitespace(c)) {
                if (startLine == 0) {
                    startLine = line;
                }

                lastLine = line;
            }

            current.append(c);
            i++;
        }

        if (startLine != 0) {
            pieces.add(new Piece(current.toString().strip(), startLine, lastLine));
        }

        return pieces;
    }

    /**
     * Where the string, quoted name or dollar-quoted body starting at
     * {@code start} ends, after its closing quote; {@code start} when none
     * starts there. One that is never closed runs to the end of the text.
     */
    private static int quotedEnd(final String sql, final int start) {
        final var c = sql.charAt(start);

        if (c == '\'' || c == '"' || c == '`') {
            // E'...' strings of PostgreSQL take backslash escapes
            final var backslashes = c == '\'' && start > 0
                && (sql.charAt(start - 1) == 'E' || sql.charAt(start - 1) == 'e')
                && (start < 2 || !Character.isLetterOrDigit(sql.charAt(start - 2)));

            var i = start + 1;

            while (i < sql.length()) {
                final var at = sql.charAt(i);

                if (backslashes && at == '\\') {
                    i += 2;
                } else if (at == c) {
                    // A doubled quote is one quote in the string
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == c) {
                        i += 2;
                    } else {
                        return i + 1;
                    }
                } else {
                    i++;
                }
            }

            return sql.length();
        }

        if (c == '$') {
            final var matcher = DOLLAR_QUOTE.matcher(sql).region(start, sql.length());

            if (matcher.lookingAt()) {
                final var tag = matcher.group();
                final var end = sql.indexOf(tag, matcher.end());

                return end < 0 ? sql.length() : end + tag.length();
            }
        }

        return start;
    }

    private static int lineBreaks(final String sql, final int from, final int to) {
        var count = 0;

        for (var i = from; i < to; i++) {
            if (sql.charAt(i) == '\n') {
                count++;
            }
        }

        return count;
    }
}
