package io.github.talant2801.cryptoconverter;

import java.util.logging.Level;
import java.util.logging.Logger;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

/** JavaFX application shell. Owns the stage and the {@link AppContext} lifecycle. */
public class CryptoConverterApp extends Application {

    private static final Logger LOG = Logger.getLogger(CryptoConverterApp.class.getName());

    private static final int MIN_WIDTH = 900;
    private static final int MIN_HEIGHT = 620;

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
                LOG.log(Level.WARNING, "Failed to shut down cleanly", e);
            }
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
