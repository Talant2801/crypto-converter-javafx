package io.github.talant2801.cryptoconverter.service;

import io.github.talant2801.cryptoconverter.client.ApiException;
import io.github.talant2801.cryptoconverter.client.CoinGeckoClient;
import io.github.talant2801.cryptoconverter.config.AppConfig;
import io.github.talant2801.cryptoconverter.domain.Coin;
import io.github.talant2801.cryptoconverter.domain.ExchangeRate;
import io.github.talant2801.cryptoconverter.domain.PricePoint;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * A {@link RateService} that answers from a TTL cache and only reaches the
 * {@link CoinGeckoClient} when it has to.
 *
 * <p>A decorator rather than a cache built into the client, so the client stays
 * a faithful description of one HTTP call and this class stays a description of
 * one caching policy. Each concern is then testable on its own: the client
 * against a stubbed transport, this against a counting client.
 *
 * <p>Three caches rather than one because the data ages at genuinely different
 * rates. Spot prices move by the second, so they expire in about a minute; the
 * market-cap ordering of the top hundred coins barely moves within a session,
 * so it holds for half an hour; a daily price series gains one point a day, so
 * re-fetching it while the user flicks between 7, 30 and 90 days would spend
 * the rate-limit budget on identical answers.
 */
public final class CachedRateService implements RateService {

    /** How many coins the selectors are populated from. */
    private static final int TOP_COIN_COUNT = 100;

    /** The coin list is priced in USD; the selectors show names, not prices. */
    private static final String COIN_LIST_CURRENCY = "usd";

    /** The coin-list cache holds a single entry, so its key is a constant. */
    private static final String COIN_LIST_KEY = "top-" + TOP_COIN_COUNT;

    private final CoinGeckoClient client;
    private final TtlCache<ExchangeRate> rates;
    private final TtlCache<List<Coin>> coinList;
    private final TtlCache<List<PricePoint>> histories;

    public CachedRateService(CoinGeckoClient client, Duration rateTtl, Duration coinListTtl, Clock clock) {
        this.client = Objects.requireNonNull(client, "client");
        Objects.requireNonNull(clock, "clock");
        this.rates = new TtlCache<>("rates", rateTtl, clock);
        this.coinList = new TtlCache<>("coin-list", coinListTtl, clock);
        // The history series shares the coin list's TTL: both describe slow
        // movements, and both are re-requested by ordinary UI fiddling.
        this.histories = new TtlCache<>("history", coinListTtl, clock);
    }

    /** Builds the service from resolved configuration. */
    public static CachedRateService create(CoinGeckoClient client, AppConfig config, Clock clock) {
        Objects.requireNonNull(config, "config");
        return new CachedRateService(client, config.rateCacheTtl(), config.coinListCacheTtl(), clock);
    }

    @Override
    public CompletableFuture<ExchangeRate> spotRate(String coinId, String fiatCode) {
        String coin = normalise(coinId, "coinId");
        String fiat = normalise(fiatCode, "fiatCode");
        return rates.get(coin + "/" + fiat, () -> client.spotPrices(List.of(coin), List.of(fiat))
                .thenApply(fetched -> firstOf(fetched, coin, fiat)));
    }

    @Override
    public CompletableFuture<List<Coin>> topCoins() {
        return coinList.get(COIN_LIST_KEY, () -> client.topCoins(TOP_COIN_COUNT, COIN_LIST_CURRENCY));
    }

    @Override
    public CompletableFuture<List<PricePoint>> priceHistory(
            String coinId, String fiatCode, int days) {

        String coin = normalise(coinId, "coinId");
        String fiat = normalise(fiatCode, "fiatCode");
        if (days < 1) {
            throw new IllegalArgumentException("days must be positive: " + days);
        }
        return histories.get(coin + "/" + fiat + "/" + days, () -> client.priceHistory(coin, fiat, days));
    }

    /**
     * The rate for the pair that was asked for.
     *
     * <p>A single-pair request that comes back without that pair means the id
     * was not one CoinGecko knows, which is a {@link ApiException.NotFound} and
     * so is not retried — the mapper already drops quotes that are absent or
     * unusable.
     */
    private static ExchangeRate firstOf(List<ExchangeRate> fetched, String coinId, String fiatCode) {
        return fetched.stream()
                .filter(rate -> rate.base().equalsIgnoreCase(coinId) && rate.quote().equalsIgnoreCase(fiatCode))
                .findFirst()
                .orElseThrow(() -> new ApiException.NotFound(
                        "CoinGecko returned no price for " + coinId + " in " + fiatCode));
    }

    /**
     * Lower-cases and trims, so "BTC " from a selector and "btc" from a saved
     * favourite share one cache entry rather than costing two requests.
     */
    private static String normalise(String value, String name) {
        Objects.requireNonNull(value, name);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }
}
