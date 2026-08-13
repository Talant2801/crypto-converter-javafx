package io.github.talant2801.cryptoconverter.service;

import io.github.talant2801.cryptoconverter.domain.CurrencyPair;
import io.github.talant2801.cryptoconverter.persistence.FavouritesDao;
import java.util.ArrayList;
import java.util.List;

/**
 * A favourites DAO backed by a list, preserving insertion order and rejecting
 * duplicates exactly as the unique constraint on the table does.
 */
final class InMemoryFavouritesDao implements FavouritesDao {

    private final List<CurrencyPair> pairs = new ArrayList<>();

    @Override
    public synchronized boolean add(CurrencyPair pair) {
        if (pairs.contains(pair)) {
            return false;
        }
        return pairs.add(pair);
    }

    @Override
    public synchronized boolean remove(CurrencyPair pair) {
        return pairs.remove(pair);
    }

    @Override
    public synchronized boolean contains(CurrencyPair pair) {
        return pairs.contains(pair);
    }

    @Override
    public synchronized List<CurrencyPair> all() {
        return List.copyOf(pairs);
    }
}
