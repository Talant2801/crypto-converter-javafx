package io.github.talant2801.cryptoconverter.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A clock the test moves by hand.
 *
 * <p>Every expiry rule in this package is expressed against an injected
 * {@link Clock}, so a TTL test can jump forward an hour instead of sleeping
 * through one. Nothing here waits on wall-clock time.
 */
final class TickingClock extends Clock {

    private Instant now;

    TickingClock(Instant start) {
        this.now = start;
    }

    static TickingClock at(String isoInstant) {
        return new TickingClock(Instant.parse(isoInstant));
    }

    void advance(Duration amount) {
        now = now.plus(amount);
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
