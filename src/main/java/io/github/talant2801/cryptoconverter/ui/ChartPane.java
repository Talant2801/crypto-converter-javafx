package io.github.talant2801.cryptoconverter.ui;

import io.github.talant2801.cryptoconverter.domain.PricePoint;
import io.github.talant2801.cryptoconverter.service.RateService;
import io.github.talant2801.cryptoconverter.ui.ConverterPane.CurrencyPairSelection;
import io.github.talant2801.cryptoconverter.ui.util.UiUtils;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javafx.geometry.Pos;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

/**
 * The selected pair's price over the last 7, 30 or 90 days.
 *
 * <p>Three states share one area of the window — loading, failed, and a drawn
 * chart — and exactly one is visible at a time. A pane that leaves a stale chart
 * on screen while loading a new range invites the user to read the wrong
 * numbers, so the chart is hidden while an answer is on its way and a failure
 * gets a message and a retry button rather than a silent empty grid.
 *
 * <p>Only a coin priced in fiat can be charted, because that is the series
 * CoinGecko publishes. A crypto-to-crypto pair says so plainly instead of
 * showing an invented cross series.
 */
public final class ChartPane extends VBox {

    /** The ranges the endpoint serves and the toggle offers. */
    private static final List<Integer> RANGES = List.of(7, 30, 90);

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM");
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("EEE HH:mm");

    private final RateService rates;

    private final NumberAxis xAxis = new NumberAxis();
    private final NumberAxis yAxis = new NumberAxis();
    private final LineChart<Number, Number> chart = new LineChart<>(xAxis, yAxis);
    private final ProgressIndicator progress = new ProgressIndicator();
    private final Label message = new Label();
    private final Button retry = new Button("Retry");
    private final VBox failure = new VBox(message, retry);
    private final ToggleGroup ranges = new ToggleGroup();

    private CurrencyPairSelection selection;
    private int days = RANGES.get(0);

    /** Guards against a slow reply for one range landing after another was picked. */
    private long latestRequest;

    public ChartPane(RateService rates) {
        this.rates = Objects.requireNonNull(rates, "rates");

        getStyleClass().add("chart-pane");
        getChildren().addAll(header(), body());
        retry.setOnAction(event -> load());
        showMessage("Select a coin and a fiat currency to see its price history");
    }

    /** Points the chart at a new pair, reloading unless nothing actually changed. */
    public void show(CurrencyPairSelection pair) {
        if (Objects.equals(selection, pair)) {
            return;
        }
        selection = pair;
        load();
    }

    private Region header() {
        Label title = new Label("Price history");
        title.getStyleClass().add("section-title");

        HBox row = new HBox(title, spacer(), rangeButtons());
        row.getStyleClass().add("chart-header");
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private Region rangeButtons() {
        HBox row = new HBox();
        row.getStyleClass().add("range-toggle");
        for (int range : RANGES) {
            ToggleButton button = new ToggleButton(range + "D");
            button.setToggleGroup(ranges);
            button.setUserData(range);
            button.setSelected(range == days);
            // Clicking the selected button would otherwise deselect it and
            // leave the chart with no range at all.
            button.setOnAction(event -> button.setSelected(true));
            row.getChildren().add(button);
        }
        ranges.selectedToggleProperty().addListener((source, previous, current) -> {
            if (current != null) {
                days = (int) current.getUserData();
                load();
            }
        });
        return row;
    }

    private Region body() {
        chart.setCreateSymbols(false);
        chart.setLegendVisible(false);
        chart.setAnimated(false);
        chart.getStyleClass().add("price-chart");

        xAxis.setForceZeroInRange(false);
        xAxis.setTickLabelFormatter(timeLabels());
        yAxis.setForceZeroInRange(false);
        yAxis.setTickLabelFormatter(priceLabels());

        progress.getStyleClass().add("chart-progress");
        progress.setVisible(false);

        message.getStyleClass().add("label-muted");
        message.setWrapText(true);
        failure.getStyleClass().add("chart-message");
        failure.setAlignment(Pos.CENTER);
        retry.setVisible(false);

        StackPane stack = new StackPane(chart, failure, progress);
        stack.getStyleClass().add("chart-body");
        VBox.setVgrow(stack, Priority.ALWAYS);
        return stack;
    }

    private void load() {
        Optional<CurrencyOption> coin = selection == null ? Optional.empty() : selection.coin();
        Optional<CurrencyOption> fiat = selection == null ? Optional.empty() : selection.fiat();
        if (coin.isEmpty() || fiat.isEmpty()) {
            showMessage("Price history is available for a coin priced in a fiat currency");
            return;
        }

        long request = ++latestRequest;
        showLoading();
        UiUtils.onFxThread(
                rates.priceHistory(coin.get().code(), fiat.get().code(), days),
                points -> {
                    if (request == latestRequest) {
                        draw(points, coin.get(), fiat.get());
                    }
                },
                error -> {
                    if (request == latestRequest) {
                        showFailure(UiUtils.messageFor(error));
                    }
                });
    }

    private void draw(List<PricePoint> points, CurrencyOption coin, CurrencyOption fiat) {
        if (points.isEmpty()) {
            showMessage("No price history was published for this pair");
            return;
        }
        XYChart.Series<Number, Number> series = new XYChart.Series<>();
        for (PricePoint point : points) {
            // A chart is pixels: doubles are the right type here, and this is
            // the one place a monetary value is allowed to become one, because
            // nothing downstream does arithmetic with it.
            series.getData().add(new XYChart.Data<>(
                    point.at().getEpochSecond(), point.price().doubleValue()));
        }
        chart.getData().setAll(List.of(series));
        yAxis.setLabel(fiat.symbol());
        xAxis.setLabel("%s over %d days".formatted(coin.symbol(), days));
        showChart();
    }

    private void showLoading() {
        progress.setVisible(true);
        chart.setVisible(false);
        failure.setVisible(false);
    }

    private void showChart() {
        progress.setVisible(false);
        chart.setVisible(true);
        failure.setVisible(false);
    }

    /** A dead end the user cannot act on: no retry button, just an explanation. */
    private void showMessage(String text) {
        message.setText(text);
        retry.setVisible(false);
        progress.setVisible(false);
        chart.setVisible(false);
        failure.setVisible(true);
    }

    /** A failure the user can act on, so it offers the action. */
    private void showFailure(String text) {
        showMessage(text);
        retry.setVisible(true);
    }

    /** Dates on a 30 or 90 day range; day and hour on a 7 day one. */
    private StringConverter<Number> timeLabels() {
        return new StringConverter<>() {

            @Override
            public String toString(Number epochSeconds) {
                Instant at = Instant.ofEpochSecond(epochSeconds.longValue());
                DateTimeFormatter format = days <= 7 ? HOUR : DAY;
                return format.format(at.atZone(ZoneId.systemDefault()));
            }

            @Override
            public Number fromString(String label) {
                throw new UnsupportedOperationException("Axis labels are not editable");
            }
        };
    }

    private static StringConverter<Number> priceLabels() {
        return new StringConverter<>() {

            @Override
            public String toString(Number price) {
                return UiUtils.formatRate(new BigDecimal(price.toString()));
            }

            @Override
            public Number fromString(String label) {
                throw new UnsupportedOperationException("Axis labels are not editable");
            }
        };
    }

    private static Region spacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }
}
