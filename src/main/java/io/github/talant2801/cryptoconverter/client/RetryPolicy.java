package io.github.talant2801.cryptoconverter.client;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * How long to wait before re-issuing a failed request.
 *
 * <p>The delay doubles per attempt and is then multiplied by a random factor in
 * [0,1) — "full jitter". Plain exponential backoff would have every pane that
 * failed during one rate-limit window retry at the same instant and trip the
 * limit again; spreading the retries across the window is the whole point, and
 * costs on average half the wait.
 *
 * <p>{@code maxDelay} caps the growth so a third attempt cannot leave the user
 * staring at a spinner for a minute.
 *
 * @param maxRetries retries after the initial attempt, so 3 means at most 4 calls
 * @param baseDelay the un-jittered delay before the first retry
 * @param maxDelay ceiling applied before jitter
 */
public record RetryPolicy(int maxRetries, Duration baseDelay, Duration maxDelay) {

    public RetryPolicy {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must not be negative: " + maxRetries);
        }
        Objects.requireNonNull(baseDelay, "baseDelay");
        Objects.requireNonNull(maxDelay, "maxDelay");
        if (baseDelay.isNegative()) {
            throw new IllegalArgumentException("baseDelay must not be negative: " + baseDelay);
        }
        if (maxDelay.compareTo(baseDelay) < 0) {
            throw new IllegalArgumentException("maxDelay " + maxDelay + " is below baseDelay " + baseDelay);
        }
    }

    public static RetryPolicy of(int maxRetries, Duration baseDelay) {
        return new RetryPolicy(maxRetries, baseDelay, Duration.ofSeconds(20));
    }

    /**
     * The delay before the retry that follows {@code attempt}, where the initial
     * attempt is 0.
     *
     * <p>The randomness is a parameter so tests can pin it; production passes
     * {@link ThreadLocalRandom}.
     *
     * @param randomFraction supplies a value in [0,1)
     */
    public Duration delayBefore(int attempt, DoubleSupplier randomFraction) {
        if (attempt < 0) {
            throw new IllegalArgumentException("attempt must not be negative: " + attempt);
        }
        // Shift rather than Math.pow, and clamp the exponent so a large attempt
        // count cannot overflow the long before maxDelay gets a chance to cap it.
        long factor = 1L << Math.min(attempt, 32);
        long uncapped = saturatingMultiply(baseDelay.toMillis(), factor);
        long capped = Math.min(uncapped, maxDelay.toMillis());
        return Duration.ofMillis((long) (capped * randomFraction.getAsDouble()));
    }

    /** Convenience overload using the shared thread-local generator. */
    public Duration delayBefore(int attempt) {
        return delayBefore(attempt, () -> ThreadLocalRandom.current().nextDouble());
    }

    private static long saturatingMultiply(long value, long factor) {
        try {
            return Math.multiplyExact(value, factor);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }
}
