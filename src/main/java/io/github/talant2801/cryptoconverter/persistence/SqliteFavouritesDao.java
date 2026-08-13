package io.github.talant2801.cryptoconverter.persistence;

import io.github.talant2801.cryptoconverter.domain.CurrencyPair;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@link FavouritesDao} over SQLite.
 *
 * <p>Adding leans on the table's unique constraint through {@code INSERT OR
 * IGNORE}: saving a favourite twice is one statement that changes nothing,
 * rather than a select followed by an insert with a race between them.
 */
public final class SqliteFavouritesDao implements FavouritesDao {

    private static final String INSERT =
            "INSERT OR IGNORE INTO favourites (from_currency, to_currency) VALUES (?, ?)";

    private static final String DELETE =
            "DELETE FROM favourites WHERE from_currency = ? AND to_currency = ?";

    private static final String SELECT_ONE =
            "SELECT 1 FROM favourites WHERE from_currency = ? AND to_currency = ?";

    private static final String SELECT_ALL =
            "SELECT from_currency, to_currency FROM favourites ORDER BY id";

    private final Database database;

    public SqliteFavouritesDao(Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public boolean add(CurrencyPair pair) {
        Objects.requireNonNull(pair, "pair");
        return database.withConnection("save a favourite", connection -> {
            try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
                bind(statement, pair);
                // Zero rows means the unique constraint caught a duplicate.
                return statement.executeUpdate() > 0;
            }
        });
    }

    @Override
    public boolean remove(CurrencyPair pair) {
        Objects.requireNonNull(pair, "pair");
        return database.withConnection("remove a favourite", connection -> {
            try (PreparedStatement statement = connection.prepareStatement(DELETE)) {
                bind(statement, pair);
                return statement.executeUpdate() > 0;
            }
        });
    }

    @Override
    public boolean contains(CurrencyPair pair) {
        Objects.requireNonNull(pair, "pair");
        return database.withConnection("look up a favourite", connection -> {
            try (PreparedStatement statement = connection.prepareStatement(SELECT_ONE)) {
                bind(statement, pair);
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next();
                }
            }
        });
    }

    @Override
    public List<CurrencyPair> all() {
        return database.withConnection("read the favourites", connection -> {
            try (PreparedStatement statement = connection.prepareStatement(SELECT_ALL);
                    ResultSet rows = statement.executeQuery()) {
                List<CurrencyPair> pairs = new ArrayList<>();
                while (rows.next()) {
                    pairs.add(new CurrencyPair(rows.getString("from_currency"), rows.getString("to_currency")));
                }
                return List.copyOf(pairs);
            }
        });
    }

    private static void bind(PreparedStatement statement, CurrencyPair pair) throws SQLException {
        statement.setString(1, pair.from());
        statement.setString(2, pair.to());
    }
}
