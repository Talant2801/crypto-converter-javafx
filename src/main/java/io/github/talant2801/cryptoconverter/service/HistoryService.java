package io.github.talant2801.cryptoconverter.service;

import io.github.talant2801.cryptoconverter.domain.ConversionRecord;
import io.github.talant2801.cryptoconverter.domain.ConversionResult;
import io.github.talant2801.cryptoconverter.domain.CurrencyPair;
import io.github.talant2801.cryptoconverter.persistence.ConversionHistoryDao;
import io.github.talant2801.cryptoconverter.persistence.FavouritesDao;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * History and favourites, moved off the caller's thread.
 *
 * <p>The DAOs are blocking, because JDBC is. This class is the seam that keeps
 * that fact away from the UI: every method hands the work to the I/O executor
 * and returns a {@link CompletableFuture}, so the JavaFX application thread
 * never waits on a disk write.
 *
 * <p>It is also where the history limit lives. The pane shows the last hundred
 * conversions, and putting that number in a query rather than in a UI class is
 * what stops a long-running install from loading ten thousand rows to display a
 * screenful.
 */
public final class HistoryService {

    /** How many past conversions the history pane shows. */
    public static final int HISTORY_LIMIT = 100;

    private final ConversionHistoryDao history;
    private final FavouritesDao favourites;
    private final Executor executor;
    private final Clock clock;

    public HistoryService(
            ConversionHistoryDao history, FavouritesDao favourites, Executor executor, Clock clock) {
        this.history = Objects.requireNonNull(history, "history");
        this.favourites = Objects.requireNonNull(favourites, "favourites");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Saves a completed conversion, stamped with the current time.
     *
     * <p>The clock is injected rather than read from the result's rate: the rate
     * carries when the <em>price</em> was fetched, which can be a minute older
     * than the conversion the user just performed, and the history column means
     * the latter.
     *
     * @return the saved record, carrying the id the database assigned
     */
    public CompletableFuture<ConversionRecord> record(ConversionResult result) {
        Objects.requireNonNull(result, "result");
        ConversionRecord unsaved = ConversionRecord.from(result, clock.instant());
        return CompletableFuture.supplyAsync(() -> history.insert(unsaved), executor);
    }

    /** The most recent conversions, newest first. */
    public CompletableFuture<List<ConversionRecord>> recent() {
        return CompletableFuture.supplyAsync(() -> history.recent(HISTORY_LIMIT), executor);
    }

    /** Deletes one history row, reporting whether it was still there. */
    public CompletableFuture<Boolean> delete(long id) {
        return CompletableFuture.supplyAsync(() -> history.delete(id), executor);
    }

    /** Deletes every history row, reporting how many went. */
    public CompletableFuture<Integer> clear() {
        return CompletableFuture.supplyAsync(history::clear, executor);
    }

    /** Saves a pair as a favourite; returns false when it was already saved. */
    public CompletableFuture<Boolean> addFavourite(CurrencyPair pair) {
        Objects.requireNonNull(pair, "pair");
        return CompletableFuture.supplyAsync(() -> favourites.add(pair), executor);
    }

    /** Removes a favourite; returns false when it was not saved to begin with. */
    public CompletableFuture<Boolean> removeFavourite(CurrencyPair pair) {
        Objects.requireNonNull(pair, "pair");
        return CompletableFuture.supplyAsync(() -> favourites.remove(pair), executor);
    }

    public CompletableFuture<Boolean> isFavourite(CurrencyPair pair) {
        Objects.requireNonNull(pair, "pair");
        return CompletableFuture.supplyAsync(() -> favourites.contains(pair), executor);
    }

    /** Every saved pair, in the order they were added. */
    public CompletableFuture<List<CurrencyPair>> favourites() {
        return CompletableFuture.supplyAsync(favourites::all, executor);
    }
}
