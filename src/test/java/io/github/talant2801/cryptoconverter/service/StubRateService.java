package io.github.talant2801.cryptoconverter.service;

import io.github.talant2801.cryptoconverter.client.ApiException;
import io.github.talant2801.cryptoconverter.domain.Coin;
import io.github.talant2801.cryptoconverter.domain.ExchangeRate;
import io.github.talant2801.cryptoconverter.domain.PricePoint;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A {@link RateService} holding a fixed price table.
 *
 * <p>Written by hand rather than mocked so a test can state the market in one
 * line and still assert how many lookups a conversion cost — the routing rules
 * are about which rates get asked for, not only about the arithmetic.
 */
final class StubRateService implements RateService {

    private final Map<String, ExchangeRate> table = new HashMap<>();
    private final AtomicInteger lookups = new AtomicInteger();
    private RuntimeException failure;

    StubRateService quoting(String coinId, String fiatCode, String price, Instant fetchedAt) {
        return quoting(coinId, fiatCode, price, null, fetchedAt);
    }

    StubRateService quoting(String coinId, String fiatCode, String price, String change24h, Instant fetchedAt) {
        table.put(
                key(coinId, fiatCode),
                new ExchangeRate(
                        coinId,
                        fiatCode.toUpperCase(Locale.ROOT),
                        new BigDecimal(price),
                        change24h == null ? null : new BigDecimal(change24h),
                        fetchedAt));
        return this;
    }

    /** Makes every lookup fail, for testing how conversion reports a dead API. */
    StubRateService failingWith(RuntimeException error) {
        this.failure = error;
        return this;
    }

    @Override
    public CompletableFuture<ExchangeRate> spotRate(String coinId, String fiatCode) {
        lookups.incrementAndGet();
        if (failure != null) {
            return CompletableFuture.failedFuture(failure);
        }
        ExchangeRate rate = table.get(key(coinId, fiatCode));
        return rate == null
                ? CompletableFuture.failedFuture(
                        new ApiException.NotFound("No stub rate for " + coinId + "/" + fiatCode))
                : CompletableFuture.completedFuture(rate);
    }

    @Override
    public CompletableFuture<List<Coin>> topCoins() {
        return CompletableFuture.completedFuture(List.of());
    }

    @Override
    public CompletableFuture<List<PricePoint>> priceHistory(String coinId, String fiatCode, int days) {
        return CompletableFuture.completedFuture(List.of());
    }

    int lookups() {
        return lookups.get();
    }

    private static String key(String coinId, String fiatCode) {
        return coinId.toLowerCase(Locale.ROOT) + "/" + fiatCode.toLowerCase(Locale.ROOT);
    }
}
