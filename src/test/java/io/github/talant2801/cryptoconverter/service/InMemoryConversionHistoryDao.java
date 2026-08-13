package io.github.talant2801.cryptoconverter.service;

import io.github.talant2801.cryptoconverter.domain.ConversionRecord;
import io.github.talant2801.cryptoconverter.persistence.ConversionHistoryDao;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A history DAO backed by a list.
 *
 * <p>The SQL implementation is tested against a real SQLite database of its own;
 * these tests are about the service layer, so they use the cheapest thing that
 * honours the interface — including the "newest first" ordering, which the
 * service relies on.
 */
final class InMemoryConversionHistoryDao implements ConversionHistoryDao {

    private final List<ConversionRecord> rows = new ArrayList<>();
    private final AtomicLong nextId = new AtomicLong(1);
    private RuntimeException failure;

    /** Makes every write fail, for testing that a lost row does not lose the conversion. */
    void failWith(RuntimeException error) {
        this.failure = error;
    }

    @Override
    public synchronized ConversionRecord insert(ConversionRecord record) {
        if (failure != null) {
            throw failure;
        }
        ConversionRecord saved = record.withId(nextId.getAndIncrement());
        rows.add(saved);
        return saved;
    }

    @Override
    public synchronized List<ConversionRecord> recent(int limit) {
        return rows.stream()
                .sorted(Comparator.comparing(ConversionRecord::convertedAt)
                        .thenComparing(ConversionRecord::id)
                        .reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized boolean delete(long id) {
        return rows.removeIf(row -> row.id() != null && row.id() == id);
    }

    @Override
    public synchronized int clear() {
        int removed = rows.size();
        rows.clear();
        return removed;
    }

    synchronized int size() {
        return rows.size();
    }
}
