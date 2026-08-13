package io.github.talant2801.cryptoconverter.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A time-to-live cache in front of an asynchronous loader, with in-flight
 * coalescing and a fallback to expired data when the loader fails.
 *
 * <p>Three behaviours, each earning its place against CoinGecko's free tier,
 * which allows something like ten to thirty requests a minute:
 *
 * <ul>
 *   <li><b>TTL.</b> A value younger than {@code ttl} is served without a call.
 *   <li><b>Coalescing.</b> Concurrent misses on the same key produce exactly one
 *       call. Without this, a chart, a converter and a history row all asking
 *       for bitcoin at startup would spend three requests on one answer.
 *   <li><b>Stale fallback.</b> If the loader fails and an expired value is still
 *       held, the expired value is returned instead of the failure. A converter
 *       showing a minute-old price with a "stale" marker is more useful than one
 *       showing an error dialog, and {@code fetchedAt} on the value itself lets
 *       the UI say how old it is.
 * </ul>
 *
 * <p>The waiting is done with futures rather than locks: no caller thread is
 * ever parked, which matters because the caller is usually the JavaFX
 * application thread.
 *
 * <p>Package-private — it is an implementation detail of {@link CachedRateService},
 * not a general-purpose cache, and it deliberately has no eviction policy
 * because the key space here is bounded by the coin list.
 *
 * @param <V> the cached value type
 */
final class TtlCache<V> {

    private static final Logger log = LoggerFactory.getLogger(TtlCache.class);

    private final ConcurrentHashMap<String, Entry<V>> entries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<V>> inFlight = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final Clock clock;
    private final String name;

    /**
     * @param name used only in log lines, to tell one cache's misses from another's
     * @param ttl how long a loaded value counts as fresh
     */
    TtlCache(String name, Duration ttl, Clock clock) {
        this.name = Objects.requireNonNull(name, "name");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Returns the cached value for {@code key}, loading it if it is missing or
     * expired.
     *
     * @param loader invoked at most once per key per in-flight window
     * @return a future independent of the shared one, so a caller cancelling its
     *     own request cannot cancel the load for everybody else
     */
    CompletableFuture<V> get(String key, Supplier<CompletableFuture<V>> loader) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(loader, "loader");

        Entry<V> fresh = entries.get(key);
        if (fresh != null && !fresh.isExpired(clock.instant(), ttl)) {
            return CompletableFuture.completedFuture(fresh.value());
        }

        // A placeholder is registered before the load starts. Publishing the
        // promise rather than the loader's own future is what makes coalescing
        // safe: the completion callback removes the key from the map, and doing
        // that inside a computeIfAbsent mapping function would be a recursive
        // update on the same key whenever the loader completes synchronously.
        CompletableFuture<V> promise = new CompletableFuture<>();
        CompletableFuture<V> running = inFlight.putIfAbsent(key, promise);
        if (running != null) {
            log.trace("{}: joining the in-flight load for {}", name, key);
            return running.copy();
        }

        start(key, loader, promise);
        return promise.copy();
    }

    /** The value held for {@code key} regardless of age, for stale indicators. */
    Optional<Entry<V>> peek(String key) {
        return Optional.ofNullable(entries.get(key));
    }

    private void start(String key, Supplier<CompletableFuture<V>> loader, CompletableFuture<V> promise) {
        CompletableFuture<V> load;
        try {
            load = loader.get();
        } catch (RuntimeException immediateFailure) {
            // A loader that throws rather than returning a failed future would
            // otherwise leave the promise — and everyone joined to it — pending
            // forever.
            load = CompletableFuture.failedFuture(immediateFailure);
        }

        load.whenComplete((value, error) -> {
            // Deregister before completing, so a caller woken by the completion
            // cannot join a future that has already finished.
            inFlight.remove(key, promise);
            if (error == null) {
                entries.put(key, new Entry<>(value, clock.instant()));
                promise.complete(value);
                return;
            }
            Entry<V> stale = entries.get(key);
            if (stale != null) {
                log.warn("{}: load for {} failed ({}), serving data from {}",
                        name, key, unwrap(error).toString(), stale.storedAt());
                promise.complete(stale.value());
            } else {
                promise.completeExceptionally(unwrap(error));
            }
        });
    }

    /** Strips the {@link CompletionException} wrapper so callers see the real cause. */
    private static Throwable unwrap(Throwable error) {
        return error instanceof CompletionException wrapper && wrapper.getCause() != null
                ? wrapper.getCause()
                : error;
    }

    /**
     * A cached value and the moment it was stored.
     *
     * @param <V> the cached value type
     */
    record Entry<V>(V value, Instant storedAt) {

        Entry {
            Objects.requireNonNull(storedAt, "storedAt");
        }

        boolean isExpired(Instant now, Duration ttl) {
            return storedAt.plus(ttl).isBefore(now);
        }
    }
}
