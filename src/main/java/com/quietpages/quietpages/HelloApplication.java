package com.quietpages.quietpages;

import javafx.application.Application;
import javafx.stage.Stage;

public class HelloApplication extends Application {

    private static Stage primaryStage;

    @Override
    public void start(Stage stage) {
        primaryStage = stage;

        // Show splash screen first — it loads the main app after 2 seconds
        new SplashScreen().show(stage);
    }

    public static Stage getPrimaryStage() { return primaryStage; }

    public static void main(String[] args) {
        launch();
    }
}