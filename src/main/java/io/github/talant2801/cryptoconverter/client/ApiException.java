package io.github.talant2801.cryptoconverter.client;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Every failure the CoinGecko client can surface, as a closed set.
 *
 * <p>Sealing the hierarchy lets callers switch over the failure exhaustively —
 * the UI can decide between "retrying, hold on", "that coin does not exist" and
 * "showing stale data" without string-matching messages, and the compiler will
 * flag any case a future variant leaves unhandled.
 *
 * <p>{@link #isRetryable()} lives here rather than in the retry loop because
 * whether a failure is transient is a property of the failure itself. Rate
 * limits and transport hiccups pass; a 404 or an unparseable body will fail the
 * same way however many times it is repeated, so retrying only wastes the
 * user's time and the API's budget.
 */
public abstract sealed class ApiException extends RuntimeException
        permits ApiException.RateLimited,
                ApiException.NotFound,
                ApiException.Transport,
                ApiException.Malformed {

    private ApiException(String message) {
        super(message);
    }

    private ApiException(String message, Throwable cause) {
        super(message, cause);
    }

    /** True when repeating the same request has a realistic chance of succeeding. */
    public abstract boolean isRetryable();

    /** HTTP 429 — the free tier's request budget is exhausted for now. */
    public static final class RateLimited extends ApiException {

        private final transient Duration retryAfter;

        /**
         * @param retryAfter the upstream {@code Retry-After} hint, or null when
         *     the response did not carry one and backoff must be guessed
         */
        public RateLimited(String message, Duration retryAfter) {
            super(message);
            this.retryAfter = retryAfter;
        }

        public Optional<Duration> retryAfter() {
            return Optional.ofNullable(retryAfter);
        }

        @Override
        public boolean isRetryable() {
            return true;
        }
    }

    /** HTTP 404, or a 2xx response that simply omitted the coin that was asked for. */
    public static final class NotFound extends ApiException {

        public NotFound(String message) {
            super(message);
        }

        @Override
        public boolean isRetryable() {
            return false;
        }
    }

    /**
     * The request never produced a usable HTTP response: connection refused,
     * timeout, DNS failure, or a 5xx from the far side.
     */
    public static final class Transport extends ApiException {

        public Transport(String message) {
            super(message);
        }

        public Transport(String message, Throwable cause) {
            super(message, cause);
        }

        @Override
        public boolean isRetryable() {
            return true;
        }
    }

    /**
     * The exchange completed but produced nothing usable: a body that does not
     * match the expected shape, or a request the API rejected outright. Either
     * way the fault is on this side and repeating it changes nothing.
     */
    public static final class Malformed extends ApiException {

        public Malformed(String message) {
            super(message);
        }

        public Malformed(String message, Throwable cause) {
            super(message, Objects.requireNonNull(cause, "cause"));
        }

        @Override
        public boolean isRetryable() {
            return false;
        }
    }
}
