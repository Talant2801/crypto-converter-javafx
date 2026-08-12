package io.github.talant2801.cryptoconverter;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** JavaFX application shell. Owns the stage and the {@link AppContext} lifecycle. */
public class CryptoConverterApp extends Application {

    private static final Logger LOG = LoggerFactory.getLogger(CryptoConverterApp.class);

    private static final int MIN_WIDTH = 900;
    private static final int MIN_HEIGHT = 650;

    private AppContext context;

    @Override
    public void init() {
        context = AppContext.create();
    }

    @Override
    public void start(Stage stage) {
        BorderPane root = new BorderPane();
        root.setCenter(new Label("CryptoConverter"));

        Scene scene = new Scene(root, MIN_WIDTH, MIN_HEIGHT);
        scene.getStylesheets().add(
                CryptoConverterApp.class.getResource("styles.css").toExternalForm());

        stage.setTitle("CryptoConverter");
        stage.setMinWidth(MIN_WIDTH);
        stage.setMinHeight(MIN_HEIGHT);
        stage.setScene(scene);
        stage.show();
    }

    @Override
    public void stop() {
        if (context != null) {
            try {
                context.close();
            } catch (Exception e) {
                LOG.warn("Failed to shut down cleanly", e);
            }
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
