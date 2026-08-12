package io.github.talant2801.cryptoconverter.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The precedence rules, exercised without touching the real process
 * environment or the developer's home directory — both are constructor
 * parameters precisely so these tests can stay hermetic.
 */
class ConfigLoaderTest {

    @TempDir
    Path tempDir;

    private final Map<String, String> environment = new HashMap<>();

    private ConfigLoader loaderWith(String propertiesBody) throws IOException {
        Path file = tempDir.resolve("config.properties");
        if (propertiesBody != null) {
            Files.writeString(file, propertiesBody, StandardCharsets.UTF_8);
        }
        return new ConfigLoader(environment::get, file);
    }

    private ConfigLoader loaderWithNoFile() throws IOException {
        return loaderWith(null);
    }

    @Test
    void fallsBackToDefaultsWhenNothingIsConfigured() throws IOException {
        AppConfig config = loaderWithNoFile().load();

        assertThat(config.anthropicApiKey()).isEmpty();
        assertThat(config.aiEnabled()).isFalse();
        assertThat(config.coinGeckoBaseUri()).isEqualTo(ConfigLoader.DEFAULT_BASE_URI);
        assertThat(config.httpTimeout()).isEqualTo(ConfigLoader.DEFAULT_HTTP_TIMEOUT);
        assertThat(config.rateCacheTtl()).isEqualTo(ConfigLoader.DEFAULT_RATE_CACHE_TTL);
        assertThat(config.coinListCacheTtl()).isEqualTo(ConfigLoader.DEFAULT_COIN_LIST_CACHE_TTL);
        assertThat(config.maxRetries()).isEqualTo(ConfigLoader.DEFAULT_MAX_RETRIES);
        assertThat(config.retryBaseDelay()).isEqualTo(ConfigLoader.DEFAULT_RETRY_BASE_DELAY);
        assertThat(config.databasePath()).isEqualTo(ConfigLoader.DEFAULT_DATABASE_PATH);
    }

    @Test
    void readsEverySettingFromThePropertiesFile() throws IOException {
        ConfigLoader loader = loaderWith(
                """
                anthropic.api.key=file-key
                coingecko.base.url=https://proxy.internal/api/v3
                http.timeout.ms=2500
                cache.rate.ttl.seconds=15
                cache.coinlist.ttl.seconds=300
                db.path=/var/lib/cryptoconverter/history.db
                http.max.retries=5
                http.retry.base.delay.ms=250
                """);

        AppConfig config = loader.load();

        assertThat(config.anthropicApiKey()).contains("file-key");
        assertThat(config.coinGeckoBaseUri()).isEqualTo(URI.create("https://proxy.internal/api/v3"));
        assertThat(config.httpTimeout()).isEqualTo(Duration.ofMillis(2500));
        assertThat(config.rateCacheTtl()).isEqualTo(Duration.ofSeconds(15));
        assertThat(config.coinListCacheTtl()).isEqualTo(Duration.ofSeconds(300));
        assertThat(config.databasePath()).isEqualTo(Path.of("/var/lib/cryptoconverter/history.db"));
        assertThat(config.maxRetries()).isEqualTo(5);
        assertThat(config.retryBaseDelay()).isEqualTo(Duration.ofMillis(250));
    }

    @Test
    void environmentVariablesOverrideTheFile() throws IOException {
        environment.put("ANTHROPIC_API_KEY", "env-key");
        environment.put("CRYPTOCONVERTER_RATE_CACHE_TTL_SECONDS", "5");
        ConfigLoader loader = loaderWith(
                """
                anthropic.api.key=file-key
                cache.rate.ttl.seconds=15
                cache.coinlist.ttl.seconds=300
                """);

        AppConfig config = loader.load();

        assertThat(config.anthropicApiKey()).contains("env-key");
        assertThat(config.rateCacheTtl()).isEqualTo(Duration.ofSeconds(5));
        // Untouched by the environment, so the file still wins over the default.
        assertThat(config.coinListCacheTtl()).isEqualTo(Duration.ofSeconds(300));
    }

    @Test
    void aBlankEnvironmentVariableFallsThroughRatherThanBlankingTheSetting() throws IOException {
        environment.put("ANTHROPIC_API_KEY", "   ");
        ConfigLoader loader = loaderWith("anthropic.api.key=file-key\n");

        assertThat(loader.load().anthropicApiKey()).contains("file-key");
    }

    @Test
    void anApiKeyThatIsOnlyWhitespaceCountsAsAbsent() throws IOException {
        environment.put("ANTHROPIC_API_KEY", "\t");
        ConfigLoader loader = loaderWith("cache.rate.ttl.seconds=15\n");

        AppConfig config = loader.load();

        assertThat(config.anthropicApiKey()).isEmpty();
        assertThat(config.aiEnabled()).isFalse();
    }

    @Test
    void surroundingWhitespaceIsStrippedFromTheApiKey() throws IOException {
        environment.put("ANTHROPIC_API_KEY", "  sk-ant-abc123  ");

        assertThat(loaderWithNoFile().load().anthropicApiKey()).contains("sk-ant-abc123");
    }

    @Test
    void aTrailingSlashOnTheBaseUrlIsRemovedSoPathsDoNotDoubleUp() throws IOException {
        environment.put("COINGECKO_BASE_URL", "https://proxy.internal/api/v3/");

        assertThat(loaderWithNoFile().load().coinGeckoBaseUri())
                .isEqualTo(URI.create("https://proxy.internal/api/v3"));
    }

    @Test
    void anUnreadableFileIsTreatedAsNoOverridesRatherThanAStartupFailure() throws IOException {
        // The common case is a developer who never created the file at all.
        ConfigLoader loader = new ConfigLoader(environment::get, tempDir.resolve("does-not-exist.properties"));

        assertThat(loader.load().coinGeckoBaseUri()).isEqualTo(ConfigLoader.DEFAULT_BASE_URI);
    }

    @Test
    void aDirectoryWhereThePropertiesFileShouldBeIsIgnored() throws IOException {
        Path asDirectory = Files.createDirectory(tempDir.resolve("config.properties"));
        ConfigLoader loader = new ConfigLoader(environment::get, asDirectory);

        assertThat(loader.load().maxRetries()).isEqualTo(ConfigLoader.DEFAULT_MAX_RETRIES);
    }

    @Test
    void anUnparseableNumberNamesTheOffendingKey() throws IOException {
        environment.put("CRYPTOCONVERTER_HTTP_TIMEOUT_MS", "ten thousand");

        assertThatThrownBy(() -> loaderWithNoFile().load())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http.timeout.ms")
                .hasMessageContaining("ten thousand");
    }

    @Test
    void anUnparseableBaseUrlIsRejected() throws IOException {
        environment.put("COINGECKO_BASE_URL", "ht tp://broken");

        assertThatThrownBy(() -> loaderWithNoFile().load())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid CoinGecko base URL");
    }

    @Test
    void aNonPositiveCacheTtlIsRejectedByTheConfigItself() throws IOException {
        environment.put("CRYPTOCONVERTER_RATE_CACHE_TTL_SECONDS", "0");

        assertThatThrownBy(() -> loaderWithNoFile().load())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rateCacheTtl");
    }

    @Test
    void aNegativeRetryCountIsRejected() throws IOException {
        environment.put("CRYPTOCONVERTER_MAX_RETRIES", "-1");

        assertThatThrownBy(() -> loaderWithNoFile().load())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxRetries");
    }

    @Test
    void theSystemLoaderPointsAtTheUsersHomeDirectory() {
        assertThat(ConfigLoader.systemLoader()).isNotNull();
        // Compared as a value, not on disk: the file legitimately may not exist.
        assertThat(ConfigLoader.DEFAULT_PROPERTIES_PATH.toString())
                .isEqualTo(Path.of(System.getProperty("user.home"), ".cryptoconverter", "config.properties")
                        .toString());
        assertThat(ConfigLoader.DEFAULT_DATABASE_PATH.getFileName().toString()).isEqualTo("history.db");
    }

    /**
     * The single most important assertion in this class: {@link AppConfig} is
     * exactly the sort of object that ends up in a debug log, and the record's
     * generated {@code toString} would have inlined the key.
     */
    @Test
    void toStringNeverRevealsTheApiKey() throws IOException {
        environment.put("ANTHROPIC_API_KEY", "sk-ant-supersecret");

        String rendered = loaderWithNoFile().load().toString();

        assertThat(rendered).doesNotContain("sk-ant-supersecret").contains("<set>");
        assertThat(new AppConfig(
                        Optional.empty(),
                        ConfigLoader.DEFAULT_BASE_URI,
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(60),
                        Duration.ofMinutes(30),
                        Path.of("/tmp/history.db"),
                        3,
                        Duration.ofMillis(500))
                        .toString())
                .contains("<unset>");
    }

    @Test
    void aConfiguredKeyEnablesTheAiLayer() throws IOException {
        environment.put("ANTHROPIC_API_KEY", "sk-ant-abc123");

        assertThat(loaderWithNoFile().load().aiEnabled()).isTrue();
    }
}
