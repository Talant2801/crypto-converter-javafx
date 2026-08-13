package io.github.talant2801.cryptoconverter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.talant2801.cryptoconverter.domain.CurrencyPair;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Favourites against a real SQLite database, including the unique constraint. */
class SqliteFavouritesDaoTest {

    private static final CurrencyPair BTC_USD = CurrencyPair.of("bitcoin", "USD");
    private static final CurrencyPair ETH_EUR = CurrencyPair.of("ethereum", "EUR");

    private Database database;
    private FavouritesDao dao;

    @BeforeEach
    void openDatabase() {
        database = Database.inMemory();
        dao = new SqliteFavouritesDao(database);
    }

    @AfterEach
    void closeDatabase() {
        database.close();
    }

    @Test
    void savesAndFindsAPair() {
        assertThat(dao.add(BTC_USD)).isTrue();

        assertThat(dao.contains(BTC_USD)).isTrue();
        assertThat(dao.all()).containsExactly(BTC_USD);
    }

    @Test
    void savingTheSamePairTwiceChangesNothing() {
        dao.add(BTC_USD);

        assertThat(dao.add(BTC_USD)).isFalse();
        assertThat(dao.all()).containsExactly(BTC_USD);
    }

    @Test
    void treatsTheReversedPairAsADifferentFavourite() {
        dao.add(BTC_USD);
        dao.add(BTC_USD.swapped());

        assertThat(dao.all()).containsExactly(BTC_USD, BTC_USD.swapped());
    }

    @Test
    void returnsPairsInTheOrderTheyWereAdded() {
        dao.add(ETH_EUR);
        dao.add(BTC_USD);

        assertThat(dao.all()).containsExactly(ETH_EUR, BTC_USD);
    }

    @Test
    void removesOnlyThePairAskedFor() {
        dao.add(BTC_USD);
        dao.add(ETH_EUR);

        assertThat(dao.remove(BTC_USD)).isTrue();
        assertThat(dao.contains(BTC_USD)).isFalse();
        assertThat(dao.all()).containsExactly(ETH_EUR);
    }

    @Test
    void reportsThatAnUnsavedPairWasNotRemoved() {
        assertThat(dao.remove(BTC_USD)).isFalse();
    }

    @Test
    void findsNothingInAnEmptyDatabase() {
        assertThat(dao.all()).isEmpty();
        assertThat(dao.contains(BTC_USD)).isFalse();
    }

    @Test
    void keepsFavouritesAcrossAReopenOfTheSameFile(@TempDir Path directory) {
        // The in-memory database cannot show durability, and durability is the
        // entire point of this table: what was saved has to survive a restart.
        Path file = directory.resolve("nested").resolve("history.db");
        try (Database first = Database.open(file)) {
            new SqliteFavouritesDao(first).add(BTC_USD);
        }

        try (Database reopened = Database.open(file)) {
            assertThat(new SqliteFavouritesDao(reopened).all()).containsExactly(BTC_USD);
        }
    }
}
