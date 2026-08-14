package io.github.talant2801.cryptoconverter.ui;

import java.util.Objects;
import java.util.Optional;
import javafx.geometry.Orientation;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * The window's layout, and the wiring between the panes that make it up.
 *
 * <p>The panes do not know about each other: the converter announces that a
 * conversion was saved, and this class is what turns that into a history
 * refresh. Keeping the connections in one place means a pane can be moved,
 * replaced or dropped without hunting for the callbacks it registered on its
 * neighbours.
 */
public final class MainView extends BorderPane {

    /** Left column against right column, as a fraction of the window's width. */
    private static final double CONVERTER_SHARE = 0.56;

    private final ConverterPane converter;
    private final ChartPane chart;
    private final HistoryPane history;
    private final Optional<AiPane> ai;

    /**
     * @param ai present only when an API key was configured; the window is laid
     *     out without the pane entirely when it is absent
     */
    public MainView(ConverterPane converter, ChartPane chart, HistoryPane history, Optional<AiPane> ai) {
        this.converter = Objects.requireNonNull(converter, "converter");
        this.chart = Objects.requireNonNull(chart, "chart");
        this.history = Objects.requireNonNull(history, "history");
        this.ai = Objects.requireNonNull(ai, "ai");

        getStyleClass().add("main-view");
        setTop(header());
        setCenter(body());
        // Safe to subscribe after the panes were constructed: the converter
        // loads its currency list asynchronously and announces the opening pair
        // from a Platform.runLater, which cannot run until this constructor
        // returns.
        converter.setOnPairChanged(pair -> {
            chart.show(pair);
            ai.ifPresent(pane -> pane.show(pair));
        });
        converter.setOnConversionSaved(history::refresh);
        ai.ifPresent(pane -> pane.setOnConversionSaved(history::refresh));
    }

    private VBox left() {
        VBox column = new VBox(converter, chart);
        VBox.setVgrow(chart, Priority.ALWAYS);
        column.getStyleClass().add("left-column");
        return column;
    }

    /** The right column: history, and the AI pane when there is one. */
    private VBox right() {
        VBox column = ai.map(pane -> new VBox(history, pane)).orElseGet(() -> new VBox(history));
        VBox.setVgrow(history, Priority.ALWAYS);
        column.getStyleClass().add("right-column");
        return column;
    }

    private Region body() {
        SplitPane split = new SplitPane(left(), right());
        split.setOrientation(Orientation.HORIZONTAL);
        split.setDividerPositions(CONVERTER_SHARE);
        split.getStyleClass().add("body-split");
        return split;
    }

    private Region header() {
        Label title = new Label("CryptoConverter");
        title.getStyleClass().add("app-title");

        Label subtitle = new Label("Live cryptocurrency rates from CoinGecko");
        subtitle.getStyleClass().add("label-muted");

        VBox header = new VBox(title, subtitle);
        header.getStyleClass().add("app-header");
        return header;
    }
}
