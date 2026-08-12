package io.github.talant2801.cryptoconverter.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.function.DoubleSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The backoff curve, pinned. The jitter source is supplied by each test so the
 * randomness never leaks into an assertion.
 */
class RetryPolicyTest {

    /** Full jitter, at its maximum, so the assertions read as the un-jittered curve. */
    private static final DoubleSupplier NO_JITTER = () -> 1.0;

    private final RetryPolicy policy = new RetryPolicy(3, Duration.ofMillis(500), Duration.ofSeconds(20));

    @ParameterizedTest(name = "attempt {0} waits {1} ms before retrying")
    @CsvSource({"0, 500", "1, 1000", "2, 2000", "3, 4000", "4, 8000"})
    void delayDoublesWithEachAttempt(int attempt, long expectedMillis) {
        assertThat(policy.delayBefore(attempt, NO_JITTER)).isEqualTo(Duration.ofMillis(expectedMillis));
    }

    @Test
    void delayStopsGrowingAtTheCeiling() {
        // 500ms doubled six times is 32s, past the 20s cap.
        assertThat(policy.delayBefore(6, NO_JITTER)).isEqualTo(Duration.ofSeconds(20));
        assertThat(policy.delayBefore(20, NO_JITTER)).isEqualTo(Duration.ofSeconds(20));
    }

    @Test
    void jitterScalesTheDelayDownButNeverUp() {
        assertThat(policy.delayBefore(2, () -> 0.0)).isZero();
        assertThat(policy.delayBefore(2, () -> 0.25)).isEqualTo(Duration.ofMillis(500));
        assertThat(policy.delayBefore(2, () -> 1.0)).isEqualTo(Duration.ofMillis(2000));
    }

    @Test
    void jitterKeepsRetriesFromLandingTogether() {
        // Two callers failing in the same window must not wake at the same instant.
        Duration first = policy.delayBefore(3, () -> 0.1);
        Duration second = policy.delayBefore(3, () -> 0.9);

        assertThat(first).isNotEqualTo(second);
        assertThat(first).isLessThan(second);
        assertThat(second).isLessThanOrEqualTo(policy.maxDelay());
    }

    @Test
    void anExtremeAttemptCountCannotOverflowIntoANegativeDelay() {
        // The shift is clamped and the multiply saturates, so the cap still wins.
        assertThat(policy.delayBefore(Integer.MAX_VALUE, NO_JITTER)).isEqualTo(policy.maxDelay());
        assertThat(policy.delayBefore(64, NO_JITTER)).isEqualTo(policy.maxDelay());
    }

    @Test
    void aZeroBaseDelayRetriesImmediately() {
        RetryPolicy immediate = new RetryPolicy(3, Duration.ZERO, Duration.ZERO);

        assertThat(immediate.delayBefore(0, NO_JITTER)).isZero();
        assertThat(immediate.delayBefore(5, NO_JITTER)).isZero();
    }

    @Test
    void theConvenienceFactoryKeepsTheDelayWithinTwentySeconds() {
        RetryPolicy created = RetryPolicy.of(3, Duration.ofMillis(500));

        assertThat(created.maxRetries()).isEqualTo(3);
        assertThat(created.baseDelay()).isEqualTo(Duration.ofMillis(500));
        assertThat(created.maxDelay()).isEqualTo(Duration.ofSeconds(20));
    }

    @Test
    void theDefaultJitterSourceStaysWithinTheCurve() {
        // Exercises the ThreadLocalRandom overload without asserting a value.
        for (int i = 0; i < 50; i++) {
            Duration delay = policy.delayBefore(2);
            assertThat(delay).isBetween(Duration.ZERO, Duration.ofMillis(2000));
        }
    }

    @Test
    void rejectsAnIncoherentPolicy() {
        assertThatThrownBy(() -> new RetryPolicy(-1, Duration.ofMillis(500), Duration.ofSeconds(20)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxRetries");
        assertThatThrownBy(() -> new RetryPolicy(3, Duration.ofMillis(-1), Duration.ofSeconds(20)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baseDelay");
        assertThatThrownBy(() -> new RetryPolicy(3, Duration.ofSeconds(30), Duration.ofSeconds(20)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("below baseDelay");
        assertThatThrownBy(() -> new RetryPolicy(3, null, Duration.ofSeconds(20)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsANegativeAttemptNumber() {
        assertThatThrownBy(() -> policy.delayBefore(-1, NO_JITTER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attempt");
    }
}
