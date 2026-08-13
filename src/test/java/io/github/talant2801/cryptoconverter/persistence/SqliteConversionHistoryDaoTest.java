package io.github.talant2801.cryptoconverter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.talant2801.cryptoconverter.domain.ConversionRecord;
import io.github.talant2801.cryptoconverter.domain.CurrencyPair;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Exercises the DAO against a real SQLite database held in memory.
 *
 * <p>A fake would prove the DAO calls itself correctly and nothing else. The
 * things worth checking here — that the schema applies, that a {@link BigDecimal}
 * survives the round trip, that an id comes back from an insert — only exist
 * once actual SQLite is involved. The database dies with the connection, so the
 * suite still leaves nothing behind and needs no network.
 */
class SqliteConversionHistoryDaoTest {

    private static final Instant NOON = Instant.parse("2024-05-01T12:00:00Z");

    private Database database;
    private ConversionHistoryDao dao;

    @BeforeEach
    void openDatabase() {
        database = Database.inMemory();
        dao = new SqliteConversionHistoryDao(database);
    }

    @AfterEach
    void closeDatabase() {
        database.close();
    }

    private static ConversionRecord record(String from, String to, String amount, Instant at) {
        return new ConversionRecord(
                null, from, to, new BigDecimal(amount), new BigDecimal("21000.25"),
                new BigDecimal("42000.50"), at);
    }

    @Nested
    @DisplayName("insert and read")
    class InsertAndRead {

        @Test
        void returnsTheSavedRowWithTheIdTheDatabaseAssigned() {
            ConversionRecord saved = dao.insert(record("bitcoin", "USD", "0.5", NOON));

            assertThat(saved.id()).isNotNull().isPositive();
            assertThat(saved.fromCurrency()).isEqualTo("bitcoin");
        }

        @Test
        void assignsADistinctIdToEachRow() {
            ConversionRecord first = dao.insert(record("bitcoin", "USD", "0.5", NOON));
            ConversionRecord second = dao.insert(record("bitcoin", "USD", "0.5", NOON));

            assertThat(second.id()).isNotEqualTo(first.id());
        }

        @Test
        void readsEveryFieldBackUnchanged() {
            ConversionRecord saved = dao.insert(record("ethereum", "PLN", "2.75", NOON));

            ConversionRecord read = dao.recent(10).get(0);

            assertThat(read).isEqualTo(saved);
        }

        @Test
        void keepsAmountsExactRatherThanRoundingThemThroughAFloatingPointColumn() {
            // Stored as TEXT precisely so this value comes back with every digit
            // it went in with; a REAL column would quietly lose the tail.
            BigDecimal awkward = new BigDecimal("0.00000001");
            dao.insert(new ConversionRecord(
                    null, "bitcoin", "USD", awkward, new BigDecimal("123456789.987654321"),
                    new BigDecimal("42000.123456789"), NOON));

            ConversionRecord read = dao.recent(1).get(0);

            assertThat(read.fromAmount()).isEqualByComparingTo(awkward);
            assertThat(read.toAmount()).isEqualByComparingTo("123456789.987654321");
            assertThat(read.rate()).isEqualByComparingTo("42000.123456789");
        }

        @Test
        void preservesTheInstantToTheMillisecond() {
            Instant precise = Instant.parse("2024-05-01T12:00:00.123Z");

            dao.insert(record("bitcoin", "USD", "1", precise));

            assertThat(dao.recent(1).get(0).convertedAt()).isEqualTo(precise);
        }
    }

    @Nested
    @DisplayName("recent")
    class Recent {

        @Test
        void returnsNewestFirst() {
            dao.insert(record("bitcoin", "USD", "1", NOON.minusSeconds(60)));
            dao.insert(record("ethereum", "USD", "2", NOON));
            dao.insert(record("solana", "USD", "3", NOON.minusSeconds(30)));

            assertThat(dao.recent(10))
                    .extracting(ConversionRecord::fromCurrency)
                    .containsExactly("ethereum", "solana", "bitcoin");
        }

        @Test
        void breaksATieOnTheIdSoTheOrderIsStable() {
            ConversionRecord first = dao.insert(record("bitcoin", "USD", "1", NOON));
            ConversionRecord second = dao.insert(record("ethereum", "USD", "2", NOON));

            assertThat(dao.recent(10))
                    .extracting(ConversionRecord::id)
                    .containsExactly(second.id(), first.id());
        }

        @Test
        void stopsAtTheLimit() {
            for (int i = 0; i < 5; i++) {
                dao.insert(record("bitcoin", "USD", "1", NOON.plusSeconds(i)));
            }

            assertThat(dao.recent(3)).hasSize(3);
        }

        @Test
        void returnsAnEmptyListWhenNothingHasBeenSaved() {
            assertThat(dao.recent(100)).isEmpty();
        }

        @Test
        void rejectsANonPositiveLimit() {
            assertThatThrownBy(() -> dao.recent(0)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        void removesOnlyTheRowAskedFor() {
            ConversionRecord doomed = dao.insert(record("bitcoin", "USD", "1", NOON));
            dao.insert(record("ethereum", "USD", "2", NOON));

            assertThat(dao.delete(doomed.id())).isTrue();
            assertThat(dao.recent(10))
                    .extracting(ConversionRecord::fromCurrency)
                    .containsExactly("ethereum");
        }

        @Test
        void reportsThatAnUnknownRowWasAlreadyGone() {
            assertThat(dao.delete(4321L)).isFalse();
        }

        @Test
        void clearRemovesEverythingAndCountsWhatItRemoved() {
            dao.insert(record("bitcoin", "USD", "1", NOON));
            dao.insert(record("ethereum", "USD", "2", NOON));

            assertThat(dao.clear()).isEqualTo(2);
            assertThat(dao.recent(10)).isEmpty();
            assertThat(dao.clear()).isZero();
        }
    }

    @Nested
    @DisplayName("schema")
    class Schema {

        @Test
        void appliesCleanlyToADatabaseThatAlreadyHasIt() {
            // Every startup runs the same DDL; the second run must be a no-op
            // rather than an error, which is what IF NOT EXISTS buys.
            try (Database reopened = Database.inMemory()) {
                ConversionHistoryDao other = new SqliteConversionHistoryDao(reopened);
                assertThat(other.recent(1)).isEmpty();
            }
        }

        @Test
        void keepsHistoryAndFavouritesIndependent() {
            List<ConversionRecord> before = dao.recent(10);
            new SqliteFavouritesDao(database).add(CurrencyPair.of("bitcoin", "USD"));

            assertThat(dao.recent(10)).isEqualTo(before);
        }
    }
}
