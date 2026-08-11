package io.github.talant2801.cryptoconverter;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Composition root. Every collaborator is built and wired here, so the rest of
 * the code can take its dependencies through the constructor and stay unaware
 * of how they were assembled.
 *
 * <p>This is the one place allowed to know about every layer at once.
 */
public final class AppContext implements AutoCloseable {

    private final ExecutorService ioExecutor;

    private AppContext(ExecutorService ioExecutor) {
        this.ioExecutor = ioExecutor;
    }

    public static AppContext create() {
        // Daemon threads so a lingering HTTP call cannot keep the JVM alive
        // after the window closes.
        ExecutorService executor = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "crypto-io");
            thread.setDaemon(true);
            return thread;
        });
        return new AppContext(executor);
    }

    public ExecutorService ioExecutor() {
        return ioExecutor;
    }

    @Override
    public void close() throws InterruptedException {
        ioExecutor.shutdown();
        if (!ioExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
            ioExecutor.shutdownNow();
        }
    }
}
