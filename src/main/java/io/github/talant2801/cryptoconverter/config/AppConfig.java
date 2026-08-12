package io.github.talant2801.cryptoconverter.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Every externally tunable knob in the application, resolved once at startup by
 * {@link ConfigLoader} and passed down through the composition root.
 *
 * <p>Settings live in a record rather than being read from the environment at
 * the point of use so that the resolution rules exist in exactly one place and
 * collaborators can be constructed in tests without touching the environment.
 *
 * <p>{@code anthropicApiKey} is an {@link Optional} because the AI layer is
 * genuinely optional: absent simply means the no-op assistant is wired in. The
 * key must never be logged, printed, or placed in an exception message, which
 * is why this record deliberately overrides {@link #toString()}.
 */
public record AppConfig(
        Optional<String> anthropicApiKey,
        URI coinGeckoBaseUri,
        Duration httpTimeout,
        Duration rateCacheTtl,
        Duration coinListCacheTtl,
        Path databasePath,
        int maxRetries,
        Duration retryBaseDelay) {

    public AppConfig {
        Objects.requireNonNull(anthropicApiKey, "anthropicApiKey");
        Objects.requireNonNull(coinGeckoBaseUri, "coinGeckoBaseUri");
        Objects.requireNonNull(databasePath, "databasePath");
        requirePositive(httpTimeout, "httpTimeout");
        requirePositive(rateCacheTtl, "rateCacheTtl");
        requirePositive(coinListCacheTtl, "coinListCacheTtl");
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must not be negative: " + maxRetries);
        }
        Objects.requireNonNull(retryBaseDelay, "retryBaseDelay");
        if (retryBaseDelay.isNegative()) {
            throw new IllegalArgumentException("retryBaseDelay must not be negative: " + retryBaseDelay);
        }
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be positive: " + value);
        }
    }

    /** True when an Anthropic key was configured and the AI layer can be enabled. */
    public boolean aiEnabled() {
        return anthropicApiKey.isPresent();
    }

    /**
     * Redacts the API key. The default record {@code toString} would inline it,
     * and this object is the kind of thing that ends up in a debug log.
     */
    @Override
    public String toString() {
        return "AppConfig[anthropicApiKey=%s, coinGeckoBaseUri=%s, httpTimeout=%s, rateCacheTtl=%s, "
                        .formatted(anthropicApiKey.isPresent() ? "<set>" : "<unset>",
                                coinGeckoBaseUri, httpTimeout, rateCacheTtl)
                + "coinListCacheTtl=%s, databasePath=%s, maxRetries=%d, retryBaseDelay=%s]"
                        .formatted(coinListCacheTtl, databasePath, maxRetries, retryBaseDelay);
    }
}
