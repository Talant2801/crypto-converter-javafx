package io.github.talant2801.cryptoconverter.persistence;

import io.github.talant2801.cryptoconverter.domain.CurrencyPair;
import java.util.List;

/**
 * Storage for the pairs a user marked as favourites.
 *
 * <p>Keyed by the pair itself rather than by a row id: a favourite is fully
 * described by its two currencies, so callers never have to hold onto a
 * database identifier to remove one.
 */
public interface FavouritesDao {

    /**
     * Saves a pair, doing nothing if it is already saved.
     *
     * @return true when the pair was newly added
     */
    boolean add(CurrencyPair pair);

    /**
     * Removes a pair.
     *
     * @return true when a row was actually removed
     */
    boolean remove(CurrencyPair pair);

    boolean contains(CurrencyPair pair);

    /** Every saved pair, oldest first, which is the order they were added in. */
    List<CurrencyPair> all();
}
