package io.github.talant2801.cryptoconverter.persistence;

import io.github.talant2801.cryptoconverter.domain.ConversionRecord;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@link ConversionHistoryDao} over SQLite.
 *
 * <p>Every statement is a {@link PreparedStatement} with bound parameters.
 * Nothing on this page concatenates a value into SQL — not because a currency
 * code is likely to be hostile, but because "only the risky ones are
 * parameterised" is a rule nobody applies consistently for long.
 *
 * <p>Amounts round-trip through {@code TEXT} and {@link BigDecimal#toPlainString()},
 * so a value read back is bit-for-bit what was written. Timestamps are stored as
 * epoch milliseconds.
 */
public final class SqliteConversionHistoryDao implements ConversionHistoryDao {

    private static final String INSERT =
            """
            INSERT INTO conversion_history
                (from_currency, to_currency, from_amount, to_amount, rate, converted_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT_RECENT =
            """
            SELECT id, from_currency, to_currency, from_amount, to_amount, rate, converted_at
            FROM conversion_history
            ORDER BY converted_at DESC, id DESC
            LIMIT ?
            """;

    private static final String DELETE_ONE = "DELETE FROM conversion_history WHERE id = ?";

    private static final String DELETE_ALL = "DELETE FROM conversion_history";

    private final Database database;

    public SqliteConversionHistoryDao(Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public ConversionRecord insert(ConversionRecord record) {
        Objects.requireNonNull(record, "record");
        return database.withConnection("save a conversion", connection -> {
            try (PreparedStatement statement =
                    connection.prepareStatement(INSERT, Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, record.fromCurrency());
                statement.setString(2, record.toCurrency());
                statement.setString(3, record.fromAmount().toPlainString());
                statement.setString(4, record.toAmount().toPlainString());
                statement.setString(5, record.rate().toPlainString());
                statement.setLong(6, record.convertedAt().toEpochMilli());
                statement.executeUpdate();
                return record.withId(generatedId(statement));
            }
        });
    }

    @Override
    public List<ConversionRecord> recent(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive: " + limit);
        }
        return database.withConnection("read the conversion history", connection -> {
            try (PreparedStatement statement = connection.prepareStatement(SELECT_RECENT)) {
                statement.setInt(1, limit);
                try (ResultSet rows = statement.executeQuery()) {
                    List<ConversionRecord> records = new ArrayList<>();
                    while (rows.next()) {
                        records.add(toRecord(rows));
                    }
                    return List.copyOf(records);
                }
            }
        });
    }

    @Override
    public boolean delete(long id) {
        return database.withConnection("delete a conversion", connection -> {
            try (PreparedStatement statement = connection.prepareStatement(DELETE_ONE)) {
                statement.setLong(1, id);
                return statement.executeUpdate() > 0;
            }
        });
    }

    @Override
    public int clear() {
        return database.withConnection("clear the conversion history", connection -> {
            try (PreparedStatement statement = connection.prepareStatement(DELETE_ALL)) {
                return statement.executeUpdate();
            }
        });
    }

    private static ConversionRecord toRecord(ResultSet rows) throws SQLException {
        return new ConversionRecord(
                rows.getLong("id"),
                rows.getString("from_currency"),
                rows.getString("to_currency"),
                new BigDecimal(rows.getString("from_amount")),
                new BigDecimal(rows.getString("to_amount")),
                new BigDecimal(rows.getString("rate")),
                Instant.ofEpochMilli(rows.getLong("converted_at")));
    }

    private static long generatedId(PreparedStatement statement) throws SQLException {
        try (ResultSet keys = statement.getGeneratedKeys()) {
            if (!keys.next()) {
                throw new SQLException("The insert reported no generated id");
            }
            return keys.getLong(1);
        }
    }
}
