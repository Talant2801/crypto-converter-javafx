package io.github.talant2801.cryptoconverter.client;

import io.github.talant2801.cryptoconverter.domain.Coin;
import io.github.talant2801.cryptoconverter.domain.ExchangeRate;
import io.github.talant2801.cryptoconverter.domain.PricePoint;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Read access to CoinGecko, expressed in domain types.
 *
 * <p>An interface rather than a single concrete class because two later pieces
 * depend on the seam: {@code CachedRateService} decorates it, and the tests
 * substitute it wholesale. Callers therefore never learn whether a value came
 * from the network or a cache.
 *
 * <p>Every method is asynchronous. The JavaFX application thread calls these
 * directly, so a blocking signature would make freezing the UI the path of
 * least resistance. Failures arrive as a {@link CompletableFuture} completed
 * exceptionally with an {@link ApiException}.
 */
public interface CoinGeckoClient {

    /**
     * The top {@code limit} coins by market capitalisation, priced in
     * {@code vsCurrency}, which populates the coin selectors.
     *
     * @param limit how many coins to request, 1-250 as the endpoint allows
     * @param vsCurrency lower-case fiat code the market data is denominated in
     */
    CompletableFuture<List<Coin>> topCoins(int limit, String vsCurrency);

    /**
     * Spot prices for each requested coin in each requested fiat, including the
     * 24h change.
     *
     * <p>Batched deliberately: the endpoint accepts comma-separated ids, and one
     * request for ten coins costs a tenth of the rate-limit budget that ten
     * requests would.
     *
     * @param coinIds CoinGecko coin ids, for example {@code bitcoin}
     * @param fiatCodes lower-case fiat codes, for example {@code usd}
     * @return one rate per (coin, fiat) pair the response actually contained
     */
    CompletableFuture<List<ExchangeRate>> spotPrices(Collection<String> coinIds, Collection<String> fiatCodes);

    /**
     * The price series for one coin over the trailing {@code days}, oldest first.
     *
     * @param days 7, 30 or 90 — the ranges the chart offers
     */
    CompletableFuture<List<PricePoint>> priceHistory(String coinId, String fiatCode, int days);
}
