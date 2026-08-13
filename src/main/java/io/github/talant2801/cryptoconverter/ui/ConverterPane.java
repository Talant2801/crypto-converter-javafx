package io.github.talant2801.cryptoconverter.ui;

import io.github.talant2801.cryptoconverter.domain.ConversionResult;
import io.github.talant2801.cryptoconverter.domain.Money;
import io.github.talant2801.cryptoconverter.service.ConversionService;
import io.github.talant2801.cryptoconverter.ui.util.UiUtils;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

/**
 * The converter: an amount, two currencies, and the answer.
 *
 * <p>Three rules shape everything here.
 *
 * <ul>
 *   <li><b>Nothing blocks.</b> Conversions are futures; results come back
 *       through {@link UiUtils#onFxThread}, which is the only path that touches
 *       a control.
 *   <li><b>Typing does not spam the API.</b> Recalculation is debounced, so a
 *       user typing "0.35" spends one request rather than four.
 *   <li><b>The newest answer wins.</b> Each recalculation carries a sequence
 *       number and a late reply from an earlier one is dropped — otherwise a
 *       slow request for "0.3" could overwrite the result for "0.35".
 * </ul>
 *
 * <p>Building the currency list and formatting values live in
 * {@link CurrencyCatalog} and {@link UiUtils}, leaving this class to do one
 * thing: wire controls to the conversion service.
 */
public final class ConverterPane extends VBox {

    private final ConversionService conversions;
    private final CurrencyCatalog catalog;
    private final Duration rateTtl;
    private final Clock clock;

    private final TextField amountField = new TextField("1");
    private final ComboBox<CurrencyOption> fromBox = new ComboBox<>();
    private final ComboBox<CurrencyOption> toBox = new ComboBox<>();
    private final Label resultLabel = new Label("—");
    private final Label rateLabel = new Label();
    private final Label changeLabel = new Label();
    private final Label staleLabel = new Label();
    private final ProgressIndicator progress = new ProgressIndicator();
    private final Runnable requestPreview;

    /** Notified with the pair whenever the selection changes, so the chart can follow. */
    private Consumer<CurrencyPairSelection> onPairChanged = selection -> { };

    /** Notified after a conversion is written to history, so the table can refresh. */
    private Runnable onConversionSaved = () -> { };

    /** Guards against an older, slower response landing on top of a newer one. */
    private long latestRequest;

    public ConverterPane(
            ConversionService conversions, CurrencyCatalog catalog, Duration rateTtl, Clock clock) {

        this.conversions = Objects.requireNonNull(conversions, "conversions");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.rateTtl = Objects.requireNonNull(rateTtl, "rateTtl");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.requestPreview = UiUtils.debounce(UiUtils.DEBOUNCE, this::preview);

        getStyleClass().add("converter-pane");
        getChildren().addAll(header(), amountRow(), currencyRow(), resultBlock());
        wireEvents();
        loadCurrencies();
    }

    /** Sets the listener told about the current pair, for the chart to follow. */
    public void setOnPairChanged(Consumer<CurrencyPairSelection> listener) {
        this.onPairChanged = Objects.requireNonNull(listener, "listener");
    }

    /** Sets the listener told that history has a new row. */
    public void setOnConversionSaved(Runnable listener) {
        this.onConversionSaved = Objects.requireNonNull(listener, "listener");
    }

    private Region header() {
        Label title = new Label("Convert");
        title.getStyleClass().add("section-title");
        return title;
    }

    private Region amountRow() {
        amountField.getStyleClass().add("amount-field");
        // Digits and at most one decimal point: a formatter rejects the bad
        // keystroke outright, which is friendlier than validating afterwards.
        amountField.setTextFormatter(new TextFormatter<>(change ->
                change.getControlNewText().matches("\\d*\\.?\\d*") ? change : null));
        HBox.setHgrow(amountField, Priority.ALWAYS);

        Button convert = new Button("Convert");
        convert.getStyleClass().add("button-primary");
        convert.setOnAction(event -> commit());
        convert.setDefaultButton(true);

        HBox row = new HBox(amountField, convert);
        row.getStyleClass().add("field-row");
        return row;
    }

    private Region currencyRow() {
        fromBox.setConverter(labels());
        toBox.setConverter(labels());
        HBox.setHgrow(fromBox, Priority.ALWAYS);
        HBox.setHgrow(toBox, Priority.ALWAYS);
        fromBox.setMaxWidth(Double.MAX_VALUE);
        toBox.setMaxWidth(Double.MAX_VALUE);

        Button swap = new Button("⇄");
        swap.getStyleClass().add("swap-button");
        swap.setOnAction(event -> swap());

        HBox row = new HBox(fromBox, swap, toBox);
        row.getStyleClass().add("field-row");
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private Region resultBlock() {
        resultLabel.getStyleClass().add("result-amount");
        rateLabel.getStyleClass().add("label-muted");
        staleLabel.getStyleClass().add("stale-badge");
        staleLabel.setVisible(false);
        progress.getStyleClass().add("inline-progress");
        progress.setVisible(false);

        HBox rateLine = new HBox(rateLabel, changeLabel, staleLabel, progress);
        rateLine.getStyleClass().add("rate-line");
        rateLine.setAlignment(Pos.CENTER_LEFT);

        VBox block = new VBox(resultLabel, rateLine);
        block.getStyleClass().add("result-block");
        return block;
    }

    private void wireEvents() {
        amountField.textProperty().addListener(this::onInputChanged);
        fromBox.valueProperty().addListener(this::onPairEdited);
        toBox.valueProperty().addListener(this::onPairEdited);
        amountField.setOnAction(event -> commit());
    }

    private void onInputChanged(ObservableValue<? extends String> source, String old, String current) {
        requestPreview.run();
    }

    private void onPairEdited(ObservableValue<? extends CurrencyOption> source, CurrencyOption old, CurrencyOption current) {
        announcePair();
        // A selection is a deliberate act rather than a keystroke, so it does
        // not wait out the typing debounce.
        preview();
    }

    private void loadCurrencies() {
        UiUtils.onFxThread(
                catalog.load(),
                options -> {
                    fromBox.setItems(FXCollections.observableArrayList(options));
                    toBox.setItems(FXCollections.observableArrayList(options));
                    fromBox.setValue(CurrencyCatalog.defaultFrom(options));
                    toBox.setValue(CurrencyCatalog.defaultTo(options));
                },
                error -> UiUtils.showError("Could not load currencies", error));
    }

    private void swap() {
        CurrencyOption from = fromBox.getValue();
        fromBox.setValue(toBox.getValue());
        toBox.setValue(from);
    }

    /** Recalculates without touching history — what typing and selecting do. */
    private void preview() {
        start(amount -> conversions.convert(amount, toBox.getValue().code()), false);
    }

    /** Recalculates and saves — what the Convert button and Enter do. */
    private void commit() {
        start(amount -> conversions.convertAndSave(amount, toBox.getValue().code()), true);
    }

    /**
     * Runs one conversion and paints its outcome, ignoring the reply if a newer
     * request has been made since.
     *
     * @param work the conversion to run, either previewing or committing
     * @param saved whether the listener should be told history changed
     */
    private void start(Function<Money, CompletableFuture<ConversionResult>> work, boolean saved) {

        Optional<Money> amount = enteredAmount();
        if (amount.isEmpty() || fromBox.getValue() == null || toBox.getValue() == null) {
            clearResult();
            return;
        }
        long request = ++latestRequest;
        progress.setVisible(true);

        UiUtils.onFxThread(
                work.apply(amount.get()),
                result -> {
                    if (request == latestRequest) {
                        show(result);
                        if (saved) {
                            onConversionSaved.run();
                        }
                    }
                },
                error -> {
                    if (request == latestRequest) {
                        progress.setVisible(false);
                        clearResult();
                        UiUtils.showError("Conversion failed", error);
                    }
                });
    }

    private Optional<Money> enteredAmount() {
        String text = amountField.getText();
        if (text == null || text.isBlank() || ".".equals(text) || fromBox.getValue() == null) {
            return Optional.empty();
        }
        return Optional.of(new Money(new BigDecimal(text), fromBox.getValue().code()));
    }

    private void show(ConversionResult result) {
        progress.setVisible(false);
        resultLabel.setText(UiUtils.formatAmount(result.to()) + " " + toBox.getValue().symbol());
        rateLabel.setText("1 %s = %s %s".formatted(
                fromBox.getValue().symbol(),
                UiUtils.formatRate(result.rate().rate()),
                toBox.getValue().symbol()));
        showChange(result);
        showStaleness(result);
    }

    /** Colour is carried by a style class, never by an inline style. */
    private void showChange(ConversionResult result) {
        BigDecimal change = result.rate().change24h();
        changeLabel.getStyleClass().removeAll("change-up", "change-down");
        changeLabel.setText(UiUtils.formatChange(change));
        if (change != null) {
            changeLabel.getStyleClass().add(change.signum() >= 0 ? "change-up" : "change-down");
        }
    }

    /**
     * Marks a price the cache served after a failed refresh.
     *
     * <p>The rate carries when it was fetched, so the pane can say how old the
     * figure on screen is instead of pretending it is live.
     */
    private void showStaleness(ConversionResult result) {
        boolean stale = result.rate().isStaleAt(clock.instant(), rateTtl);
        staleLabel.setVisible(stale);
        if (stale) {
            staleLabel.setText("stale — last updated " + UiUtils.formatTime(result.rate().fetchedAt()));
        }
    }

    private void clearResult() {
        progress.setVisible(false);
        resultLabel.setText("—");
        rateLabel.setText("");
        changeLabel.setText("");
        staleLabel.setVisible(false);
    }

    private void announcePair() {
        CurrencyOption from = fromBox.getValue();
        CurrencyOption to = toBox.getValue();
        if (from != null && to != null) {
            onPairChanged.accept(new CurrencyPairSelection(from, to));
        }
    }

    private static StringConverter<CurrencyOption> labels() {
        return new StringConverter<>() {

            @Override
            public String toString(CurrencyOption option) {
                return option == null ? "" : option.label();
            }

            @Override
            public CurrencyOption fromString(String label) {
                // The selectors are not editable, so nothing ever needs parsing
                // back from a label into an option.
                throw new UnsupportedOperationException("Currency selectors are read-only");
            }
        };
    }

    /**
     * The pair currently selected, as handed to whoever is following along.
     *
     * @param from the currency being converted out of
     * @param to the currency being converted into
     */
    public record CurrencyPairSelection(CurrencyOption from, CurrencyOption to) {

        public CurrencyPairSelection {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
        }

        /** The coin of the pair, when exactly one side is a coin. */
        public Optional<CurrencyOption> coin() {
            if (from.crypto() == to.crypto()) {
                return Optional.empty();
            }
            return Optional.of(from.crypto() ? from : to);
        }

        /** The fiat of the pair, when exactly one side is fiat. */
        public Optional<CurrencyOption> fiat() {
            if (from.crypto() == to.crypto()) {
                return Optional.empty();
            }
            return Optional.of(from.crypto() ? to : from);
        }
    }
}
