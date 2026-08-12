package io.github.talant2801.cryptoconverter.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.talant2801.cryptoconverter.domain.Coin;
import io.github.talant2801.cryptoconverter.domain.ExchangeRate;
import io.github.talant2801.cryptoconverter.domain.PricePoint;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Exercises the client against a scripted {@link StubHttpClient}.
 *
 * <p>No test here opens a socket, so the suite passes offline. Retry delays are
 * configured to zero rather than being waited out, which keeps the timing
 * assertions about <em>how many</em> calls happen instead of how long they take.
 */
class HttpCoinGeckoClientTest {

    private static final URI BASE_URI = URI.create("https://stub.local/api/v3");
    private static final Instant FETCHED_AT = Instant.parse("2024-05-01T12:00:00Z");

    /** Zero delays, but a real ceiling still exercised by the Retry-After tests. */
    private static final RetryPolicy IMMEDIATE = new RetryPolicy(3, Duration.ZERO, Duration.ZERO);

    private static final String MARKETS_JSON =
            """
            [
              {"id":"bitcoin","symbol":"btc","name":"Bitcoin","image":"https://img/btc.png",
               "current_price":42000.5,"price_change_percentage_24h":-1.25,"market_cap":800000000},
              {"id":"ethereum","symbol":"eth","name":"Ethereum","image":"https://img/eth.png",
               "current_price":2200.0,"price_change_percentage_24h":2.5,"market_cap":300000000}
            ]
            """;

    private static final String SIMPLE_PRICE_JSON =
            """
            {
              "bitcoin":{"usd":42000.5,"usd_24h_change":-1.2345,"eur":38000.25,"eur_24h_change":-1.1},
              "ethereum":{"usd":2200.0,"usd_24h_change":2.5,"eur":2000.0,"eur_24h_change":2.4}
            }
            """;

    private static final String MARKET_CHART_JSON =
            """
            {
              "prices":[[1714564800000,42000.5],[1714651200000,42500.25],[1714737600000,41800.0]],
              "market_caps":[[1714564800000,800000000]],
              "total_volumes":[[1714564800000,25000000]]
            }
            """;

    private final StubHttpClient http = new StubHttpClient();

    private HttpCoinGeckoClient clientWith(RetryPolicy policy) {
        return new HttpCoinGeckoClient(
                http,
                BASE_URI,
                Duration.ofSeconds(5),
                policy,
                new ObjectMapper(),
                Runnable::run,
                () -> 0.5,
                Clock.fixed(FETCHED_AT, ZoneOffset.UTC));
    }

    private HttpCoinGeckoClient client() {
        return clientWith(IMMEDIATE);
    }

    @Nested
    @DisplayName("request building and mapping")
    class RequestsAndMapping {

        @Test
        void topCoinsRequestsTheMarketsEndpointAndMapsEveryRow() {
            http.respondingOk(MARKETS_JSON);

            List<Coin> coins = valueOf(client().topCoins(100, "USD"));

            assertThat(http.lastUri().toString())
                    .isEqualTo("https://stub.local/api/v3/coins/markets"
                            + "?vs_currency=usd&order=market_cap_desc&per_page=100&page=1");
            assertThat(coins)
                    .containsExactly(
                            new Coin("bitcoin", "btc", "Bitcoin", "https://img/btc.png"),
                            new Coin("ethereum", "eth", "Ethereum", "https://img/eth.png"));
        }

        @Test
        void topCoinsRejectsAPageSizeTheEndpointCannotServe() {
            assertThatThrownBy(() -> client().topCoins(251, "usd"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("250");
            assertThatThrownBy(() -> client().topCoins(0, "usd")).isInstanceOf(IllegalArgumentException.class);
            assertThat(http.callCount()).isZero();
        }

        @Test
        void spotPricesBatchesEveryCoinAndFiatIntoOneRequest() {
            http.respondingOk(SIMPLE_PRICE_JSON);

            valueOf(client().spotPrices(List.of("bitcoin", "ethereum"), List.of("usd", "eur")));

            assertThat(http.lastUri().toString())
                    .isEqualTo("https://stub.local/api/v3/simple/price"
                            + "?ids=bitcoin%2Cethereum&vs_currencies=usd%2Ceur&include_24hr_change=true");
            assertThat(http.callCount()).isOne();
        }

        @Test
        void spotPricesMapsEveryPairAndStampsTheFetchTime() {
            http.respondingOk(SIMPLE_PRICE_JSON);

            List<ExchangeRate> rates =
                    valueOf(client().spotPrices(List.of("bitcoin", "ethereum"), List.of("usd", "eur")));

            assertThat(rates).hasSize(4);
            assertThat(rates).allSatisfy(rate -> assertThat(rate.fetchedAt()).isEqualTo(FETCHED_AT));
            assertThat(rates.get(0).base()).isEqualTo("bitcoin");
            assertThat(rates.get(0).quote()).isEqualTo("USD");
            assertThat(rates.get(0).rate()).isEqualByComparingTo("42000.5");
            assertThat(rates.get(0).change24h()).isEqualByComparingTo("-1.2345");
            assertThat(rates.get(1).quote()).isEqualTo("EUR");
            assertThat(rates.get(3).base()).isEqualTo("ethereum");
        }

        @Test
        void spotPricesNormalisesCasingAndDropsDuplicateIds() {
            http.respondingOk(SIMPLE_PRICE_JSON);

            valueOf(client().spotPrices(List.of("Bitcoin", "bitcoin", " ETHEREUM "), List.of("USD")));

            assertThat(http.lastUri().toString())
                    .contains("ids=bitcoin%2Cethereum")
                    .contains("vs_currencies=usd");
        }

        @Test
        void spotPricesSkipsPairsTheResponseOmitsRatherThanFailing() {
            // Ethereum is absent entirely and bitcoin carries no EUR quote.
            http.respondingOk("{\"bitcoin\":{\"usd\":42000.5,\"usd_24h_change\":-1.2}}");

            List<ExchangeRate> rates =
                    valueOf(client().spotPrices(List.of("bitcoin", "ethereum"), List.of("usd", "eur")));

            assertThat(rates).hasSize(1);
            assertThat(rates.get(0).quote()).isEqualTo("USD");
        }

        @Test
        void spotPricesRejectsAnEmptyRequest() {
            assertThatThrownBy(() -> client().spotPrices(List.of(), List.of("usd")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("coinIds");
            assertThatThrownBy(() -> client().spotPrices(List.of("bitcoin"), List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("fiatCodes");
            assertThat(http.callCount()).isZero();
        }

        @Test
        void topCoinsDropsAnUnusableRowRatherThanTheWholePage() {
            // A row without an id cannot be requested later, but the other one can.
            http.respondingOk(
                    """
                    [
                      {"symbol":"???","name":"Mystery","image":null},
                      {"id":"ethereum","symbol":"eth","name":"Ethereum","image":null}
                    ]
                    """);

            List<Coin> coins = valueOf(client().topCoins(10, "usd"));

            assertThat(coins).singleElement().returns("ethereum", Coin::id);
            assertThat(coins.get(0).imageUrl()).isNull();
        }

        @Test
        void spotPricesIgnoresAQuoteThatIsNotARealPrice() {
            // ExchangeRate rejects a non-positive rate, so it is dropped upstream
            // of the constructor rather than blowing up the whole batch.
            http.respondingOk("{\"bitcoin\":{\"usd\":0,\"eur\":38000.25,\"eur_24h_change\":-1.1}}");

            List<ExchangeRate> rates =
                    valueOf(client().spotPrices(List.of("bitcoin"), List.of("usd", "eur")));

            assertThat(rates).singleElement().returns("EUR", ExchangeRate::quote);
        }

        @Test
        void spotPricesToleratesAMissingChangeFigure() {
            http.respondingOk("{\"bitcoin\":{\"usd\":42000.5}}");

            List<ExchangeRate> rates = valueOf(client().spotPrices(List.of("bitcoin"), List.of("usd")));

            assertThat(rates).singleElement().returns(null, ExchangeRate::change24h);
        }

        @Test
        void priceHistoryMapsTheSeriesInOrder() {
            http.respondingOk(MARKET_CHART_JSON);

            List<PricePoint> points = valueOf(client().priceHistory("bitcoin", "usd", 7));

            assertThat(http.lastUri().toString())
                    .isEqualTo("https://stub.local/api/v3/coins/bitcoin/market_chart?vs_currency=usd&days=7");
            assertThat(points).hasSize(3);
            assertThat(points.get(0).at()).isEqualTo(Instant.ofEpochMilli(1714564800000L));
            assertThat(points.get(0).price()).isEqualByComparingTo("42000.5");
            assertThat(points.get(2).price()).isEqualByComparingTo("41800.0");
        }

        @Test
        void priceHistoryRejectsANonPositiveRange() {
            assertThatThrownBy(() -> client().priceHistory("bitcoin", "usd", 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("days");
            assertThatThrownBy(() -> client().priceHistory(" ", "usd", 7))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(http.callCount()).isZero();
        }
    }

    @Nested
    @DisplayName("retry policy")
    class Retrying {

        @Test
        void retriesAfterARateLimitAndReturnsTheEventualSuccess() {
            http.respondingWithStatus(429).respondingOk(MARKETS_JSON);

            List<Coin> coins = valueOf(client().topCoins(10, "usd"));

            assertThat(coins).hasSize(2);
            assertThat(http.callCount()).isEqualTo(2);
        }

        @Test
        void givesUpWithRateLimitedOnceTheRetryBudgetIsSpent() {
            http.alwaysRespondingWithStatus(429);

            ApiException failure = failureOf(client().topCoins(10, "usd"));

            assertThat(failure).isInstanceOf(ApiException.RateLimited.class);
            assertThat(failure.isRetryable()).isTrue();
            // One initial attempt plus the three retries the policy allows.
            assertThat(http.callCount()).isEqualTo(4);
        }

        @Test
        void capsAGenerousRetryAfterHintAtThePolicyCeiling() {
            // An hour-long hint would hang this test if the ceiling were ignored.
            http.respondingWithStatus(429, Map.of("Retry-After", List.of("3600")))
                    .respondingOk(MARKETS_JSON);

            List<Coin> coins = valueOf(clientWith(new RetryPolicy(3, Duration.ZERO, Duration.ofMillis(10)))
                    .topCoins(10, "usd"));

            assertThat(coins).hasSize(2);
            assertThat(http.callCount()).isEqualTo(2);
        }

        @Test
        void fallsBackToBackoffWhenRetryAfterIsAnHttpDate() {
            http.respondingWithStatus(429, Map.of("Retry-After", List.of("Wed, 01 May 2024 12:00:05 GMT")))
                    .respondingOk(MARKETS_JSON);

            assertThat(valueOf(client().topCoins(10, "usd"))).hasSize(2);
            assertThat(http.callCount()).isEqualTo(2);
        }

        @Test
        void retriesServerErrorsAsTransportFailures() {
            http.respondingWithStatus(503).respondingWithStatus(500).respondingOk(MARKETS_JSON);

            assertThat(valueOf(client().topCoins(10, "usd"))).hasSize(2);
            assertThat(http.callCount()).isEqualTo(3);
        }

        @Test
        void retriesWhenTheRequestNeverReachesTheServer() {
            http.failingWith(new IOException("connection reset")).respondingOk(MARKETS_JSON);

            assertThat(valueOf(client().topCoins(10, "usd"))).hasSize(2);
            assertThat(http.callCount()).isEqualTo(2);
        }

        @Test
        void surfacesTransportWithItsCauseOnceRetriesAreExhausted() {
            http.alwaysFailingWith(new HttpTimeoutException("request timed out"));

            ApiException failure = failureOf(client().priceHistory("bitcoin", "usd", 30));

            assertThat(failure).isInstanceOf(ApiException.Transport.class);
            assertThat(failure).hasMessageContaining("request timed out");
            assertThat(failure).hasCauseInstanceOf(HttpTimeoutException.class);
            assertThat(http.callCount()).isEqualTo(4);
        }

        @Test
        void respectsAZeroRetryBudget() {
            http.alwaysRespondingWithStatus(429);

            ApiException failure =
                    failureOf(clientWith(new RetryPolicy(0, Duration.ZERO, Duration.ZERO)).topCoins(10, "usd"));

            assertThat(failure).isInstanceOf(ApiException.RateLimited.class);
            assertThat(http.callCount()).isOne();
        }
    }

    @Nested
    @DisplayName("non-retryable failures")
    class FailingFast {

        @Test
        void doesNotRetryANotFound() {
            http.alwaysRespondingWithStatus(404);

            ApiException failure = failureOf(client().priceHistory("no-such-coin", "usd", 7));

            assertThat(failure).isInstanceOf(ApiException.NotFound.class);
            assertThat(failure.isRetryable()).isFalse();
            assertThat(http.callCount()).isOne();
        }

        @Test
        void doesNotRetryARequestTheApiRejected() {
            http.alwaysRespondingWithStatus(400);

            ApiException failure = failureOf(client().topCoins(10, "usd"));

            assertThat(failure).isInstanceOf(ApiException.Malformed.class);
            assertThat(failure).hasMessageContaining("400");
            assertThat(http.callCount()).isOne();
        }

        @Test
        void doesNotRetryAnUnparseableBody() {
            http.respondingWith(200, "<html>gateway error</html>");

            ApiException failure = failureOf(client().topCoins(10, "usd"));

            assertThat(failure).isInstanceOf(ApiException.Malformed.class);
            assertThat(http.callCount()).isOne();
        }

        @Test
        void reportsUnknownCoinIdsAsNotFoundWithoutRetrying() {
            http.respondingOk("{}");

            ApiException failure = failureOf(client().spotPrices(List.of("nonexistent"), List.of("usd")));

            assertThat(failure).isInstanceOf(ApiException.NotFound.class);
            assertThat(http.callCount()).isOne();
        }

        @Test
        void reportsACoinListWithNoUsableRowsAsMalformed() {
            http.respondingOk("[{\"symbol\":\"btc\"},{\"name\":\"Ethereum\"}]");

            ApiException failure = failureOf(client().topCoins(10, "usd"));

            assertThat(failure).isInstanceOf(ApiException.Malformed.class);
            assertThat(http.callCount()).isOne();
        }

        @Test
        void rejectsAPriceHistoryWithoutASeries() {
            http.respondingOk("{\"market_caps\":[[1714564800000,800000000]]}");

            ApiException failure = failureOf(client().priceHistory("bitcoin", "usd", 90));

            assertThat(failure).isInstanceOf(ApiException.Malformed.class);
            assertThat(failure).hasMessageContaining("no prices array");
        }

        @Test
        void rejectsAPriceSeriesWithAMalformedSample() {
            http.respondingOk("{\"prices\":[[1714564800000,42000.5],[1714651200000]]}");

            ApiException failure = failureOf(client().priceHistory("bitcoin", "usd", 7));

            assertThat(failure).isInstanceOf(ApiException.Malformed.class);
            assertThat(http.callCount()).isOne();
        }
    }

    // --- Future helpers -------------------------------------------------------

    private static <T> T valueOf(CompletableFuture<T> future) {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while awaiting the result", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new AssertionError("Expected the future to succeed", e);
        }
    }

    private static ApiException failureOf(CompletableFuture<?> future) {
        try {
            Object value = future.get(5, TimeUnit.SECONDS);
            throw new AssertionError("Expected the future to fail but it returned " + value);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while awaiting the failure", e);
        } catch (TimeoutException e) {
            throw new AssertionError("The future never completed", e);
        } catch (ExecutionException e) {
            assertThat(e.getCause()).isInstanceOf(ApiException.class);
            return (ApiException) e.getCause();
        }
    }

    /**
     * Money must never round-trip through a double. The digits below cannot
     * survive binary floating point, so this fails loudly if the DTO binding
     * ever loosens to {@code double}.
     */
    @Test
    void preservesFullPrecisionRatherThanRoundingThroughDouble() {
        http.respondingOk("{\"bitcoin\":{\"usd\":0.12345678901234567890123456789}}");

        List<ExchangeRate> rates = valueOf(client().spotPrices(List.of("bitcoin"), List.of("usd")));

        assertThat(rates.get(0).rate()).isEqualByComparingTo(new BigDecimal("0.12345678901234567890123456789"));
    }
}
