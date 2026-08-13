/**
 * Everything that knows the application has a database.
 *
 * <p>{@link io.github.talant2801.cryptoconverter.persistence.Database} owns the
 * file, the schema and the single connection; the DAOs own the statements. The
 * layer above sees only the two DAO interfaces and domain records, so the SQL,
 * the column names and {@link java.sql.SQLException} all stop here.
 *
 * <p>The schema is applied with {@code IF NOT EXISTS} on every startup, which
 * makes a first run and a hundredth run the same code path.
 */
package io.github.talant2801.cryptoconverter.persistence;
