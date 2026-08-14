package io.github.talant2801.cryptoconverter.ui;

import io.github.talant2801.cryptoconverter.domain.ConversionResult;
import io.github.talant2801.cryptoconverter.domain.ExchangeRate;
import io.github.talant2801.cryptoconverter.domain.PricePoint;
import io.github.talant2801.cryptoconverter.service.ConversionService;
import io.github.talant2801.cryptoconverter.service.RateService;
import io.github.talant2801.cryptoconverter.service.ai.AiAssistant;
import io.github.talant2801.cryptoconverter.service.ai.ConversionIntent;
import io.github.talant2801.cryptoconverter.service.ai.MarketSnapshot;
import io.github.talant2801.cryptoconverter.ui.ConverterPane.CurrencyPairSelection;
import io.github.talant2801.cryptoconverter.ui.util.UiUtils;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * The two AI features: asking for a conversion in words, and asking what the
 * price has been doing.
 *
 * <p>The pane exists only when an API key is configured — {@link #isAvailable}
 * decides that once, and the composition root simply does not add it otherwise.
 * That is what "the application must fully function with the AI layer disabled"
 * means in practice: not a disabled button, but an absent pane and no code path
 * that can call a service which is not there.
 *
 * <p>The model parses; it does not calculate. A natural-language question is
 * turned into an amount and two currency codes, and those go through the same
 * {@link ConversionService} the converter above uses. The number on screen comes
 * from the same arithmetic either way.
 */
public final class AiPane extends VBox {

    /** How many days of history the summary is written from. */
    private static final int SUMMARY_DAYS = 7;

    private final AiAssistant assistant;
    private final ConversionService conversions;
    private final RateService rates;
    private final CurrencyCatalog catalog;

    private final TextField questionField = new TextField();
    private final Button askButton = new Button("Ask");
    private final Button summariseButton = new Button("Summarise market");
    private final TextArea answerArea = new TextArea();
    private final ProgressIndicator progress = new ProgressIndicator();

    private List<String> knownCurrencies = List.of();
    private CurrencyPairSelection selection;

    /** Notified after a question writes a conversion to history. */
    private Runnable onConversionSaved = () -> { };

    public AiPane(
            AiAssistant assistant, ConversionService conversions, RateService rates, CurrencyCatalog catalog) {

        this.assistant = Objects.requireNonNull(assistant, "assistant");
        this.conversions = Objects.requireNonNull(conversions, "conversions");
        this.rates = Objects.requireNonNull(rates, "rates");
        this.catalog = Objects.requireNonNull(catalog, "catalog");

        getStyleClass().add("ai-pane");
        getChildren().addAll(header(), questionRow(), answerArea, disclaimer());
        wireEvents();
        loadKnownCurrencies();
    }

    /** True when the pane is worth building at all. */
    public static boolean isAvailable(AiAssistant assistant) {
        return assistant.enabled();
    }

    /** Sets the listener told that history has a new row. */
    public void setOnConversionSaved(Runnable listener) {
        this.onConversionSaved = Objects.requireNonNull(listener, "listener");
    }

    /** Follows the converter's pair, which is what a summary is written about. */
    public void show(CurrencyPairSelection pair) {
        selection = pair;
        summariseButton.setDisable(pair.coin().isEmpty() || pair.fiat().isEmpty());
    }

    private Region header() {
        Label title = new Label("Ask in plain language");
        title.getStyleClass().add("section-title");
        return title;
    }

    private Region questionRow() {
        questionField.setPromptText("how much is 0.35 BTC in Polish zloty");
        HBox.setHgrow(questionField, Priority.ALWAYS);

        askButton.getStyleClass().add("button-primary");
        summariseButton.setDisable(true);
        progress.getStyleClass().add("inline-progress");
        progress.setVisible(false);

        HBox row = new HBox(questionField, askButton, summariseButton, progress);
        row.getStyleClass().add("field-row");
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /**
     * Permanent and non-dismissable: it is part of the layout rather than
     * something shown alongside an answer, so there is no state in which the
     * pane displays a market opinion without it.
     */
    private Region disclaimer() {
        Label label = new Label("Informational only. Not financial advice.");
        label.getStyleClass().add("disclaimer");
        return label;
    }

    private void wireEvents() {
        answerArea.setEditable(false);
        answerArea.setWrapText(true);
        answerArea.setPrefRowCount(4);
        answerArea.getStyleClass().add("ai-answer");
        answerArea.setPromptText("Answers appear here.");
        VBox.setVgrow(answerArea, Priority.ALWAYS);

        askButton.setOnAction(event -> ask());
        questionField.setOnAction(event -> ask());
        summariseButton.setOnAction(event -> summarise());
    }

    private void loadKnownCurrencies() {
        UiUtils.onFxThread(
                catalog.load(),
                options -> knownCurrencies = options.stream().map(CurrencyOption::code).toList(),
                error -> answerArea.setText(UiUtils.messageFor(error)));
    }

    /**
     * Parses the question, then converts through the ordinary path.
     *
     * <p>The two steps are chained rather than merged so that a parsing failure
     * and a rate failure produce different messages — "I could not read that" is
     * a different problem from "CoinGecko is down", and only one of them is the
     * user's to fix.
     */
    private void ask() {
        String question = questionField.getText();
        if (question == null || question.isBlank()) {
            return;
        }
        busy(true);
        CompletableFuture<String> answer = assistant
                .parseConversion(question, knownCurrencies)
                .thenCompose(intent -> conversions.convertAndSave(intent.money(), intent.to())
                        .thenApply(result -> describe(intent, result)));

        UiUtils.onFxThread(
                answer,
                text -> {
                    busy(false);
                    answerArea.setText(text);
                    onConversionSaved.run();
                },
                error -> {
                    busy(false);
                    answerArea.setText(UiUtils.messageFor(error));
                });
    }

    /** Builds the sentence shown for a converted question. */
    private static String describe(ConversionIntent intent, ConversionResult result) {
        return "%s %s = %s %s%n(rate 1 %s = %s %s)"
                .formatted(
                        UiUtils.formatAmount(result.from()),
                        intent.from(),
                        UiUtils.formatAmount(result.to()),
                        intent.to(),
                        intent.from(),
                        UiUtils.formatRate(result.rate().rate()),
                        intent.to());
    }

    /**
     * Fetches the price and the week's series, then asks for a summary of them.
     *
     * <p>Both reads go through the cached rate service, so pressing the button
     * twice costs one set of requests.
     */
    private void summarise() {
        Optional<CurrencyOption> coin = selection == null ? Optional.empty() : selection.coin();
        Optional<CurrencyOption> fiat = selection == null ? Optional.empty() : selection.fiat();
        if (coin.isEmpty() || fiat.isEmpty()) {
            answerArea.setText("Pick a coin and a fiat currency above to summarise.");
            return;
        }
        busy(true);

        CompletableFuture<ExchangeRate> rate = rates.spotRate(coin.get().code(), fiat.get().code());
        CompletableFuture<List<PricePoint>> series =
                rates.priceHistory(coin.get().code(), fiat.get().code(), SUMMARY_DAYS);

        CompletableFuture<String> summary = rate.thenCombine(series, (spot, points) -> new MarketSnapshot(
                        coin.get().symbol(), fiat.get().symbol(), spot.rate(), spot.change24h(), points))
                .thenCompose(assistant::summariseMarket);

        UiUtils.onFxThread(
                summary,
                text -> {
                    busy(false);
                    answerArea.setText(text);
                },
                error -> {
                    busy(false);
                    answerArea.setText(UiUtils.messageFor(error));
                });
    }

    private void busy(boolean working) {
        progress.setVisible(working);
        askButton.setDisable(working);
        questionField.setDisable(working);
        summariseButton.setDisable(working || selection == null || selection.coin().isEmpty());
    }
}
