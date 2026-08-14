package io.github.talant2801.cryptoconverter.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.talant2801.cryptoconverter.config.AppConfig;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The disabled path. What matters is that a missing key produces a working
 * application rather than a broken one.
 */
class NoOpAiAssistantTest {

    private final NoOpAiAssistant assistant = new NoOpAiAssistant();

    private static AppConfig config(Optional<String> apiKey) {
        return new AppConfig(
                apiKey,
                URI.create("https://api.coingecko.com/api/v3"),
                Duration.ofSeconds(10),
                Duration.ofSeconds(60),
                Duration.ofMinutes(30),
                Path.of("/tmp/does-not-need-to-exist.db"),
                3,
                Duration.ofMillis(500));
    }

    @Test
    void reportsItselfDisabledSoTheUiCanLeaveThePaneOut() {
        assertThat(assistant.enabled()).isFalse();
    }

    @Test
    void failsWithAnExplanationRatherThanAnEmptyAnswer() {
        assertThatThrownBy(() -> assistant.parseConversion("1 btc in usd", Set.of("bitcoin", "USD"))
                        .get(1, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOf(AiException.class)
                .hasMessageContaining("ANTHROPIC_API_KEY");
    }

    @Test
    void failsTheSameWayForASummary() {
        MarketSnapshot snapshot =
                new MarketSnapshot("BTC", "USD", new BigDecimal("42000"), null, List.of());

        assertThatThrownBy(() -> assistant.summariseMarket(snapshot).get(1, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOf(AiException.class);
    }

    @Test
    void isWhatTheFactoryBuildsWhenNoKeyIsConfigured() {
        AiAssistant built = ClaudeAiAssistant.create(config(Optional.empty()), Runnable::run);

        assertThat(built).isInstanceOf(NoOpAiAssistant.class);
        assertThat(built.enabled()).isFalse();
    }

    @Test
    void yieldsToTheRealAssistantOnceAKeyIsConfigured() {
        AiAssistant built = ClaudeAiAssistant.create(config(Optional.of("sk-ant-test")), Runnable::run);

        assertThat(built).isInstanceOf(ClaudeAiAssistant.class);
        assertThat(built.enabled()).isTrue();
    }
}
