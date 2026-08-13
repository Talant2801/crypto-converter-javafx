package io.github.talant2801.cryptoconverter.persistence;

import io.github.talant2801.cryptoconverter.domain.ConversionRecord;
import java.util.List;

/**
 * Storage for past conversions.
 *
 * <p>An interface because the service layer above should not be able to tell
 * SQLite from anything else, and because it lets the history pane be reasoned
 * about without a database at all.
 *
 * <p>Every method is blocking: JDBC is. Callers run these on an I/O thread —
 * see {@link io.github.talant2801.cryptoconverter.service.HistoryService}, which
 * is the only class in the application that touches a DAO directly.
 */
public interface ConversionHistoryDao {

    /**
     * Saves a conversion.
     *
     * @param record a record whose {@code id} is null
     * @return the same record carrying the id the database assigned
     */
    ConversionRecord insert(ConversionRecord record);

    /**
     * The most recent conversions, newest first.
     *
     * @param limit how many rows to return, at least one
     */
    List<ConversionRecord> recent(int limit);

    /**
     * Deletes one row.
     *
     * @return true when a row was actually removed, false when the id was already gone
     */
    boolean delete(long id);

    /**
     * Deletes every row.
     *
     * @return how many rows were removed
     */
    int clear();
}
