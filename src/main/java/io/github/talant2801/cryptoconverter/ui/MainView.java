package io.github.talant2801.cryptoconverter.ui;

import java.util.Objects;
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

    public MainView(ConverterPane converter, ChartPane chart, HistoryPane history) {
        this.converter = Objects.requireNonNull(converter, "converter");
        this.chart = Objects.requireNonNull(chart, "chart");
        this.history = Objects.requireNonNull(history, "history");

        getStyleClass().add("main-view");
        setTop(header());
        setCenter(body());
        converter.setOnConversionSaved(history::refresh);
        // Safe to subscribe after the converter was constructed: it loads its
        // currency list asynchronously and announces the opening pair from a
        // Platform.runLater, which cannot run until this constructor returns.
        converter.setOnPairChanged(chart::show);
    }

    private VBox left() {
        VBox column = new VBox(converter, chart);
        VBox.setVgrow(chart, Priority.ALWAYS);
        column.getStyleClass().add("left-column");
        return column;
    }

    private Region body() {
        SplitPane split = new SplitPane(left(), history);
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
