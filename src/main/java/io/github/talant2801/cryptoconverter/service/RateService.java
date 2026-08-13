package io.github.talant2801.cryptoconverter.service;

import io.github.talant2801.cryptoconverter.domain.Coin;
import io.github.talant2801.cryptoconverter.domain.ExchangeRate;
import io.github.talant2801.cryptoconverter.domain.PricePoint;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Market data as the rest of the application wants it: one pair at a time,
 * asynchronously, with no notion of where the numbers came from.
 *
 * <p>This is deliberately narrower than
 * {@link io.github.talant2801.cryptoconverter.client.CoinGeckoClient}. The
 * client speaks in batches because that is what the endpoint rewards; callers
 * think in single pairs because that is what a converter does. Bridging the two
 * is {@link CachedRateService}'s job, and having the seam here is what lets the
 * cache be a decorator rather than a special case threaded through every caller.
 *
 * <p>Failures arrive as a future completed exceptionally with an
 * {@link io.github.talant2801.cryptoconverter.client.ApiException}.
 */
public interface RateService {

    /**
     * The price of one {@code coinId} in {@code fiatCode}.
     *
     * @param coinId CoinGecko coin id, for example {@code bitcoin}
     * @param fiatCode fiat code in any casing, for example {@code USD}
     */
    CompletableFuture<ExchangeRate> spotRate(String coinId, String fiatCode);

    /** The top coins by market capitalisation, for populating the selectors. */
    CompletableFuture<List<Coin>> topCoins();

    /**
     * The price series for one pair over the trailing {@code days}, oldest first.
     *
     * @param days 7, 30 or 90 — the ranges the chart offers
     */
    CompletableFuture<List<PricePoint>> priceHistory(String coinId, String fiatCode, int days);
}
