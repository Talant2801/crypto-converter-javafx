package io.github.talant2801.cryptoconverter.config;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds an {@link AppConfig} by consulting, in order: environment variables,
 * a properties file outside the repository, then hard-coded defaults.
 *
 * <p>Environment first is what makes the application deployable without editing
 * files, and the properties file is what makes it comfortable to run locally.
 * The file lives at {@code ~/.cryptoconverter/config.properties} precisely so a
 * real key can never be committed; {@code config.properties.example} in the repo
 * documents the shape with placeholder values.
 *
 * <p>The environment lookup and the file location are constructor parameters so
 * tests can exercise the precedence rules without mutating the real process
 * environment or the developer's home directory.
 */
public final class ConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(ConfigLoader.class);

    /** Default location of the local override file, outside the repository. */
    public static final Path DEFAULT_PROPERTIES_PATH =
            Path.of(System.getProperty("user.home"), ".cryptoconverter", "config.properties");

    static final URI DEFAULT_BASE_URI = URI.create("https://api.coingecko.com/api/v3");
    static final Duration DEFAULT_HTTP_TIMEOUT = Duration.ofSeconds(10);
    static final Duration DEFAULT_RATE_CACHE_TTL = Duration.ofSeconds(60);
    static final Duration DEFAULT_COIN_LIST_CACHE_TTL = Duration.ofMinutes(30);
    static final int DEFAULT_MAX_RETRIES = 3;
    static final Duration DEFAULT_RETRY_BASE_DELAY = Duration.ofMillis(500);
    static final Path DEFAULT_DATABASE_PATH =
            Path.of(System.getProperty("user.home"), ".cryptoconverter", "history.db");

    private final Function<String, String> environment;
    private final Path propertiesPath;

    public ConfigLoader(Function<String, String> environment, Path propertiesPath) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.propertiesPath = Objects.requireNonNull(propertiesPath, "propertiesPath");
    }

    /** Loader bound to the real process environment and the user's home directory. */
    public static ConfigLoader systemLoader() {
        return new ConfigLoader(System::getenv, DEFAULT_PROPERTIES_PATH);
    }

    public AppConfig load() {
        Properties file = readProperties();

        Optional<String> apiKey = resolve(file, "ANTHROPIC_API_KEY", "anthropic.api.key")
                .map(String::trim)
                .filter(value -> !value.isEmpty());

        URI baseUri = resolve(file, "COINGECKO_BASE_URL", "coingecko.base.url")
                .map(ConfigLoader::parseUri)
                .orElse(DEFAULT_BASE_URI);

        Duration httpTimeout = resolve(file, "CRYPTOCONVERTER_HTTP_TIMEOUT_MS", "http.timeout.ms")
                .map(value -> Duration.ofMillis(parseLong(value, "http.timeout.ms")))
                .orElse(DEFAULT_HTTP_TIMEOUT);

        Duration rateTtl = resolve(file, "CRYPTOCONVERTER_RATE_CACHE_TTL_SECONDS", "cache.rate.ttl.seconds")
                .map(value -> Duration.ofSeconds(parseLong(value, "cache.rate.ttl.seconds")))
                .orElse(DEFAULT_RATE_CACHE_TTL);

        Duration coinListTtl = resolve(
                        file, "CRYPTOCONVERTER_COIN_LIST_CACHE_TTL_SECONDS", "cache.coinlist.ttl.seconds")
                .map(value -> Duration.ofSeconds(parseLong(value, "cache.coinlist.ttl.seconds")))
                .orElse(DEFAULT_COIN_LIST_CACHE_TTL);

        Path databasePath = resolve(file, "CRYPTOCONVERTER_DB_PATH", "db.path")
                .map(Path::of)
                .orElse(DEFAULT_DATABASE_PATH);

        int maxRetries = resolve(file, "CRYPTOCONVERTER_MAX_RETRIES", "http.max.retries")
                .map(value -> (int) parseLong(value, "http.max.retries"))
                .orElse(DEFAULT_MAX_RETRIES);

        Duration retryBaseDelay = resolve(file, "CRYPTOCONVERTER_RETRY_BASE_DELAY_MS", "http.retry.base.delay.ms")
                .map(value -> Duration.ofMillis(parseLong(value, "http.retry.base.delay.ms")))
                .orElse(DEFAULT_RETRY_BASE_DELAY);

        AppConfig config = new AppConfig(
                apiKey, baseUri, httpTimeout, rateTtl, coinListTtl, databasePath, maxRetries, retryBaseDelay);
        // AppConfig.toString redacts the key, so this line is safe to log.
        log.debug("Loaded configuration: {}", config);
        return config;
    }

    /** Environment wins over the file; absent means "fall through to the default". */
    private Optional<String> resolve(Properties file, String envVar, String propertyKey) {
        String fromEnv = environment.apply(envVar);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return Optional.of(fromEnv);
        }
        String fromFile = file.getProperty(propertyKey);
        if (fromFile != null && !fromFile.isBlank()) {
            return Optional.of(fromFile);
        }
        return Optional.empty();
    }

    /**
     * Reads the override file, treating "missing" and "unreadable" alike as
     * "no overrides". A developer without the file is the normal case, not an
     * error worth failing startup over.
     */
    private Properties readProperties() {
        Properties properties = new Properties();
        if (!Files.isReadable(propertiesPath)) {
            return properties;
        }
        try (Reader reader = Files.newBufferedReader(propertiesPath, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            log.warn("Could not read {}, continuing with environment and defaults: {}",
                    propertiesPath, e.getMessage());
        }
        return properties;
    }

    private static URI parseUri(String value) {
        try {
            // Trailing slashes would double up when paths are appended.
            return new URI(value.endsWith("/") ? value.substring(0, value.length() - 1) : value);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid CoinGecko base URL: " + value, e);
        }
    }

    private static long parseLong(String value, String key) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Expected a number for " + key + " but got: " + value, e);
        }
    }
}
