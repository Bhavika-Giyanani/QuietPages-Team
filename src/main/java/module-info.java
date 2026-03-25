module com.quietpages.quietpages {
    // ── JavaFX modules ────────────────────────────────────────────────────────
    requires javafx.base;        // ObservableList, FXCollections, properties
    requires javafx.graphics;    // Node, Stage, Scene, Platform, ImageView etc.
    requires javafx.controls;    // Button, Label, TextField, ComboBox etc.
    requires javafx.fxml;        // FXMLLoader, @FXML
    requires javafx.web;         // WebView, WebEngine
    requires javafx.swing;       // SwingFXUtils (image conversion)
    requires javafx.media;       // Media (future TTS)

    // ── Third-party ───────────────────────────────────────────────────────────
    requires org.controlsfx.controls;
    requires org.kordamp.ikonli.javafx;
    requires org.kordamp.ikonli.fontawesome5;
    requires org.kordamp.ikonli.material2;

    // ── JDK ───────────────────────────────────────────────────────────────────
    requires java.sql;
    requires org.slf4j;
    requires org.xerial.sqlitejdbc;

    // ── Open packages to JavaFX reflection (required for FXML injection) ──────
    opens com.quietpages.quietpages            to javafx.fxml, javafx.graphics;
    opens com.quietpages.quietpages.controller to javafx.fxml;
    opens com.quietpages.quietpages.model      to javafx.base, javafx.fxml;
    opens com.quietpages.quietpages.service    to javafx.fxml;
    opens com.quietpages.quietpages.db         to javafx.fxml;
    opens com.quietpages.quietpages.util       to javafx.fxml;

    // ── Exports ───────────────────────────────────────────────────────────────
    exports com.quietpages.quietpages;
    exports com.quietpages.quietpages.controller;
    exports com.quietpages.quietpages.model;
    exports com.quietpages.quietpages.service;
    exports com.quietpages.quietpages.db;
    exports com.quietpages.quietpages.util;
}