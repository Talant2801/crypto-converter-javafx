package io.github.talant2801.cryptoconverter.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.talant2801.cryptoconverter.client.ApiException;
import io.github.talant2801.cryptoconverter.domain.Coin;
import io.github.talant2801.cryptoconverter.domain.ExchangeRate;
import io.github.talant2801.cryptoconverter.domain.PricePoint;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Covers the three promises the cache makes: serve fresh data without a call,
 * make concurrent misses share one call, and prefer stale data to an error.
 *
 * <p>Time is moved with {@link TickingClock} and loads are completed by hand, so
 * nothing here sleeps and nothing opens a socket.
 */
class CachedRateServiceTest {

    private static final Instant START = Instant.parse("2024-05-01T12:00:00Z");
    private static final Duration RATE_TTL = Duration.ofSeconds(60);
    private static final Duration COIN_LIST_TTL = Duration.ofMinutes(30);

    private final TickingClock clock = new TickingClock(START);
    private final RecordingCoinGeckoClient client = new RecordingCoinGeckoClient();

    private CachedRateService service() {
        return new CachedRateService(client, RATE_TTL, COIN_LIST_TTL, clock);
    }

    private static ExchangeRate rate(String coin, String fiat, String price, Instant at) {
        return new ExchangeRate(coin, fiat, new BigDecimal(price), new BigDecimal("1.5"), at);
    }

    @Nested
    @DisplayName("time to live")
    class TimeToLive {

        @Test
        void servesASecondRequestWithinTheTtlWithoutCallingTheApi() {
            client.answeringSpotPrices(rate("bitcoin", "USD", "42000.5", START));
            CachedRateService service = service();

            ExchangeRate first = valueOf(service.spotRate("bitcoin", "usd"));
            clock.advance(Duration.ofSeconds(59));
            ExchangeRate second = valueOf(service.spotRate("bitcoin", "usd"));

            assertThat(second).isSameAs(first);
            assertThat(client.spotPriceCalls()).isOne();
        }

        @Test
        void refetchesOnceTheTtlHasElapsed() {
            client.answeringSpotPrices(rate("bitcoin", "USD", "42000.5", START));
            CachedRateService service = service();

            valueOf(service.spotRate("bitcoin", "usd"));
            clock.advance(RATE_TTL.plusSeconds(1));
            valueOf(service.spotRate("bitcoin", "usd"));

            assertThat(client.spotPriceCalls()).isEqualTo(2);
        }

        @Test
        void keepsTheCoinListLongerThanARate() {
            client.answeringTopCoins(List.of(new Coin("bitcoin", "btc", "Bitcoin", null)));
            CachedRateService service = service();

            valueOf(service.topCoins());
            // Well past the rate TTL, comfortably inside the coin-list TTL.
            clock.advance(Duration.ofMinutes(20));
            List<Coin> second = valueOf(service.topCoins());

            assertThat(second).singleElement().returns("bitcoin", Coin::id);
            assertThat(client.topCoinCalls()).isOne();
        }

        @Test
        void cachesEachPairSeparately() {
            client.answeringSpotPrices(
                    rate("bitcoin", "USD", "42000.5", START),
                    rate("bitcoin", "EUR", "38000.25", START),
                    rate("ethereum", "USD", "2200", START));
            CachedRateService service = service();

            valueOf(service.spotRate("bitcoin", "usd"));
            valueOf(service.spotRate("bitcoin", "eur"));
            valueOf(service.spotRate("ethereum", "usd"));
            valueOf(service.spotRate("bitcoin", "usd"));

            assertThat(client.spotPriceCalls()).isEqualTo(3);
        }

        @Test
        void cachesEachChartRangeSeparately() {
            client.answeringPriceHistory(List.of(new PricePoint(START, new BigDecimal("42000.5"))));
            CachedRateService service = service();

            valueOf(service.priceHistory("bitcoin", "usd", 7));
            valueOf(service.priceHistory("bitcoin", "usd", 7));
            valueOf(service.priceHistory("bitcoin", "usd", 30));

            assertThat(client.priceHistoryCalls()).isEqualTo(2);
        }

        @Test
        void treatsCasingAndSurroundingSpaceAsTheSamePair() {
            client.answeringSpotPrices(rate("bitcoin", "USD", "42000.5", START));
            CachedRateService service = service();

            valueOf(service.spotRate("Bitcoin", "USD"));
            valueOf(service.spotRate(" bitcoin ", " usd "));

            assertThat(client.spotPriceCalls()).isOne();
            assertThat(client.requestedCoinIds()).singleElement().isEqualTo(List.of("bitcoin"));
        }
    }

    @Nested
    @DisplayName("request coalescing")
    class Coalescing {

        @Test
        void concurrentIdenticalRequestsProduceExactlyOneCall() {
            CachedRateService service = service();

            // The client's default answer never completes on its own, so both
            // requests are in flight at the same time.
            CompletableFuture<ExchangeRate> first = service.spotRate("bitcoin", "usd");
            CompletableFuture<ExchangeRate> second = service.spotRate("bitcoin", "usd");

            assertThat(client.spotPriceCalls()).isOne();
            assertThat(first).isNotDone();

            client.spotPriceAnswer(0).complete(List.of(rate("bitcoin", "USD", "42000.5", START)));

            assertThat(valueOf(first).rate()).isEqualByComparingTo("42000.5");
            assertThat(valueOf(second).rate()).isEqualByComparingTo("42000.5");
            assertThat(client.spotPriceCalls()).isOne();
        }

        @Test
        void aFailedLoadIsSharedByEveryJoinedCaller() {
            CachedRateService service = service();

            CompletableFuture<ExchangeRate> first = service.spotRate("bitcoin", "usd");
            CompletableFuture<ExchangeRate> second = service.spotRate("bitcoin", "usd");

            client.spotPriceAnswer(0).completeExceptionally(new ApiException.Transport("network down"));

            assertThat(failureOf(first)).isInstanceOf(ApiException.Transport.class);
            assertThat(failureOf(second)).isInstanceOf(ApiException.Transport.class);
        }

        @Test
        void startsAFreshCallOnceTheSharedOneHasSettled() {
            CachedRateService service = service();

            service.spotRate("bitcoin", "usd");
            client.spotPriceAnswer(0).complete(List.of(rate("bitcoin", "USD", "42000.5", START)));
            clock.advance(RATE_TTL.plusSeconds(1));
            service.spotRate("bitcoin", "usd");

            // The finished load was deregistered, so the expired entry triggers
            // a real second call rather than joining a completed future.
            assertThat(client.spotPriceCalls()).isEqualTo(2);
        }

        @Test
        void cancellingOneCallerDoesNotCancelTheSharedLoad() {
            CachedRateService service = service();

            CompletableFuture<ExchangeRate> impatient = service.spotRate("bitcoin", "usd");
            CompletableFuture<ExchangeRate> patient = service.spotRate("bitcoin", "usd");
            impatient.cancel(true);

            client.spotPriceAnswer(0).complete(List.of(rate("bitcoin", "USD", "42000.5", START)));

            assertThat(valueOf(patient).rate()).isEqualByComparingTo("42000.5");
        }
    }

    @Nested
    @DisplayName("stale fallback")
    class StaleFallback {

        @Test
        void servesTheExpiredValueWhenTheRefreshFails() {
            client.answeringSpotPrices(rate("bitcoin", "USD", "42000.5", START));
            CachedRateService service = service();
            ExchangeRate cached = valueOf(service.spotRate("bitcoin", "usd"));

            clock.advance(RATE_TTL.plusMinutes(5));
            client.failingSpotPrices(new ApiException.Transport("network down"));
            ExchangeRate served = valueOf(service.spotRate("bitcoin", "usd"));

            assertThat(served).isSameAs(cached);
            // The value still carries its original timestamp, which is what the
            // UI reads to say how stale the figure it is showing has become.
            assertThat(served.fetchedAt()).isEqualTo(START);
            assertThat(served.isStaleAt(clock.instant(), RATE_TTL)).isTrue();
        }

        @Test
        void failsWhenThereIsNothingStaleToFallBackOn() {
            client.failingSpotPrices(new ApiException.Transport("network down"));

            assertThat(failureOf(service().spotRate("bitcoin", "usd")))
                    .isInstanceOf(ApiException.Transport.class)
                    .hasMessageContaining("network down");
        }

        @Test
        void recoversOnceTheApiAnswersAgain() {
            client.answeringSpotPrices(rate("bitcoin", "USD", "42000.5", START));
            CachedRateService service = service();
            valueOf(service.spotRate("bitcoin", "usd"));

            clock.advance(RATE_TTL.plusSeconds(1));
            client.failingSpotPrices(new ApiException.Transport("network down"));
            valueOf(service.spotRate("bitcoin", "usd"));

            clock.advance(RATE_TTL.plusSeconds(1));
            Instant later = clock.instant();
            client.answeringSpotPrices(rate("bitcoin", "USD", "43000", later));

            assertThat(valueOf(service.spotRate("bitcoin", "usd")).rate()).isEqualByComparingTo("43000");
        }
    }

    @Nested
    @DisplayName("input handling")
    class InputHandling {

        @Test
        void reportsAPairTheResponseDoesNotContainAsNotFound() {
            client.answeringSpotPrices(rate("bitcoin", "USD", "42000.5", START));

            assertThat(failureOf(service().spotRate("not-a-coin", "usd")))
                    .isInstanceOf(ApiException.NotFound.class)
                    .hasMessageContaining("not-a-coin");
        }

        @Test
        void rejectsBlankAndNegativeArguments() {
            CachedRateService service = service();

            assertThatThrownBy(() -> service.spotRate("  ", "usd"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("coinId");
            assertThatThrownBy(() -> service.priceHistory("bitcoin", "usd", 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("days");
            assertThat(client.spotPriceCalls()).isZero();
        }
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

    private static Throwable failureOf(CompletableFuture<?> future) {
        try {
            Object value = future.get(5, TimeUnit.SECONDS);
            throw new AssertionError("Expected a failure but the future produced " + value);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for " + future, e);
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (TimeoutException e) {
            throw new AssertionError("The future never completed", e);
        }
    }
}
