package io.github.talant2801.cryptoconverter.service.ai;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;

/**
 * The seam that makes the AI layer optional.
 *
 * <p>Two implementations exist: {@link ClaudeAiAssistant} when a key is
 * configured, and {@link NoOpAiAssistant} when one is not. Because the choice is
 * made once, in the composition root, nothing downstream contains an
 * "if the AI is enabled" branch — the UI asks {@link #enabled()} once and hides
 * itself if the answer is no.
 *
 * <p>Note what is <em>not</em> here: no method returns a converted amount. The
 * model's only job is reading intent out of a sentence; the arithmetic stays in
 * {@link io.github.talant2801.cryptoconverter.service.ConversionService}, where
 * it is deterministic and tested.
 */
public interface AiAssistant {

    /** True when an API key was configured and the AI features can be used. */
    boolean enabled();

    /**
     * Reads a conversion request out of a sentence.
     *
     * <p>The result is validated against {@code knownCurrencies} before it is
     * returned, so a model that invents a coin produces a friendly error rather
     * than a request for a rate that does not exist.
     *
     * @param query what the user typed, for example "how much is 0.35 BTC in Polish zloty"
     * @param knownCurrencies every currency code the application will accept
     * @return a future completed with the intent, or completed exceptionally
     *     with an {@link AiException} carrying a message fit to show the user
     */
    CompletableFuture<ConversionIntent> parseConversion(String query, Collection<String> knownCurrencies);

    /**
     * A two or three sentence plain-language description of recent movement.
     *
     * <p>Informational only. Callers must display it alongside a permanent
     * not-financial-advice notice.
     */
    CompletableFuture<String> summariseMarket(MarketSnapshot snapshot);
}
