package io.github.talant2801.cryptoconverter.ui.util;

import io.github.talant2801.cryptoconverter.client.ApiException;
import io.github.talant2801.cryptoconverter.domain.Money;
import io.github.talant2801.cryptoconverter.persistence.PersistenceException;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.util.Duration;

/**
 * The small things every pane needs: formatting, debouncing, turning a future
 * into a UI update, and turning a failure into a sentence a person can read.
 *
 * <p>Collected in one place because each of them is a decision that has to be
 * made identically everywhere. Two panes formatting an amount differently, or
 * one of them touching a control from a background thread, are the kind of bug
 * that only shows up in front of an audience.
 */
public final class UiUtils {

    /** How long typing has to pause before a conversion is recalculated. */
    public static final Duration DEBOUNCE = Duration.millis(400);

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private UiUtils() {
    }

    /**
     * Delivers a future's outcome on the JavaFX application thread.
     *
     * <p>Every network and database call in the UI goes through here. Without a
     * single funnel, "this callback happens to run on the HTTP thread" is a
     * detail each call site would have to remember, and the one that forgets
     * corrupts the scene graph in a way that surfaces much later.
     */
    public static <T> void onFxThread(
            CompletableFuture<T> future, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {

        future.whenComplete((value, error) -> Platform.runLater(() -> {
            if (error != null) {
                onFailure.accept(unwrap(error));
            } else {
                onSuccess.accept(value);
            }
        }));
    }

    /**
     * Wraps an action so that rapid calls collapse into one, run {@code delay}
     * after the last of them.
     *
     * <p>Backed by {@link PauseTransition} rather than a timer thread: it fires
     * on the JavaFX thread, so the debounced action can touch controls directly,
     * and it is cancelled with the scene rather than outliving it.
     *
     * @return a trigger to call on every keystroke
     */
    public static Runnable debounce(Duration delay, Runnable action) {
        PauseTransition pause = new PauseTransition(delay);
        pause.setOnFinished(event -> action.run());
        return pause::playFromStart;
    }

    /** An amount with thousands separators, at the scale its currency calls for. */
    public static String formatAmount(Money money) {
        return format(money.amount(), Money.scaleFor(money.currencyCode()));
    }

    /**
     * A rate with enough decimals to be meaningful at either end of the range.
     *
     * <p>A coin priced in fiat needs two; a fiat unit priced in bitcoin needs
     * eight and still looks like zero with fewer. Choosing on the magnitude of
     * the number avoids showing "0.00" for a real price.
     */
    public static String formatRate(BigDecimal rate) {
        return format(rate, rate.abs().compareTo(BigDecimal.ONE) < 0 ? 8 : 2);
    }

    /** A signed percentage, as shown beside the rate. */
    public static String formatChange(BigDecimal change24h) {
        if (change24h == null) {
            return "";
        }
        String sign = change24h.signum() >= 0 ? "+" : "";
        return sign + format(change24h, 2) + "%";
    }

    /** The local wall-clock time of an instant, for the stale-data marker. */
    public static String formatTime(Instant instant) {
        return TIME.format(instant.atZone(ZoneId.systemDefault()));
    }

    /**
     * A message for the user, chosen from the failure's type rather than its
     * text.
     *
     * <p>Switching over the sealed {@link ApiException} hierarchy means the
     * compiler points at this method when a new failure mode is added, instead
     * of the user meeting a raw stack trace.
     */
    public static String messageFor(Throwable failure) {
        Throwable cause = unwrap(failure);
        if (cause instanceof ApiException apiFailure) {
            return switch (apiFailure) {
                case ApiException.RateLimited ignored ->
                        "CoinGecko is rate limiting us. Prices will refresh in a moment.";
                case ApiException.NotFound ignored ->
                        "No price is published for that pair.";
                case ApiException.Transport ignored ->
                        "Could not reach CoinGecko. Check your connection and try again.";
                case ApiException.Malformed ignored ->
                        "CoinGecko sent something this version cannot read.";
            };
        }
        if (cause instanceof PersistenceException) {
            return "Could not read or write the local history database.";
        }
        if (cause instanceof IllegalArgumentException) {
            return cause.getMessage();
        }
        return "Something went wrong: " + cause.getClass().getSimpleName();
    }

    /** An error dialog carrying {@link #messageFor} rather than a stack trace. */
    public static void showError(String title, Throwable failure) {
        Alert alert = new Alert(Alert.AlertType.ERROR, messageFor(failure));
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.showAndWait();
    }

    /** A yes/no dialog, used before anything irreversible. */
    public static boolean confirm(String title, String question) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, question);
        alert.setTitle(title);
        alert.setHeaderText(title);
        return alert.showAndWait().filter(button -> button.getButtonData().isDefaultButton()).isPresent();
    }

    private static String format(BigDecimal value, int decimals) {
        DecimalFormat format = new DecimalFormat();
        format.setGroupingUsed(true);
        format.setMinimumFractionDigits(Math.min(decimals, 2));
        format.setMaximumFractionDigits(decimals);
        return format.format(value);
    }

    /** Strips the {@link CompletionException} wrapper futures add. */
    private static Throwable unwrap(Throwable error) {
        return error instanceof CompletionException wrapper && wrapper.getCause() != null
                ? wrapper.getCause()
                : error;
    }
}
