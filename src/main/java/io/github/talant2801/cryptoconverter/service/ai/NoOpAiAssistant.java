package io.github.talant2801.cryptoconverter.service.ai;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;

/**
 * The assistant used when no API key is configured.
 *
 * <p>Reports itself disabled and fails any call with a plain explanation rather
 * than pretending to answer. The UI reads {@link #enabled()} when it is built
 * and hides the AI pane, so in practice these methods are never reached — they
 * exist so that a future caller which forgets to check gets a clear message
 * instead of a null, an empty string, or a crash.
 *
 * <p>A null assistant reference would have done the same job and produced a
 * {@link NullPointerException} at the worst possible moment.
 */
public final class NoOpAiAssistant implements AiAssistant {

    private static final String DISABLED =
            "AI features are turned off. Set ANTHROPIC_API_KEY to enable them.";

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public CompletableFuture<ConversionIntent> parseConversion(String query, Collection<String> knownCurrencies) {
        return CompletableFuture.failedFuture(new AiException(DISABLED));
    }

    @Override
    public CompletableFuture<String> summariseMarket(MarketSnapshot snapshot) {
        return CompletableFuture.failedFuture(new AiException(DISABLED));
    }
}
