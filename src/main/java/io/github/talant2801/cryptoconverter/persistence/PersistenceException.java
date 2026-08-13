package io.github.talant2801.cryptoconverter.persistence;

import java.sql.SQLException;

/**
 * A database operation that failed.
 *
 * <p>Unchecked, and thrown from the one place that catches {@link SQLException},
 * so the DAOs read as plain code and callers are not forced to handle a failure
 * they cannot do anything about. Losing a history row is not worth crashing
 * over: the UI reports it and the conversion itself still stands.
 */
public class PersistenceException extends RuntimeException {

    public PersistenceException(String message, SQLException cause) {
        super(message, cause);
    }
}
