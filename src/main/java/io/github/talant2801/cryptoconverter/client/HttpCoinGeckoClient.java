package io.github.talant2801.cryptoconverter.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.talant2801.cryptoconverter.config.AppConfig;
import io.github.talant2801.cryptoconverter.domain.Coin;
import io.github.talant2801.cryptoconverter.domain.ExchangeRate;
import io.github.talant2801.cryptoconverter.domain.PricePoint;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link CoinGeckoClient} over {@link HttpClient}, with backoff on the failures
 * worth repeating.
 *
 * <p>The class owns transport concerns only: building the request, classifying
 * the status code into an {@link ApiException}, and deciding whether to try
 * again. Interpreting response bodies belongs to
 * {@link CoinGeckoResponseMapper}, which is why that is a separate collaborator.
 *
 * <p>Nothing here blocks. The retry delay is applied with
 * {@link CompletableFuture#delayedExecutor}, so a request waiting out a rate
 * limit occupies no thread at all — which matters because the caller may well
 * be the JavaFX application thread.
 *
 * <p>The clock, the jitter source and the retry executor are injected so tests
 * can pin timing entirely and run with no network and no sleeping.
 */
public final class HttpCoinGeckoClient implements CoinGeckoClient {

    private static final Logger log = LoggerFactory.getLogger(HttpCoinGeckoClient.class);

    /** The endpoint caps {@code per_page} here, so asking for more is a bug, not a big response. */
    private static final int MAX_PAGE_SIZE = 250;

    private final HttpClient httpClient;
    private final URI baseUri;
    private final Duration requestTimeout;
    private final RetryPolicy retryPolicy;
    private final CoinGeckoResponseMapper mapper;
    private final Executor retryExecutor;
    private final DoubleSupplier jitter;
    private final Clock clock;

    HttpCoinGeckoClient(
            HttpClient httpClient,
            URI baseUri,
            Duration requestTimeout,
            RetryPolicy retryPolicy,
            ObjectMapper objectMapper,
            Executor retryExecutor,
            DoubleSupplier jitter,
            Clock clock) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.baseUri = Objects.requireNonNull(baseUri, "baseUri");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        this.mapper = new CoinGeckoResponseMapper(Objects.requireNonNull(objectMapper, "objectMapper"));
        this.retryExecutor = Objects.requireNonNull(retryExecutor, "retryExecutor");
        this.jitter = Objects.requireNonNull(jitter, "jitter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Builds a client from resolved configuration.
     *
     * <p>{@code executor} carries both the HTTP callbacks and the retry delays,
     * so the application keeps its I/O on the pool it owns rather than on the
     * common ForkJoinPool.
     */
    public static HttpCoinGeckoClient create(AppConfig config, Executor executor) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(executor, "executor");
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(config.httpTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .executor(executor)
                .build();
        return new HttpCoinGeckoClient(
                httpClient,
                config.coinGeckoBaseUri(),
                config.httpTimeout(),
                RetryPolicy.of(config.maxRetries(), config.retryBaseDelay()),
                new ObjectMapper(),
                executor,
                () -> ThreadLocalRandom.current().nextDouble(),
                Clock.systemUTC());
    }

    @Override
    public CompletableFuture<List<Coin>> topCoins(int limit, String vsCurrency) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_PAGE_SIZE + ": " + limit);
        }
        Map<String, String> query = new LinkedHashMap<>();
        query.put("vs_currency", code(vsCurrency, "vsCurrency"));
        query.put("order", "market_cap_desc");
        query.put("per_page", Integer.toString(limit));
        query.put("page", "1");
        return get(uri("/coins/markets", query), mapper::toCoins);
    }

    @Override
    public CompletableFuture<List<ExchangeRate>> spotPrices(
            Collection<String> coinIds, Collection<String> fiatCodes) {

        List<String> ids = codes(coinIds, "coinIds");
        List<String> fiats = codes(fiatCodes, "fiatCodes");
        Map<String, String> query = new LinkedHashMap<>();
        query.put("ids", String.join(",", ids));
        query.put("vs_currencies", String.join(",", fiats));
        query.put("include_24hr_change", "true");
        // The timestamp is read when the body arrives, not when it is requested,
        // so a rate that waited out three retries is not aged as if it were fresh.
        return get(uri("/simple/price", query),
                body -> mapper.toExchangeRates(body, ids, fiats, clock.instant()));
    }

    @Override
    public CompletableFuture<List<PricePoint>> priceHistory(String coinId, String fiatCode, int days) {
        String id = code(coinId, "coinId");
        if (days < 1) {
            throw new IllegalArgumentException("days must be positive: " + days);
        }
        Map<String, String> query = new LinkedHashMap<>();
        query.put("vs_currency", code(fiatCode, "fiatCode"));
        query.put("days", Integer.toString(days));
        String path = "/coins/" + URLEncoder.encode(id, StandardCharsets.UTF_8) + "/market_chart";
        return get(uri(path, query), mapper::toPricePoints);
    }

    private <T> CompletableFuture<T> get(URI uri, Function<String, T> parse) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        return attempt(request, parse, 0);
    }

    private <T> CompletableFuture<T> attempt(HttpRequest request, Function<String, T> parse, int attemptNumber) {
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .handle((response, error) -> settle(request, parse, attemptNumber, response, error))
                .thenCompose(Function.identity());
    }

    /** Turns one completed exchange into either a value, a retry, or a failed future. */
    private <T> CompletableFuture<T> settle(
            HttpRequest request,
            Function<String, T> parse,
            int attemptNumber,
            HttpResponse<String> response,
            Throwable error) {

        if (error != null) {
            return retryOrFail(request, parse, attemptNumber, asApiException(error));
        }
        try {
            return CompletableFuture.completedFuture(read(response, parse));
        } catch (ApiException failure) {
            return retryOrFail(request, parse, attemptNumber, failure);
        }
    }

    /**
     * Maps the status code onto the sealed failure set, or parses the body.
     *
     * <p>A 4xx other than 429 and 404 means this side built a request CoinGecko
     * would not accept — the same class of "our fault, repeating it is
     * pointless" problem as an unparseable body, hence {@code Malformed}.
     */
    private <T> T read(HttpResponse<String> response, Function<String, T> parse) {
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return parse.apply(response.body());
        }
        throw switch (status / 100) {
            case 4 -> switch (status) {
                case 429 -> new ApiException.RateLimited(
                        "CoinGecko rate limit reached", retryAfterHint(response));
                case 404 -> new ApiException.NotFound(
                        "CoinGecko has no data at " + response.uri().getPath());
                default -> new ApiException.Malformed("CoinGecko rejected the request with HTTP " + status);
            };
            case 5 -> new ApiException.Transport("CoinGecko returned HTTP " + status);
            default -> new ApiException.Malformed("Unexpected HTTP status " + status);
        };
    }

    private <T> CompletableFuture<T> retryOrFail(
            HttpRequest request, Function<String, T> parse, int attemptNumber, ApiException failure) {

        if (!failure.isRetryable() || attemptNumber >= retryPolicy.maxRetries()) {
            return CompletableFuture.failedFuture(failure);
        }
        Duration delay = backoff(attemptNumber, failure);
        log.debug("Retrying {} in {} ms (attempt {} of {}): {}",
                request.uri().getPath(), delay.toMillis(),
                attemptNumber + 1, retryPolicy.maxRetries(), failure.getMessage());

        Executor delayed = CompletableFuture.delayedExecutor(
                Math.max(delay.toMillis(), 0L), TimeUnit.MILLISECONDS, retryExecutor);
        return CompletableFuture.supplyAsync(() -> attempt(request, parse, attemptNumber + 1), delayed)
                .thenCompose(Function.identity());
    }

    /**
     * Prefers the server's own {@code Retry-After} over computed backoff — it
     * knows when the window resets — but never waits longer than the policy's
     * ceiling, so a generous hint cannot strand the UI on a spinner.
     */
    private Duration backoff(int attemptNumber, ApiException failure) {
        if (failure instanceof ApiException.RateLimited rateLimited) {
            Optional<Duration> hint = rateLimited.retryAfter();
            if (hint.isPresent()) {
                Duration ceiling = retryPolicy.maxDelay();
                return hint.get().compareTo(ceiling) > 0 ? ceiling : hint.get();
            }
        }
        return retryPolicy.delayBefore(attemptNumber, jitter);
    }

    /** Only the delta-seconds form is read; the HTTP-date form falls back to backoff. */
    private static Duration retryAfterHint(HttpResponse<String> response) {
        return response.headers()
                .firstValue("Retry-After")
                .map(String::trim)
                .flatMap(value -> {
                    try {
                        long seconds = Long.parseLong(value);
                        return seconds >= 0 ? Optional.of(Duration.ofSeconds(seconds)) : Optional.<Duration>empty();
                    } catch (NumberFormatException notDeltaSeconds) {
                        return Optional.empty();
                    }
                })
                .orElse(null);
    }

    private static ApiException asApiException(Throwable error) {
        Throwable cause = error instanceof CompletionException wrapper && wrapper.getCause() != null
                ? wrapper.getCause()
                : error;
        if (cause instanceof ApiException alreadyTyped) {
            return alreadyTyped;
        }
        String detail = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
        return new ApiException.Transport("Could not reach CoinGecko: " + detail, cause);
    }

    private URI uri(String path, Map<String, String> query) {
        StringBuilder url = new StringBuilder(baseUri.toString()).append(path);
        char separator = '?';
        for (Map.Entry<String, String> parameter : query.entrySet()) {
            url.append(separator)
                    .append(parameter.getKey())
                    .append('=')
                    .append(URLEncoder.encode(parameter.getValue(), StandardCharsets.UTF_8));
            separator = '&';
        }
        return URI.create(url.toString());
    }

    private static String code(String value, String name) {
        Objects.requireNonNull(value, name);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        // CoinGecko ids and vs_currency codes are lower-case; normalising here
        // keeps "USD" from the UI and "usd" from a test hitting the same cache key.
        return trimmed.toLowerCase();
    }

    private static List<String> codes(Collection<String> values, String name) {
        Objects.requireNonNull(values, name);
        if (values.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        // Distinct: a duplicate id costs request length and returns nothing new.
        return values.stream()
                .map(value -> code(value, name))
                .distinct()
                .collect(Collectors.toUnmodifiableList());
    }
}
