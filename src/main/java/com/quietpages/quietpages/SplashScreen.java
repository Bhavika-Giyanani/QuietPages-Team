package com.quietpages.quietpages;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.util.Duration;

/**
 * SplashScreen — adapted from teammate's SplashScreen.java.
 *
 * Shows for 2 seconds then transitions to the main app.
 * Tries to load logo from resources/images/logo.jpeg first.
 * If the image is not found, falls back to styled text.
 *
 * To use your teammate's logo image:
 * Place logo.jpeg at:
 * src/main/resources/com/quietpages/quietpages/images/logo.jpeg
 */
public class SplashScreen {

    public void show(Stage stage) {

        StackPane root = new StackPane();
        root.setStyle("-fx-background-color: #0D0D0D;");

        // ── Try to load logo image (same as teammate's approach) ─────────────
        javafx.scene.Node logoNode = buildLogo();
        root.getChildren().add(logoNode);

        Scene scene = new Scene(root, 1200, 700);
        scene.setFill(Color.web("#0D0D0D"));

        // Fade in — matches teammate's smooth appearance
        root.setOpacity(0);
        FadeTransition fadeIn = new FadeTransition(Duration.millis(500), root);
        fadeIn.setFromValue(0);
        fadeIn.setToValue(1);

        stage.setTitle("QuietPages");
        stage.setMinWidth(900);
        stage.setMinHeight(600);
        stage.setScene(scene);
        stage.show();

        fadeIn.play();

        // Wait 2 seconds (same as teammate) then load main app
        PauseTransition delay = new PauseTransition(Duration.seconds(2));
        delay.setOnFinished(e -> loadMainApp(stage));
        delay.play();
    }

    private javafx.scene.Node buildLogo() {
        // Try loading the image the same way teammate did
        try {
            var imgUrl = getClass().getResource(
                    "/com/quietpages/quietpages/images/logo.jpeg");
            if (imgUrl == null) {
                // Also try the path teammate used: /images/logo.jpeg
                imgUrl = getClass().getResource("/images/logo.jpeg");
            }
            if (imgUrl != null) {
                ImageView logo = new ImageView(new Image(imgUrl.toExternalForm()));
                logo.setFitWidth(1300);
                logo.setPreserveRatio(true);
                return logo;
            }
        } catch (Exception ignored) {
        }

        // ── Fallback: styled text logo (if image not found) ──────────────────
        Text appName = new Text("QuietPages");
        appName.setFont(Font.font("Segoe UI", FontWeight.LIGHT, 72));
        appName.setFill(Color.web("#DDDDDD"));

        Text tagline = new Text("Your personal reading space");
        tagline.setFont(Font.font("Segoe UI", FontWeight.THIN, 20));
        tagline.setFill(Color.web("#555555"));

        VBox box = new VBox(16, appName, tagline);
        box.setAlignment(Pos.CENTER);
        return box;
    }

    private void loadMainApp(Stage stage) {
        try {
            FXMLLoader loader = new FXMLLoader(
                    HelloApplication.class.getResource("hello-view.fxml"));
            Parent root = loader.load();

            Scene scene = new Scene(root, 1200, 700);
            scene.getStylesheets().add(
                    HelloApplication.class.getResource("library.css").toExternalForm());

            // Wire ThemeManager to the new scene and restore saved theme
            ThemeManager.setScene(scene);
            ThemeManager.restoreTheme();

            stage.setScene(scene);
            stage.setTitle("QuietPages");

        } catch (Exception ex) {
            System.err.println("[Splash] Failed to load main app: " + ex.getMessage());
            ex.printStackTrace();
        }
    }
}