package dev.graphnous.scanner.sql;

import dev.graphnous.scanner.sql.model.ScanTarget;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DialectsTest {

    @Test
    void tellsTheDatabaseFromWhatOnlyItWrites() {
        assertThat(Dialects.guess(List.of("CREATE TABLE a (id BIGSERIAL PRIMARY KEY, data JSONB)")))
            .isEqualTo(ScanTarget.Dialect.POSTGRESQL);
        assertThat(Dialects.guess(List.of("CREATE TABLE `a` (id INT UNSIGNED AUTO_INCREMENT) ENGINE=InnoDB")))
            .isEqualTo(ScanTarget.Dialect.MYSQL);
        assertThat(Dialects.guess(List.of("CREATE TABLE [dbo].[a] (id UNIQUEIDENTIFIER, name NVARCHAR(MAX))\nGO\n")))
            .isEqualTo(ScanTarget.Dialect.SQLSERVER);
        assertThat(Dialects.guess(List.of("CREATE TABLE a (id NUMBER(19), name VARCHAR2(255))")))
            .isEqualTo(ScanTarget.Dialect.ORACLE);
    }

    @Test
    void guessesNothingFromPlainSql() {
        assertThat(Dialects.guess(List.of("CREATE TABLE a (id INT PRIMARY KEY, name VARCHAR(255))"))).isNull();
        assertThat(Dialects.guess(List.of())).isNull();
    }

    @Test
    void guessesNothingWhenTwoTellApartAsOften() {
        assertThat(Dialects.guess(List.of("CREATE TABLE a (id BIGSERIAL)", "CREATE TABLE b (id INT AUTO_INCREMENT)"))).isNull();
    }
}
