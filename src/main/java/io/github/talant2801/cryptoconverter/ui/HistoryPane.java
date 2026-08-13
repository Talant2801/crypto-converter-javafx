package io.github.talant2801.cryptoconverter.ui;

import io.github.talant2801.cryptoconverter.domain.ConversionRecord;
import io.github.talant2801.cryptoconverter.domain.Money;
import io.github.talant2801.cryptoconverter.service.HistoryService;
import io.github.talant2801.cryptoconverter.ui.util.UiUtils;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * The last hundred conversions, with a way to remove them.
 *
 * <p>The table is a view of the database, not a second copy of it: every change
 * is written first and the table is reloaded from storage afterwards. Updating
 * the list optimistically would be faster to write and would eventually show a
 * row that is not really there.
 *
 * <p>How many rows "the last hundred" means is
 * {@link HistoryService#HISTORY_LIMIT}'s business — this pane asks for recent
 * conversions and displays what it gets.
 */
public final class HistoryPane extends VBox {

    private final HistoryService history;
    private final ObservableList<ConversionRecord> rows = FXCollections.observableArrayList();
    private final TableView<ConversionRecord> table = new TableView<>(rows);
    private final Button deleteButton = new Button("Delete");
    private final Button clearButton = new Button("Clear all");

    public HistoryPane(HistoryService history) {
        this.history = Objects.requireNonNull(history, "history");

        getStyleClass().add("history-pane");
        getChildren().addAll(header(), buildTable(), buttons());
        wireEvents();
        refresh();
    }

    /** Reloads the table from storage. Called after every conversion and deletion. */
    public void refresh() {
        UiUtils.onFxThread(
                history.recent(),
                records -> {
                    rows.setAll(records);
                    updateButtons();
                },
                error -> UiUtils.showError("Could not read history", error));
    }

    private Region header() {
        Label title = new Label("History");
        title.getStyleClass().add("section-title");
        return title;
    }

    private Region buildTable() {
        table.getColumns().addAll(
                List.of(
                        column("When", 90, record -> UiUtils.formatTime(record.convertedAt())),
                        column("From", 130, record -> amount(record.fromAmount(), record.fromCurrency())),
                        column("To", 130, record -> amount(record.toAmount(), record.toCurrency())),
                        column("Rate", 110, record -> UiUtils.formatRate(record.rate()))));
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.setPlaceholder(new Label("No conversions yet"));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        VBox.setVgrow(table, Priority.ALWAYS);
        return table;
    }

    private Region buttons() {
        deleteButton.setOnAction(event -> deleteSelected());
        clearButton.setOnAction(event -> clearAll());

        HBox row = new HBox(deleteButton, clearButton);
        row.getStyleClass().add("button-row");
        row.setAlignment(Pos.CENTER_RIGHT);
        return row;
    }

    private void wireEvents() {
        table.getSelectionModel().getSelectedItems().addListener(
                (ListChangeListener<ConversionRecord>) change -> updateButtons());
    }

    /**
     * Deletes every selected row, reloading once at the end rather than after
     * each one.
     */
    private void deleteSelected() {
        List<ConversionRecord> selected = List.copyOf(table.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) {
            return;
        }
        CompletableFuture<?>[] deletions = selected.stream()
                .filter(record -> record.id() != null)
                .map(record -> history.delete(record.id()))
                .toArray(CompletableFuture[]::new);

        UiUtils.onFxThread(
                CompletableFuture.allOf(deletions),
                ignored -> refresh(),
                error -> UiUtils.showError("Could not delete", error));
    }

    /** Clearing everything is irreversible, so it asks first. */
    private void clearAll() {
        if (rows.isEmpty() || !UiUtils.confirm("Clear history", "Delete all saved conversions?")) {
            return;
        }
        UiUtils.onFxThread(
                history.clear(),
                removed -> refresh(),
                error -> UiUtils.showError("Could not clear history", error));
    }

    private void updateButtons() {
        deleteButton.setDisable(table.getSelectionModel().getSelectedItems().isEmpty());
        clearButton.setDisable(rows.isEmpty());
    }

    private static String amount(BigDecimal value, String currency) {
        return UiUtils.formatAmount(new Money(value, currency)) + " " + displayCode(currency);
    }

    /**
     * History stores what a currency <em>is</em> — a coin id or a fiat code —
     * so the table capitalises a coin id rather than inventing a ticker for it.
     * A row read back years later still names something unambiguous.
     */
    private static String displayCode(String currency) {
        if (currency.equals(currency.toUpperCase())) {
            return currency;
        }
        return Character.toUpperCase(currency.charAt(0)) + currency.substring(1);
    }

    private static TableColumn<ConversionRecord, String> column(
            String title, double width, Function<ConversionRecord, String> value) {

        TableColumn<ConversionRecord, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> cellValue(value.apply(cell.getValue())));
        return column;
    }

    private static ObservableValue<String> cellValue(String text) {
        return new ReadOnlyStringWrapper(text);
    }
}
