package io.github.talant2801.cryptoconverter;

import io.github.talant2801.cryptoconverter.client.CoinGeckoClient;
import io.github.talant2801.cryptoconverter.client.HttpCoinGeckoClient;
import io.github.talant2801.cryptoconverter.config.AppConfig;
import io.github.talant2801.cryptoconverter.config.ConfigLoader;
import io.github.talant2801.cryptoconverter.persistence.Database;
import io.github.talant2801.cryptoconverter.persistence.SqliteConversionHistoryDao;
import io.github.talant2801.cryptoconverter.persistence.SqliteFavouritesDao;
import io.github.talant2801.cryptoconverter.service.CachedRateService;
import io.github.talant2801.cryptoconverter.service.ConversionService;
import io.github.talant2801.cryptoconverter.service.HistoryService;
import io.github.talant2801.cryptoconverter.service.RateService;
import io.github.talant2801.cryptoconverter.service.ai.AiAssistant;
import io.github.talant2801.cryptoconverter.service.ai.ClaudeAiAssistant;
import io.github.talant2801.cryptoconverter.ui.AiPane;
import io.github.talant2801.cryptoconverter.ui.ChartPane;
import io.github.talant2801.cryptoconverter.ui.ConverterPane;
import io.github.talant2801.cryptoconverter.ui.CurrencyCatalog;
import io.github.talant2801.cryptoconverter.ui.HistoryPane;
import io.github.talant2801.cryptoconverter.ui.MainView;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Composition root. Every collaborator is built and wired here, so the rest of
 * the code can take its dependencies through the constructor and stay unaware
 * of how they were assembled.
 *
 * <p>This is the one place allowed to know about every layer at once. It is
 * also the reason no dependency-injection framework is needed: the graph is a
 * few dozen lines of ordinary Java, readable top to bottom, and a
 * misconfiguration is a compile error rather than a runtime surprise.
 */
public final class AppContext implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AppContext.class);

    /**
     * Enough threads for the handful of concurrent requests a converter makes —
     * two rate legs, a chart series, a history write — without a pool that
     * could out-pace CoinGecko's rate limit on its own.
     */
    private static final int IO_THREADS = 4;

    private final AppConfig config;
    private final ExecutorService ioExecutor;
    private final Database database;
    private final RateService rateService;
    private final ConversionService conversionService;
    private final HistoryService historyService;
    private final AiAssistant aiAssistant;
    private final Clock clock;

    private AppContext(
            AppConfig config,
            ExecutorService ioExecutor,
            Database database,
            RateService rateService,
            ConversionService conversionService,
            HistoryService historyService,
            AiAssistant aiAssistant,
            Clock clock) {

        this.config = config;
        this.ioExecutor = ioExecutor;
        this.database = database;
        this.rateService = rateService;
        this.conversionService = conversionService;
        this.historyService = historyService;
        this.aiAssistant = aiAssistant;
        this.clock = clock;
    }

    /** Builds the whole graph from configuration resolved at startup. */
    public static AppContext create() {
        AppConfig config = ConfigLoader.systemLoader().load();
        Clock clock = Clock.systemUTC();

        // Daemon threads so a lingering HTTP call cannot keep the JVM alive
        // after the window closes.
        ExecutorService executor = Executors.newFixedThreadPool(IO_THREADS, runnable -> {
            Thread thread = new Thread(runnable, "crypto-io");
            thread.setDaemon(true);
            return thread;
        });

        CoinGeckoClient client = HttpCoinGeckoClient.create(config, executor);
        RateService rates = CachedRateService.create(client, config, clock);

        Database database = Database.open(config.databasePath());
        HistoryService history = new HistoryService(
                new SqliteConversionHistoryDao(database),
                new SqliteFavouritesDao(database),
                executor,
                clock);

        ConversionService conversions = new ConversionService(rates, history, clock);

        // The one place that decides whether the AI layer exists. Everything
        // downstream is handed an assistant and never asks where it came from.
        AiAssistant assistant = ClaudeAiAssistant.create(config, executor);
        log.info("AI features are {}", assistant.enabled() ? "enabled" : "disabled (no API key configured)");

        return new AppContext(config, executor, database, rates, conversions, history, assistant, clock);
    }

    /** Builds the window's contents. Called on the JavaFX application thread. */
    public MainView createMainView() {
        CurrencyCatalog catalog = new CurrencyCatalog(rateService);
        ConverterPane converter =
                new ConverterPane(conversionService, catalog, config.rateCacheTtl(), clock);
        Optional<AiPane> ai = AiPane.isAvailable(aiAssistant)
                ? Optional.of(new AiPane(aiAssistant, conversionService, rateService, catalog))
                : Optional.empty();
        return new MainView(converter, new ChartPane(rateService), new HistoryPane(historyService), ai);
    }

    public AppConfig config() {
        return config;
    }

    /**
     * Shuts down in the reverse order of construction: stop making work, then
     * close what the work was using.
     */
    @Override
    public void close() throws InterruptedException {
        ioExecutor.shutdown();
        if (!ioExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
            log.debug("I/O executor did not finish in time; interrupting the remaining tasks");
            ioExecutor.shutdownNow();
        }
        database.close();
    }
}
