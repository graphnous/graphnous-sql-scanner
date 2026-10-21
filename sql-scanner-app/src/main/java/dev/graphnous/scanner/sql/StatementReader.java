package dev.graphnous.scanner.sql;

import dev.graphnous.scanner.sql.model.ColumnChange;
import dev.graphnous.scanner.sql.model.ColumnDefinition;
import dev.graphnous.scanner.sql.model.ForeignKey;
import dev.graphnous.scanner.sql.model.Statement;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.ReferentialAction;
import net.sf.jsqlparser.statement.UnsupportedStatement;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.alter.AlterExpression;
import net.sf.jsqlparser.statement.create.index.CreateIndex;
import net.sf.jsqlparser.statement.create.table.ColDataType;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.create.table.ForeignKeyIndex;
import net.sf.jsqlparser.statement.create.table.Index;
import net.sf.jsqlparser.statement.create.view.CreateView;
import net.sf.jsqlparser.statement.drop.Drop;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Reads what a statement does to the tables of the database, with
 * JSqlParser. Only statements that create, change or drop tables, views
 * and indexes are parsed; the others, such as INSERT, GRANT or CREATE
 * FUNCTION, are OTHER as they are. A statement JSqlParser cannot read is
 * kept as OTHER and not parsed.
 */
final class StatementReader {

    /**
     * The statements that change tables, views or indexes, by how they
     * start; any other is OTHER without parsing it.
     */
    private static final Pattern DEFINITION = Pattern.compile(
        "(?is)^(CREATE\\s+(OR\\s+REPLACE\\s+)?((GLOBAL|LOCAL)\\s+)?((TEMP|TEMPORARY|UNLOGGED|UNIQUE|MATERIALIZED)\\s+)*"
        + "(TABLE|VIEW|INDEX)\\b|ALTER\\s+TABLE\\b|DROP\\s+(MATERIALIZED\\s+)?(TABLE|VIEW|INDEX)\\b).*"
    );

    /**
     * How long JSqlParser may take for one statement, as it gives up on
     * none by itself.
     */
    private static final long TIMEOUT_MILLIS = 10_000;

    /**
     * The words that start what a CREATE TABLE lists other than a column.
     */
    private static final Set<String> TABLE_ELEMENTS = Set.of(
        "CONSTRAINT", "PRIMARY", "FOREIGN", "UNIQUE", "CHECK", "EXCLUDE", "LIKE", "INDEX", "KEY", "PERIOD"
    );

    /**
     * The words after ADD in an ALTER TABLE that do not start a column.
     */
    private static final Set<String> ADD_ELEMENTS = Set.of(
        "COLUMN", "CONSTRAINT", "PRIMARY", "FOREIGN", "UNIQUE", "CHECK", "EXCLUDE", "INDEX", "KEY", "PARTITION",
        "IF", "SPATIAL", "FULLTEXT"
    );

    /**
     * The words a DEFAULT expression in the specs of a column ends before.
     */
    private static final Set<String> AFTER_DEFAULT = Set.of(
        "NOT", "NULL", "PRIMARY", "UNIQUE", "REFERENCES", "CHECK", "CONSTRAINT", "COLLATE", "GENERATED",
        "AUTO_INCREMENT", "COMMENT"
    );

    /**
     * A name, with its schema when written so, each part quoted or not.
     */
    private static final String NAME = "(?:\"[^\"]+\"|`[^`]+`|\\[[^]]+]|[\\w$]+)(?:\\.(?:\"[^\"]+\"|`[^`]+`|\\[[^]]+]|[\\w$]+))*";

    private static final Pattern REFERENCES = Pattern.compile(
        "(?is)REFERENCES\\s+((?:\"[^\"]+\"|`[^`]+`|\\[[^]]+]|[\\w$]+)(?:\\.(?:\"[^\"]+\"|`[^`]+`|\\[[^]]+]|[\\w$]+))*)"
        + "\\s*(?:\\(([^)]*)\\))?(.*)"
    );

    private static final Pattern ON_DELETE = Pattern.compile(
        "(?is)ON\\s+DELETE\\s+(CASCADE|SET\\s+NULL|SET\\s+DEFAULT|RESTRICT|NO\\s+ACTION)"
    );

    /**
     * A column named after ADD, DROP, ALTER or RENAME COLUMN, or after the
     * TO of RENAME COLUMN.
     */
    private static final Pattern COLUMN_CLAUSE = Pattern.compile(
        "(?i)(\\b(?:ADD|DROP|ALTER|RENAME)\\s+COLUMN\\s+(?:IF\\s+(?:NOT\\s+)?EXISTS\\s+)?)([A-Za-z_][\\w$]*)"
        + "|(\\bRENAME\\s+COLUMN\\s+(?:\"[^\"]+\"|[\\w$]+)\\s+TO\\s+)([A-Za-z_][\\w$]*)"
        + "|(\\bADD\\s+)([A-Za-z_][\\w$]*)(?=\\s)"
    );

    /**
     * A name in the place of a table, with its schema when written so.
     */
    private static final Pattern TABLE_CLAUSE = Pattern.compile(
        "(?i)(\\b(?:TABLE|VIEW|REFERENCES|ON)\\s+(?:IF\\s+(?:NOT\\s+)?EXISTS\\s+)?(?:ONLY\\s+)?)([A-Za-z_][\\w$]*(?:\\.[A-Za-z_][\\w$]*)*)(?![\\w$])"
    );

    /**
     * The words after TABLE, VIEW, REFERENCES or ON that do not name a
     * table, as in ON DELETE CASCADE or ON COMMIT DROP.
     */
    private static final Set<String> NOT_TABLES = Set.of(
        "DELETE", "UPDATE", "COMMIT", "CONFLICT", "ONLY", "IF", "AS", "SELECT", "TO"
    );

    private static final Pattern CREATE_INDEX = Pattern.compile(
        "(?is)^CREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+(?:CONCURRENTLY\\s+)?(?:IF\\s+NOT\\s+EXISTS\\s+)?(?:[\\w$\"]+\\s+)?"
        + "ON\\s+(?:ONLY\\s+)?(" + NAME + ")\\s*(?:USING\\s+\\w+\\s*)?\\((.*)"
    );

    private static final Pattern DROP_INDEX = Pattern.compile(
        "(?is)^DROP\\s+INDEX\\s+(?:CONCURRENTLY\\s+)?(IF\\s+EXISTS\\s+)?(" + NAME + "(?:\\s*,\\s*" + NAME + ")*)\\s*(?:CASCADE|RESTRICT)?"
    );

    private final ScanLog log;

    StatementReader(final ScanLog log) {
        this.log = log;
    }

    /**
     * What the statement does: one entry, or one for each table of a DROP
     * TABLE of several, and of an ALTER TABLE that also renames its table.
     */
    List<Statement> read(final SqlSplitter.Piece piece) {
        if (!DEFINITION.matcher(piece.text()).matches()) {
            return List.of(statement(piece, Statement.Kind.OTHER, true));
        }

        final var parsed = parse(piece.text());

        if (parsed == null) {
            // Indexes are simple enough to read without JSqlParser, which
            // does not know some of them, such as DROP INDEX CONCURRENTLY
            final var index = index(piece);

            return index != null ? index : List.of(statement(piece, Statement.Kind.OTHER, false));
        }

        try {
            return switch (parsed) {
                case CreateTable createTable -> List.of(createTable(piece, createTable));
                case Alter alter -> alter(piece, alter);
                case Drop drop -> drop(piece, drop);
                case CreateView createView -> List.of(createView(piece, createView));
                case CreateIndex createIndex -> List.of(createIndex(piece, createIndex));
                default -> List.of(statement(piece, Statement.Kind.OTHER, true));
            };
        } catch (final RuntimeException e) {
            // What JSqlParser read but this does not expect, such as a part
            // left null, only costs that statement
            log.detail("  Could not read the statement on line " + piece.startLine() + ": " + e);
            return List.of(statement(piece, Statement.Kind.OTHER, false));
        }
    }

    /**
     * The statement as JSqlParser reads it, or with its table and column
     * names quoted when it does not, as JSqlParser takes some of them,
     * such as settings or excludes, for keywords; null when it reads
     * neither.
     */
    private static net.sf.jsqlparser.statement.Statement parse(final String sql) {
        final var parsed = tryParse(sql);

        // Some keywords JSqlParser reads past instead of failing, taking
        // the public of ALTER TABLE ONLY public.settings for the table
        if (parsed != null && readsTheTableWritten(parsed, sql)) {
            return parsed;
        }

        final var quoted = quoteTableNames(quoteColumnNames(sql));

        if (quoted.equals(sql)) {
            return null;
        }

        final var requoted = tryParse(quoted);

        return requoted != null && readsTheTableWritten(requoted, quoted) ? requoted : null;
    }

    /**
     * Whether the statement is of the table the text names first after
     * TABLE, VIEW or the ON of an index, when it names one unquoted.
     */
    private static boolean readsTheTableWritten(final net.sf.jsqlparser.statement.Statement parsed, final String sql) {
        final var matcher = TABLE_CLAUSE.matcher(sql);
        String written = null;

        while (written == null && matcher.find()) {
            if (!NOT_TABLES.contains(matcher.group(2).split("\\.")[0].toUpperCase(Locale.ROOT))) {
                written = matcher.group(2);
            }
        }

        final Table table = switch (parsed) {
            case CreateTable createTable -> createTable.getTable();
            case Alter alter -> alter.getTable();
            case CreateView createView -> createView.getView();
            case CreateIndex createIndex -> createIndex.getTable();
            case Drop drop -> drop.getName();
            default -> null;
        };

        return written == null || table == null || name(table).equalsIgnoreCase(written);
    }

    /**
     * The statement as JSqlParser reads it; null when it does not, also
     * when it returns an UnsupportedStatement for one it gives up on, as
     * it does for a table named after one of its keywords, e.g.
     * {@code CREATE TABLE settings (...)}.
     */
    private static net.sf.jsqlparser.statement.Statement tryParse(final String sql) {
        try {
            final var statement = CCJSqlParserUtil.parse(sql, parser -> parser.withTimeOut(TIMEOUT_MILLIS));

            return statement instanceof UnsupportedStatement ? null : statement;
        } catch (final Exception | StackOverflowError e) {
            return null;
        }
    }

    /**
     * The statement with each name in the place of a table quoted, part by
     * part, as in {@code CREATE TABLE "billing"."settings"}: after TABLE,
     * VIEW, REFERENCES and the ON of an index.
     */
    static String quoteTableNames(final String sql) {
        final var result = new StringBuilder();
        final var matcher = TABLE_CLAUSE.matcher(sql);

        while (matcher.find()) {
            final var name = matcher.group(2);

            if (NOT_TABLES.contains(name.split("\\.")[0].toUpperCase(Locale.ROOT))) {
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group()));
            } else {
                final var quoted = Arrays.stream(name.split("\\.")).map(part -> '"' + part + '"').collect(Collectors.joining("."));
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group(1) + quoted));
            }
        }

        matcher.appendTail(result);

        return result.toString();
    }

    /**
     * The statement with each name in the place of a column quoted, as in
     * {@code ADD COLUMN "excludes" TEXT}, also in the column list of a
     * CREATE TABLE.
     */
    static String quoteColumnNames(final String sql) {
        final var result = new StringBuilder();
        final Matcher matcher = COLUMN_CLAUSE.matcher(sql);

        while (matcher.find()) {
            final var group = matcher.group(2) != null ? 2 : matcher.group(4) != null ? 4 : 6;
            final var name = matcher.group(group);

            if (group == 6 && ADD_ELEMENTS.contains(name.toUpperCase(Locale.ROOT))) {
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group()));
            } else {
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group(group - 1) + '"' + name + '"'));
            }
        }

        matcher.appendTail(result);

        return quoteTableColumns(result.toString());
    }

    /**
     * The CREATE TABLE with the first word of each column in its list
     * quoted; any other statement as it is.
     */
    private static String quoteTableColumns(final String sql) {
        if (!sql.regionMatches(true, 0, "CREATE", 0, 6) || !Pattern.compile("(?is)^CREATE\\s.*?\\bTABLE\\b.*").matcher(sql).matches()) {
            return sql;
        }

        final var open = sql.indexOf('(');

        if (open < 0) {
            return sql;
        }

        final var result = new StringBuilder(sql.substring(0, open + 1));
        var depth = 1;
        var expectName = true;
        var i = open + 1;

        while (i < sql.length()) {
            final var c = sql.charAt(i);

            if (c == '\'' || c == '"' || c == '`') {
                final var end = sql.indexOf(c, i + 1);
                final var after = end < 0 ? sql.length() : end + 1;
                result.append(sql, i, after);
                expectName = false;
                i = after;
                continue;
            }

            if (depth == 1 && expectName && (Character.isLetter(c) || c == '_')) {
                var end = i;

                while (end < sql.length() && (Character.isLetterOrDigit(sql.charAt(end)) || sql.charAt(end) == '_' || sql.charAt(end) == '$')) {
                    end++;
                }

                final var word = sql.substring(i, end);
                result.append(TABLE_ELEMENTS.contains(word.toUpperCase(Locale.ROOT)) ? word : '"' + word + '"');
                expectName = false;
                i = end;
                continue;
            }

            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 1) {
                expectName = true;
            } else if (!Character.isWhitespace(c)) {
                expectName = false;
            }

            result.append(c);
            i++;
        }

        return result.toString();
    }

    /**
     * The CREATE INDEX or DROP INDEX JSqlParser does not read, as written:
     * the table and the plain columns of a CREATE INDEX, one DROP_INDEX for
     * each index dropped; null for any other statement.
     */
    static List<Statement> index(final SqlSplitter.Piece piece) {
        final var created = CREATE_INDEX.matcher(piece.text());

        if (created.matches()) {
            final var statement = statement(piece, Statement.Kind.CREATE_INDEX, true);
            statement.setTable(qualified(created.group(1)));

            for (final var column : topLevel(created.group(2))) {
                final var name = unquote(column.strip().split("\\s+")[0]);

                // Only the columns themselves, not expressions such as to_tsvector(...)
                if (name.matches("[\\w$]+") && column.strip().matches("(?is)[\\w$\"`\\[\\]]+(\\s+(ASC|DESC|NULLS\\s+(FIRST|LAST)|\\w+_ops))*")) {
                    final var definition = new ColumnDefinition();
                    definition.setName(name);
                    statement.getColumns().add(definition);
                }
            }

            return List.of(statement);
        }

        final var dropped = DROP_INDEX.matcher(piece.text());

        if (dropped.matches()) {
            return Arrays.stream(dropped.group(2).split(","))
                .map(name -> {
                    final var statement = statement(piece, Statement.Kind.DROP_INDEX, true);

                    if (dropped.group(1) != null) {
                        statement.setIfExists(true);
                    }

                    return statement;
                })
                .toList();
        }

        return null;
    }

    /**
     * The parts of a column list separated by commas outside parentheses
     * and quotes, up to the parenthesis that closes it.
     */
    private static List<String> topLevel(final String list) {
        final var parts = new ArrayList<String>();
        final var current = new StringBuilder();
        var depth = 0;
        var quote = '\0';

        for (var i = 0; i < list.length(); i++) {
            final var c = list.charAt(i);

            if (quote != '\0') {
                if (c == quote) {
                    quote = '\0';
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (depth == 0) {
                    break;
                }

                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(current.toString());
                current.setLength(0);
                continue;
            }

            current.append(c);
        }

        parts.add(current.toString());

        return parts;
    }

    private Statement createTable(final SqlSplitter.Piece piece, final CreateTable createTable) {
        final var statement = statement(piece, Statement.Kind.CREATE_TABLE, true);
        statement.setTable(name(createTable.getTable()));

        if (createTable.isIfNotExists()) {
            statement.setIfExists(true);
        }

        final var references = new LinkedHashSet<String>();

        if (createTable.getColumnDefinitions() != null) {
            for (final var column : createTable.getColumnDefinitions()) {
                final var definition = definition(column.getColumnName(), column.getColDataType(), column.getColumnSpecs(), true);
                statement.getColumns().add(definition);

                if (definition.getForeignKey() != null) {
                    references.add(definition.getForeignKey().getTable());
                }
            }
        }

        if (createTable.getIndexes() != null) {
            for (final var index : createTable.getIndexes()) {
                constrain(statement.getColumns(), index, references);
            }
        }

        if (createTable.getSelect() != null) {
            references.addAll(tables(createTable.getSelect()));
        }

        references.remove(statement.getTable());
        statement.getReferences().addAll(references);

        return statement;
    }

    /**
     * Applies a constraint of the table to its columns: its primary key,
     * a unique column, or a foreign key.
     */
    private static void constrain(final List<ColumnDefinition> columns, final Index index, final Set<String> references) {
        final var names = index.getColumnsNames() == null ? List.<String>of() : index.getColumnsNames().stream().map(StatementReader::unquote).toList();
        final var type = index.getType() == null ? "" : index.getType().toUpperCase(Locale.ROOT);

        if (index instanceof ForeignKeyIndex foreignKey) {
            for (var i = 0; i < names.size(); i++) {
                final var column = column(columns, names.get(i));

                if (column != null) {
                    column.setForeignKey(foreignKey(foreignKey, i));
                }
            }

            references.add(name(foreignKey.getTable()));
        } else if (type.equals("PRIMARY KEY")) {
            for (final var name : names) {
                final var column = column(columns, name);

                if (column != null) {
                    column.setPrimaryKey(true);
                    column.setNullable(false);
                }
            }
        } else if (type.equals("UNIQUE") && names.size() == 1) {
            final var column = column(columns, names.getFirst());

            if (column != null) {
                column.setUnique(true);
            }
        }
    }

    private List<Statement> alter(final SqlSplitter.Piece piece, final Alter alter) {
        final var table = name(alter.getTable());
        final var statement = statement(piece, Statement.Kind.ALTER_TABLE, true);
        statement.setTable(table);

        final var references = new LinkedHashSet<String>();
        Statement rename = null;

        for (final var expression : alter.getAlterExpressions() == null ? List.<AlterExpression>of() : alter.getAlterExpressions()) {
            switch (expression.getOperation()) {
                case ADD -> add(statement, expression, references);
                case DROP -> {
                    if (expression.getColumnName() != null) {
                        statement.getChanges().add(change(ColumnChange.Kind.DROP, expression.getColumnName()));
                    }
                }
                case ALTER, MODIFY -> alterColumns(statement, expression);
                case CHANGE -> {
                    // MySQL: CHANGE old new type, a rename and a new type at once
                    for (final var column : columns(expression)) {
                        final var oldName = unquote(expression.getColumnOldName() == null ? column.getColumnName() : expression.getColumnOldName());
                        final var newName = unquote(column.getColumnName());

                        if (!oldName.equals(newName)) {
                            final var renamed = change(ColumnChange.Kind.RENAME, oldName);
                            renamed.setNewName(newName);
                            statement.getChanges().add(renamed);
                        }

                        final var retyped = change(ColumnChange.Kind.ALTER_TYPE, newName);
                        retyped.setDefinition(definition(newName, column.getColDataType(), column.getColumnSpecs(), false));
                        statement.getChanges().add(retyped);
                    }
                }
                case RENAME -> {
                    final var renamed = change(ColumnChange.Kind.RENAME, expression.getColumnOldName());
                    renamed.setNewName(unquote(expression.getColumnName()));
                    statement.getChanges().add(renamed);
                }
                case RENAME_TABLE -> {
                    rename = statement(piece, Statement.Kind.RENAME_TABLE, true);
                    rename.setTable(table);
                    rename.setNewName(name(expression.getNewTableName()));
                }
                default -> {
                    // Changes of what the table keeps, such as its
                    // constraints by name, its comment or its partitions,
                    // not of its columns
                }
            }
        }

        references.remove(table);
        statement.getReferences().addAll(references);

        if (rename == null) {
            return List.of(statement);
        }

        return statement.getChanges().isEmpty() ? List.of(rename) : List.of(statement, rename);
    }

    private static void add(final Statement statement, final AlterExpression expression, final Set<String> references) {
        for (final var column : columns(expression)) {
            final var definition = definition(column.getColumnName(), column.getColDataType(), column.getColumnSpecs(), true);
            final var added = change(ColumnChange.Kind.ADD, definition.getName());
            added.setDefinition(definition);
            statement.getChanges().add(added);

            if (definition.getForeignKey() != null) {
                references.add(definition.getForeignKey().getTable());
            }
        }

        if (expression.getIndex() instanceof ForeignKeyIndex foreignKey && foreignKey.getColumnsNames() != null) {
            final var names = foreignKey.getColumnsNames();

            for (var i = 0; i < names.size(); i++) {
                final var definition = new ColumnDefinition();
                definition.setName(unquote(names.get(i)));
                definition.setForeignKey(foreignKey(foreignKey, i));

                final var change = change(ColumnChange.Kind.ADD_FOREIGN_KEY, definition.getName());
                change.setDefinition(definition);
                statement.getChanges().add(change);
            }

            references.add(name(foreignKey.getTable()));
        }
    }

    private static void alterColumns(final Statement statement, final AlterExpression expression) {
        for (final var column : columns(expression)) {
            final var name = unquote(column.getColumnName());
            final var retyped = change(ColumnChange.Kind.ALTER_TYPE, name);
            retyped.setDefinition(definition(name, column.getColDataType(), column.getColumnSpecs(), false));
            statement.getChanges().add(retyped);
        }

        if (expression.getColumnSetNotNullList() != null) {
            expression.getColumnSetNotNullList().forEach(column ->
                statement.getChanges().add(change(ColumnChange.Kind.SET_NOT_NULL, column.getColumnName())));
        }

        if (expression.getColumnDropNotNullList() != null) {
            expression.getColumnDropNotNullList().forEach(column ->
                statement.getChanges().add(change(ColumnChange.Kind.DROP_NOT_NULL, column.getColumnName())));
        }

        if (expression.getColumnSetDefaultList() != null) {
            for (final var column : expression.getColumnSetDefaultList()) {
                final var name = unquote(column.getColumnName());
                final var change = change(ColumnChange.Kind.SET_DEFAULT, name);
                final var definition = new ColumnDefinition();
                definition.setName(name);

                final var value = column.getDefaultExpression() != null ? column.getDefaultExpression().toString() : column.getDefaultValue();

                if (value != null && !Secrets.namesSecret(name) && !Secrets.holdsSecret(value)) {
                    definition.setDefault(value);
                }

                change.setDefinition(definition);
                statement.getChanges().add(change);
            }
        }

        if (expression.getColumnDropDefaultList() != null) {
            expression.getColumnDropDefaultList().forEach(column ->
                statement.getChanges().add(change(ColumnChange.Kind.DROP_DEFAULT, column.getColumnName())));
        }
    }

    private static List<AlterExpression.ColumnDataType> columns(final AlterExpression expression) {
        return expression.getColDataTypeList() == null ? List.of() : expression.getColDataTypeList();
    }

    private List<Statement> drop(final SqlSplitter.Piece piece, final Drop drop) {
        final var type = drop.getType() == null ? "" : drop.getType().toUpperCase(Locale.ROOT);
        final var kind = switch (type) {
            case "TABLE" -> Statement.Kind.DROP_TABLE;
            case "VIEW", "MATERIALIZED VIEW" -> Statement.Kind.DROP_VIEW;
            case "INDEX" -> Statement.Kind.DROP_INDEX;
            default -> drop.isMaterialized() ? Statement.Kind.DROP_VIEW : Statement.Kind.OTHER;
        };

        if (kind == Statement.Kind.OTHER) {
            return List.of(statement(piece, kind, true));
        }

        final var names = drop.getNames() != null && !drop.getNames().isEmpty() ? drop.getNames() : List.of(drop.getName());
        final var statements = new ArrayList<Statement>();

        for (final var name : names) {
            final var statement = statement(piece, kind, true);

            // A DROP INDEX names the index, not its table
            if (kind != Statement.Kind.DROP_INDEX) {
                statement.setTable(name(name));
            }

            if (drop.isIfExists()) {
                statement.setIfExists(true);
            }

            statements.add(statement);
        }

        return statements;
    }

    private Statement createView(final SqlSplitter.Piece piece, final CreateView createView) {
        final var statement = statement(piece, Statement.Kind.CREATE_VIEW, true);
        statement.setTable(name(createView.getView()));

        if (createView.getSelect() != null) {
            final var references = tables(createView.getSelect());
            references.remove(statement.getTable());
            statement.getReferences().addAll(references);
        }

        return statement;
    }

    private Statement createIndex(final SqlSplitter.Piece piece, final CreateIndex createIndex) {
        final var statement = statement(piece, Statement.Kind.CREATE_INDEX, true);
        statement.setTable(name(createIndex.getTable()));

        if (createIndex.getIndex() != null && createIndex.getIndex().getColumnsNames() != null) {
            for (final var column : createIndex.getIndex().getColumnsNames()) {
                // Only the columns themselves, not expressions such as lower(name)
                final var name = unquote(column.strip());

                if (name.matches("[\\w$]+")) {
                    final var definition = new ColumnDefinition();
                    definition.setName(name);
                    statement.getColumns().add(definition);
                }
            }
        }

        return statement;
    }

    /**
     * The tables a query reads, sorted, without its own CTEs.
     */
    private static TreeSet<String> tables(final net.sf.jsqlparser.statement.Statement query) {
        return new TablesNamesFinder<>().getTables(query).stream()
            .map(StatementReader::name)
            .collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * A column as its definition writes it.
     *
     * @param nullable whether a column the specs do not say of takes null,
     *                 as one of a CREATE TABLE or an ADD COLUMN does;
     *                 false leaves it unsaid, as for ALTER COLUMN ... TYPE
     */
    static ColumnDefinition definition(
        final String name,
        final ColDataType type,
        final List<String> specs,
        final boolean nullable
    ) {
        final var definition = new ColumnDefinition();
        definition.setName(unquote(name));

        if (type != null) {
            definition.setType(type(type));
        }

        final var tokens = specs == null ? List.<String>of() : specs;

        for (var i = 0; i < tokens.size(); i++) {
            final var token = tokens.get(i).toUpperCase(Locale.ROOT);

            if (token.equals("NOT") && i + 1 < tokens.size() && tokens.get(i + 1).equalsIgnoreCase("NULL")) {
                definition.setNullable(false);
                i++;
            } else if (token.equals("NULL")) {
                definition.setNullable(true);
            } else if (token.equals("PRIMARY") && i + 1 < tokens.size() && tokens.get(i + 1).equalsIgnoreCase("KEY")) {
                definition.setPrimaryKey(true);
                definition.setNullable(false);
                i++;
            } else if (token.equals("UNIQUE")) {
                definition.setUnique(true);
            } else if (token.equals("DEFAULT") && i + 1 < tokens.size()) {
                final var value = new ArrayList<String>();
                value.add(tokens.get(++i));

                while (i + 1 < tokens.size() && !AFTER_DEFAULT.contains(firstWord(tokens.get(i + 1)))) {
                    value.add(tokens.get(++i));
                }

                final var written = String.join(" ", value);

                if (!Secrets.namesSecret(definition.getName()) && !Secrets.holdsSecret(written)) {
                    definition.setDefault(written);
                }
            } else if (token.startsWith("REFERENCES")) {
                definition.setForeignKey(foreignKey(String.join(" ", tokens.subList(i, tokens.size()))));
                break;
            } else if (token.equals("CONSTRAINT")) {
                // Its name
                i++;
            }
        }

        if (nullable && definition.getNullable() == null) {
            definition.setNullable(true);
        }

        return definition;
    }

    /**
     * The foreign key of a REFERENCES clause, as JSqlParser keeps it among
     * the specs of a column, e.g. {@code REFERENCES scans(id) ON DELETE
     * SET NULL}.
     */
    private static ForeignKey foreignKey(final String clause) {
        final var matcher = REFERENCES.matcher(clause);

        if (!matcher.matches()) {
            return null;
        }

        final var foreignKey = new ForeignKey();
        foreignKey.setTable(qualified(matcher.group(1)));

        if (matcher.group(2) != null && !matcher.group(2).isBlank()) {
            foreignKey.setColumn(unquote(matcher.group(2).split(",")[0].strip()));
        }

        final var onDelete = ON_DELETE.matcher(matcher.group(3));

        if (onDelete.find()) {
            foreignKey.setOnDelete(onDelete(onDelete.group(1).replaceAll("\\s+", "_").toUpperCase(Locale.ROOT)));
        }

        return foreignKey;
    }

    /**
     * The foreign key of the column at {@code position} of a FOREIGN KEY
     * constraint.
     */
    private static ForeignKey foreignKey(final ForeignKeyIndex index, final int position) {
        final var foreignKey = new ForeignKey();
        foreignKey.setTable(name(index.getTable()));

        final var referenced = index.getReferencedColumnNames();

        if (referenced != null && position < referenced.size()) {
            foreignKey.setColumn(unquote(referenced.get(position)));
        }

        final ReferentialAction onDelete = index.getReferentialAction(ReferentialAction.Type.DELETE);

        if (onDelete != null && onDelete.getAction() != null) {
            foreignKey.setOnDelete(onDelete(onDelete.getAction().name()));
        }

        return foreignKey;
    }

    /**
     * The action as the schema names it; null for NO ACTION, which is what
     * happens without one.
     */
    private static ForeignKey.OnDelete onDelete(final String action) {
        return switch (action) {
            case "CASCADE" -> ForeignKey.OnDelete.CASCADE;
            case "SET_NULL" -> ForeignKey.OnDelete.SET_NULL;
            case "SET_DEFAULT" -> ForeignKey.OnDelete.SET_DEFAULT;
            case "RESTRICT" -> ForeignKey.OnDelete.RESTRICT;
            default -> null;
        };
    }

    private static ColumnChange change(final ColumnChange.Kind kind, final String column) {
        final var change = new ColumnChange();
        change.setKind(kind);
        change.setColumn(unquote(column));

        return change;
    }

    private static ColumnDefinition column(final List<ColumnDefinition> columns, final String name) {
        return columns.stream().filter(column -> column.getName().equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    private static Statement statement(final SqlSplitter.Piece piece, final Statement.Kind kind, final boolean parsed) {
        final var statement = new Statement();
        statement.setKind(kind);
        statement.setParsed(parsed);
        statement.setStartLine(piece.startLine());
        statement.setEndLine(piece.endLine());

        return statement;
    }

    /**
     * The type as written, in lower case and without the space JSqlParser
     * puts before its arguments, e.g. {@code varchar(255)}.
     */
    static String type(final ColDataType type) {
        return type.toString().strip().replaceAll("\\s+\\(", "(").replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String name(final Table table) {
        return qualified(table.getFullyQualifiedName());
    }

    private static String name(final String table) {
        return qualified(table);
    }

    /**
     * A name with each of its parts unquoted, e.g. {@code billing.invoices}
     * for {@code "billing"."invoices"}.
     */
    static String qualified(final String name) {
        return Arrays.stream(name.strip().split("\\.(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)"))
            .map(StatementReader::unquote)
            .collect(Collectors.joining("."));
    }

    /**
     * A name without the quotes around it: {@code "name"}, {@code `name`}
     * or {@code [name]}.
     */
    static String unquote(final String name) {
        final var stripped = name.strip();

        if (stripped.length() >= 2) {
            final var first = stripped.charAt(0);
            final var last = stripped.charAt(stripped.length() - 1);

            if (first == '"' && last == '"' || first == '`' && last == '`' || first == '[' && last == ']') {
                return stripped.substring(1, stripped.length() - 1).replace("\"\"", "\"");
            }
        }

        return stripped;
    }

    private static String firstWord(final String token) {
        final var space = token.indexOf(' ');

        return (space < 0 ? token : token.substring(0, space)).toUpperCase(Locale.ROOT);
    }
}
