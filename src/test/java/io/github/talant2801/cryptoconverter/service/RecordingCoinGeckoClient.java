package io.github.talant2801.cryptoconverter.service;

import io.github.talant2801.cryptoconverter.client.CoinGeckoClient;
import io.github.talant2801.cryptoconverter.domain.Coin;
import io.github.talant2801.cryptoconverter.domain.ExchangeRate;
import io.github.talant2801.cryptoconverter.domain.PricePoint;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * A {@link CoinGeckoClient} that counts what it was asked and answers with
 * whatever the test supplies.
 *
 * <p>Counting is the point: the cache's contract is about how many calls reach
 * the network, and that cannot be asserted against a mock's default answers.
 * The suppliers default to futures that never complete on their own, which is
 * what lets a test hold a load open, fire a second identical request, and prove
 * the two were coalesced into one call.
 */
final class RecordingCoinGeckoClient implements CoinGeckoClient {

    private final AtomicInteger spotPriceCalls = new AtomicInteger();
    private final AtomicInteger topCoinCalls = new AtomicInteger();
    private final AtomicInteger priceHistoryCalls = new AtomicInteger();

    /** Every future handed out, so a test can complete a load on its own schedule. */
    private final List<CompletableFuture<List<ExchangeRate>>> spotPriceAnswers =
            Collections.synchronizedList(new ArrayList<>());

    private final List<Collection<String>> requestedCoinIds = Collections.synchronizedList(new ArrayList<>());

    private Supplier<CompletableFuture<List<ExchangeRate>>> spotPriceAnswer = CompletableFuture::new;
    private Supplier<CompletableFuture<List<Coin>>> topCoinAnswer = CompletableFuture::new;
    private Supplier<CompletableFuture<List<PricePoint>>> priceHistoryAnswer = CompletableFuture::new;

    @Override
    public CompletableFuture<List<Coin>> topCoins(int limit, String vsCurrency) {
        topCoinCalls.incrementAndGet();
        return topCoinAnswer.get();
    }

    @Override
    public CompletableFuture<List<ExchangeRate>> spotPrices(
            Collection<String> coinIds, Collection<String> fiatCodes) {

        spotPriceCalls.incrementAndGet();
        requestedCoinIds.add(List.copyOf(coinIds));
        CompletableFuture<List<ExchangeRate>> answer = spotPriceAnswer.get();
        spotPriceAnswers.add(answer);
        return answer;
    }

    @Override
    public CompletableFuture<List<PricePoint>> priceHistory(String coinId, String fiatCode, int days) {
        priceHistoryCalls.incrementAndGet();
        return priceHistoryAnswer.get();
    }

    RecordingCoinGeckoClient answeringSpotPrices(ExchangeRate... rates) {
        List<ExchangeRate> answer = List.of(rates);
        spotPriceAnswer = () -> CompletableFuture.completedFuture(answer);
        return this;
    }

    RecordingCoinGeckoClient failingSpotPrices(RuntimeException failure) {
        spotPriceAnswer = () -> CompletableFuture.failedFuture(failure);
        return this;
    }

    RecordingCoinGeckoClient answeringTopCoins(List<Coin> coins) {
        topCoinAnswer = () -> CompletableFuture.completedFuture(coins);
        return this;
    }

    RecordingCoinGeckoClient answeringPriceHistory(List<PricePoint> points) {
        priceHistoryAnswer = () -> CompletableFuture.completedFuture(points);
        return this;
    }

    /** The future returned by the nth spot-price call, for completing by hand. */
    CompletableFuture<List<ExchangeRate>> spotPriceAnswer(int index) {
        return spotPriceAnswers.get(index);
    }

    int spotPriceCalls() {
        return spotPriceCalls.get();
    }

    int topCoinCalls() {
        return topCoinCalls.get();
    }

    int priceHistoryCalls() {
        return priceHistoryCalls.get();
    }

    List<Collection<String>> requestedCoinIds() {
        return List.copyOf(requestedCoinIds);
    }
}
