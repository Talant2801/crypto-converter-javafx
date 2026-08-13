package io.github.talant2801.cryptoconverter.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The SQLite file, its schema, and the one connection everything shares.
 *
 * <p>One connection rather than a pool: this is an embedded, single-user
 * database, and SQLite serialises writes to the file whatever a pool does on
 * top. What a pool would add here is complexity and a class of "database is
 * locked" failures, not throughput.
 *
 * <p>That one connection is guarded by {@link #withConnection}, which is also
 * the only place in the application that catches {@link SQLException}. DAOs get
 * to be a list of statements, and callers get a single unchecked
 * {@link PersistenceException} rather than a checked exception threaded through
 * every layer.
 *
 * <p>The schema is created on startup with {@code IF NOT EXISTS}, so starting
 * against an existing file and against an empty directory are the same code
 * path and neither needs a migration tool.
 */
public final class Database implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Database.class);

    /**
     * The schema, in the order it has to be applied.
     *
     * <p>Amounts are TEXT, not REAL: SQLite's REAL is an IEEE double, and
     * storing money in one would undo the {@link java.math.BigDecimal}
     * discipline the rest of the application keeps. Timestamps are epoch
     * milliseconds, which sort correctly as integers and carry no time zone to
     * be misread later.
     */
    private static final List<String> SCHEMA = List.of(
            """
            CREATE TABLE IF NOT EXISTS conversion_history (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                from_currency TEXT    NOT NULL,
                to_currency   TEXT    NOT NULL,
                from_amount   TEXT    NOT NULL,
                to_amount     TEXT    NOT NULL,
                rate          TEXT    NOT NULL,
                converted_at  INTEGER NOT NULL
            )
            """,
            // The history pane always reads the newest rows first, so the sort
            // it does on every open is worth one index.
            """
            CREATE INDEX IF NOT EXISTS idx_conversion_history_converted_at
                ON conversion_history (converted_at DESC)
            """,
            // The unique constraint is what makes saving a favourite twice a
            // no-op at the storage level rather than a check-then-insert race.
            """
            CREATE TABLE IF NOT EXISTS favourites (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                from_currency TEXT    NOT NULL,
                to_currency   TEXT    NOT NULL,
                UNIQUE (from_currency, to_currency)
            )
            """);

    private final Connection connection;

    private Database(Connection connection) {
        this.connection = connection;
    }

    /**
     * Opens (or creates) the database file and applies the schema.
     *
     * @param path absolute path to the SQLite file; parent directories are created
     */
    public static Database open(Path path) {
        Objects.requireNonNull(path, "path");
        createParentDirectory(path);
        Database database = connect("jdbc:sqlite:" + path);
        log.info("Opened history database at {}", path);
        return database;
    }

    /**
     * An empty database that lives only as long as its connection.
     *
     * <p>Used by the DAO tests: the schema and the SQL are the real ones, so the
     * tests exercise SQLite itself rather than a substitute, and still leave no
     * file behind.
     */
    public static Database inMemory() {
        return connect("jdbc:sqlite::memory:");
    }

    private static Database connect(String url) {
        try {
            Connection connection = DriverManager.getConnection(url);
            Database database = new Database(connection);
            database.applySchema();
            return database;
        } catch (SQLException e) {
            throw new PersistenceException("Could not open the history database", e);
        }
    }

    /**
     * Runs {@code work} against the shared connection.
     *
     * <p>Synchronised because a JDBC connection is not safe for concurrent use
     * and history writes are issued from the I/O pool: two conversions
     * completing at once would otherwise interleave on one connection.
     *
     * @param description named in the exception when the statement fails
     * @throws PersistenceException wrapping any {@link SQLException}
     */
    <T> T withConnection(String description, SqlWork<T> work) {
        synchronized (connection) {
            try {
                return work.apply(connection);
            } catch (SQLException e) {
                throw new PersistenceException("Could not " + description, e);
            }
        }
    }

    private void applySchema() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String ddl : SCHEMA) {
                statement.execute(ddl);
            }
        }
    }

    private static void createParentDirectory(Path path) {
        Path parent = path.toAbsolutePath().getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.createDirectories(parent);
        } catch (IOException e) {
            throw new IllegalStateException("Could not create the directory for " + path, e);
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            // Nothing useful is left to do at shutdown, but staying silent about
            // it would hide a file that failed to flush.
            log.warn("Failed to close the history database cleanly", e);
        }
    }

    /**
     * A unit of work against a connection, allowed to throw the checked
     * exception JDBC insists on.
     *
     * @param <T> what the work produces
     */
    @FunctionalInterface
    interface SqlWork<T> {
        T apply(Connection connection) throws SQLException;
    }
}
