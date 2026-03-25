package com.quietpages.quietpages.controller;

import com.quietpages.quietpages.ThemeManager;

import javafx.fxml.FXML;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

public class SettingsController {

    @FXML
    private FlowPane themesPane;

    @FXML
    public void initialize() {
        markActive(ThemeManager.getCurrentTheme());
    }

    @FXML
    private void onLight() {
        apply("light");
    }

    @FXML
    private void onDark() {
        apply("dark");
    }

    @FXML
    private void onOcean() {
        apply("ocean");
    }

    @FXML
    private void onSunset() {
        apply("sunset");
    }

    @FXML
    private void onForest() {
        apply("forest");
    }

    @FXML
    private void onLavender() {
        apply("lavender");
    }

    @FXML
    private void onRose() {
        apply("rose");
    }

    @FXML
    private void onMidnight() {
        apply("midnight");
    }

    private void apply(String theme) {
        ThemeManager.applyTheme(theme);
        markActive(theme);
    }

    private void markActive(String theme) {
        if (themesPane == null) {
            return;
        }

        themesPane.getChildren().forEach(node -> {
            if (node instanceof VBox box) {

                boolean active = box.getId() != null
                        && box.getId().toLowerCase().contains(theme.toLowerCase());

                if (active) {
                    if (!box.getStyleClass().contains("theme-card-active")) {
                        box.getStyleClass().add("theme-card-active");
                    }
                } else {
                    box.getStyleClass().remove("theme-card-active");
                }
            }
        });
    }
}
