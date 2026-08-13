package io.github.talant2801.cryptoconverter.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.talant2801.cryptoconverter.domain.ConversionRecord;
import io.github.talant2801.cryptoconverter.domain.ConversionResult;
import io.github.talant2801.cryptoconverter.domain.CurrencyPair;
import io.github.talant2801.cryptoconverter.domain.ExchangeRate;
import io.github.talant2801.cryptoconverter.domain.Money;
import io.github.talant2801.cryptoconverter.persistence.ConversionHistoryDao;
import io.github.talant2801.cryptoconverter.persistence.Database;
import io.github.talant2801.cryptoconverter.persistence.SqliteConversionHistoryDao;
import io.github.talant2801.cryptoconverter.persistence.SqliteFavouritesDao;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The service over a real in-memory database and a real executor.
 *
 * <p>Deliberately not a fake: the point of this class is that blocking JDBC work
 * happens somewhere other than the calling thread, and that only means something
 * with an executor and a database actually in play.
 */
class HistoryServiceTest {

    private static final Instant NOW = Instant.parse("2024-05-01T12:00:00Z");

    private final TickingClock clock = new TickingClock(NOW);

    private Database database;
    private ExecutorService executor;
    private HistoryService service;

    @BeforeEach
    void openDatabase() {
        database = Database.inMemory();
        executor = Executors.newSingleThreadExecutor();
        service = new HistoryService(
                new SqliteConversionHistoryDao(database), new SqliteFavouritesDao(database), executor, clock);
    }

    @AfterEach
    void closeDatabase() {
        executor.shutdownNow();
        database.close();
    }

    private static ConversionResult result(String coin, String fiat, String amount, String rate) {
        ExchangeRate exchangeRate =
                new ExchangeRate(coin, fiat, new BigDecimal(rate), new BigDecimal("-1.25"), NOW.minusSeconds(30));
        Money from = Money.of(amount, coin);
        Money to = new Money(from.amount().multiply(exchangeRate.rate()), fiat).rounded();
        return new ConversionResult(from.rounded(), to, exchangeRate);
    }

    @Test
    void savesAConversionAndReadsItBack() {
        ConversionRecord saved = valueOf(service.record(result("bitcoin", "USD", "0.5", "42000.50")));

        assertThat(saved.id()).isNotNull();
        assertThat(valueOf(service.recent())).containsExactly(saved);
    }

    @Test
    void stampsTheRecordWithWhenTheConversionHappenedNotWhenThePriceWasFetched() {
        ConversionRecord saved = valueOf(service.record(result("bitcoin", "USD", "1", "42000.50")));

        assertThat(saved.convertedAt()).isEqualTo(NOW);
        assertThat(saved.convertedAt()).isNotEqualTo(NOW.minusSeconds(30));
    }

    @Test
    void doesTheBlockingWorkSomewhereOtherThanTheCallingThread() {
        AtomicReference<Thread> worker = new AtomicReference<>();
        // The DAO is where the blocking happens, so the DAO is what reports
        // which thread it was blocking. Asserting on a continuation instead
        // would depend on whether the future had already completed.
        ConversionHistoryDao recordingDao = new ConversionHistoryDao() {

            @Override
            public ConversionRecord insert(ConversionRecord record) {
                worker.set(Thread.currentThread());
                return record.withId(1L);
            }

            @Override
            public List<ConversionRecord> recent(int limit) {
                return List.of();
            }

            @Override
            public boolean delete(long id) {
                return false;
            }

            @Override
            public int clear() {
                return 0;
            }
        };
        HistoryService offThread =
                new HistoryService(recordingDao, new SqliteFavouritesDao(database), executor, clock);

        valueOf(offThread.record(result("bitcoin", "USD", "1", "42000.50")));

        assertThat(worker.get()).isNotNull().isNotSameAs(Thread.currentThread());
    }

    @Test
    void readsBackNoMoreThanTheHistoryLimit() {
        for (int i = 0; i < HistoryService.HISTORY_LIMIT + 5; i++) {
            clock.advance(Duration.ofSeconds(1));
            valueOf(service.record(result("bitcoin", "USD", "1", "42000.50")));
        }

        List<ConversionRecord> recent = valueOf(service.recent());

        assertThat(recent).hasSize(HistoryService.HISTORY_LIMIT);
        // Newest first, so the five that fell off are the oldest ones.
        assertThat(recent.get(0).convertedAt()).isAfter(recent.get(recent.size() - 1).convertedAt());
    }

    @Test
    void deletesOneRowAndThenEveryRow() {
        ConversionRecord first = valueOf(service.record(result("bitcoin", "USD", "1", "42000.50")));
        valueOf(service.record(result("ethereum", "USD", "2", "2100")));

        assertThat(valueOf(service.delete(first.id()))).isTrue();
        assertThat(valueOf(service.delete(first.id()))).isFalse();
        assertThat(valueOf(service.clear())).isEqualTo(1);
        assertThat(valueOf(service.recent())).isEmpty();
    }

    @Test
    void addsChecksAndRemovesFavourites() {
        CurrencyPair pair = CurrencyPair.of("bitcoin", "USD");

        assertThat(valueOf(service.addFavourite(pair))).isTrue();
        assertThat(valueOf(service.addFavourite(pair))).isFalse();
        assertThat(valueOf(service.isFavourite(pair))).isTrue();
        assertThat(valueOf(service.favourites())).containsExactly(pair);

        assertThat(valueOf(service.removeFavourite(pair))).isTrue();
        assertThat(valueOf(service.isFavourite(pair))).isFalse();
        assertThat(valueOf(service.favourites())).isEmpty();
    }

    private static <T> T valueOf(CompletableFuture<T> future) {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for " + future, e);
        } catch (ExecutionException | TimeoutException e) {
            throw new AssertionError("Expected a value but the future did not deliver one", e);
        }
    }
}
