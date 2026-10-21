package dev.graphnous.scanner.sql;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SqlSplitterTest {

    @Test
    void splitsAtSemicolonsWithTheLinesOfEach() {
        final var pieces = SqlSplitter.split("""
            -- The scans
            CREATE TABLE scans (
                id UUID PRIMARY KEY
            );

            CREATE INDEX ix ON scans (id);
            """);

        assertThat(pieces).containsExactly(
            new SqlSplitter.Piece("CREATE TABLE scans (\n    id UUID PRIMARY KEY\n)", 2, 4),
            new SqlSplitter.Piece("CREATE INDEX ix ON scans (id)", 6, 6)
        );
    }

    @Test
    void leavesOutComments() {
        final var pieces = SqlSplitter.split("""
            /* The scans;
               all of them */
            ALTER TABLE scans -- a comment; with a semicolon
                ADD COLUMN status TEXT;
            """);

        assertThat(pieces).hasSize(1);
        assertThat(pieces.getFirst().text()).isEqualTo("ALTER TABLE scans  \n    ADD COLUMN status TEXT");
        assertThat(pieces.getFirst().startLine()).isEqualTo(3);
        assertThat(pieces.getFirst().endLine()).isEqualTo(4);
    }

    @Test
    void keepsSemicolonsInStringsAndQuotedNames() {
        final var pieces = SqlSplitter.split("""
            INSERT INTO notes (text) VALUES ('a; b', 'it''s; fine', E'a\\'; b');
            ALTER TABLE "odd;name" ADD COLUMN `x;y` TEXT;
            """);

        assertThat(pieces).extracting(SqlSplitter.Piece::text).containsExactly(
            "INSERT INTO notes (text) VALUES ('a; b', 'it''s; fine', E'a\\'; b')",
            "ALTER TABLE \"odd;name\" ADD COLUMN `x;y` TEXT"
        );
    }

    @Test
    void keepsTheBodiesOfFunctionsWhole() {
        final var pieces = SqlSplitter.split("""
            CREATE FUNCTION touch() RETURNS trigger AS $$
            BEGIN
                NEW.updated_at := now();
                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql;

            CREATE FUNCTION other() RETURNS int AS $body$ SELECT 1; $body$ LANGUAGE sql;
            SELECT $1;
            """);

        assertThat(pieces).hasSize(3);
        assertThat(pieces.get(0).startLine()).isEqualTo(1);
        assertThat(pieces.get(0).endLine()).isEqualTo(6);
        assertThat(pieces.get(1).text()).contains("$body$ SELECT 1; $body$");
        assertThat(pieces.get(2).text()).isEqualTo("SELECT $1");
    }

    @Test
    void keepsALastStatementWithoutASemicolon() {
        final var pieces = SqlSplitter.split("DROP TABLE a;\n\nDROP TABLE b\n\n-- done\n");

        assertThat(pieces).containsExactly(
            new SqlSplitter.Piece("DROP TABLE a", 1, 1),
            new SqlSplitter.Piece("DROP TABLE b", 3, 3)
        );
    }

    @Test
    void findsNoStatementsInAFileOfComments() {
        assertThat(SqlSplitter.split("-- nothing yet\n/* or here */\n;\n")).isEmpty();
    }
}
