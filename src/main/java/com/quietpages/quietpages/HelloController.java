package com.quietpages.quietpages;

import com.quietpages.quietpages.controller.HomeController;
import com.quietpages.quietpages.controller.LibraryController;
import com.quietpages.quietpages.controller.ReaderController;
import com.quietpages.quietpages.model.Book;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.layout.*;

import java.net.URL;

public class HelloController {

    // ── Static instance so HomeController / other tabs can call back into the
    // shell
    public static HelloController instance;

    // ── FXML ──────────────────────────────────────────────────────────────────
    @FXML
    private BorderPane rootPane;
    @FXML
    private VBox sidebar;
    @FXML
    private StackPane contentArea;

    @FXML
    private Button btnMenuToggle;
    @FXML
    private Button btnHome;
    @FXML
    private Button btnLibrary;
    @FXML
    private Button btnCollections;
    @FXML
    private Button btnOnlineBooks;
    @FXML
    private Button btnSettings;

    // ── Public getters (used by HomeController fallback) ────────────────────────
    public Button getBtnHome() {
        return btnHome;
    }

    public Button getBtnLibrary() {
        return btnLibrary;
    }

    // ── State ─────────────────────────────────────────────────────────────────
    private boolean sidebarCollapsed = true;
    // Track last active button so reader Back knows where to return
    private Button lastActiveBtn;

    // ── Init ──────────────────────────────────────────────────────────────────
    @FXML
    public void initialize() {
        instance = this; // expose to other controllers (HomeController etc.)
        collapseSidebar();
        showHome();
        setActiveNav(btnHome);
    }

    // ── Hamburger toggle ──────────────────────────────────────────────────────
    @FXML
    private void onMenuToggle() {
        sidebarCollapsed = !sidebarCollapsed;
        if (sidebarCollapsed)
            collapseSidebar();
        else
            expandSidebar();
    }

    private void collapseSidebar() {
        sidebar.setPrefWidth(48);
        sidebar.setMinWidth(48);
        sidebar.setMaxWidth(48);
        setNavText(btnHome, null);
        setNavText(btnLibrary, null);
        setNavText(btnCollections, null);
        setNavText(btnOnlineBooks, null);
        setNavText(btnSettings, null);
    }

    private void expandSidebar() {
        sidebar.setPrefWidth(220);
        sidebar.setMinWidth(220);
        sidebar.setMaxWidth(220);
        setNavText(btnHome, "  Home");
        setNavText(btnLibrary, "  Library");
        setNavText(btnCollections, "  Collections");
        setNavText(btnOnlineBooks, "  Online Books");
        setNavText(btnSettings, "  Settings");
    }

    private void setNavText(Button btn, String text) {
        if (btn == null)
            return;
        if (text == null) {
            btn.setText("");
            btn.setContentDisplay(javafx.scene.control.ContentDisplay.GRAPHIC_ONLY);
            btn.setMaxWidth(Double.MAX_VALUE);
            btn.setAlignment(javafx.geometry.Pos.CENTER);
        } else {
            btn.setText(text);
            btn.setContentDisplay(javafx.scene.control.ContentDisplay.LEFT);
            btn.setMaxWidth(Double.MAX_VALUE);
            btn.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        }
    }

    // ── Tab navigation ────────────────────────────────────────────────────────
    @FXML
    private void onHome() {
        showHome();
        setActiveNav(btnHome);
    }

    @FXML
    private void onLibrary() {
        showLibrary();
        setActiveNav(btnLibrary);
    }

    @FXML
    private void onCollections() {
        loadTabSafe("collections-view.fxml");
        setActiveNav(btnCollections);
    }

    @FXML
    private void onOnlineBooks() {
        loadTabSafe("online-books-view.fxml");
        setActiveNav(btnOnlineBooks);
    }

    @FXML
    private void onSettings() {
        loadTabSafe("settings-view.fxml");
        setActiveNav(btnSettings);
    }

    // Called by HomeController via instance.goToLibrary()
    public void goToLibrary() {
        onLibrary();
    }

    // ── Home tab ──────────────────────────────────────────────────────────────
    private void showHome() {
        URL resource = HelloApplication.class.getResource("home-view.fxml");
        if (resource == null) {
            showPlaceholder("home-view.fxml");
            return;
        }
        try {
            FXMLLoader loader = new FXMLLoader(resource);
            Node view = loader.load();
            HomeController hc = loader.getController();
            // Wire open-book callback so home page cards open the reader
            hc.setOnOpenBook(book -> openReader(book, btnHome));
            contentArea.getChildren().setAll(view);
        } catch (Exception e) {
            System.err.println("[Shell] home-view load failed: " + e.getMessage());
            showPlaceholder("home-view.fxml");
        }
    }

    // ── Library tab ───────────────────────────────────────────────────────────
    private void showLibrary() {
        URL resource = HelloApplication.class.getResource("library-view.fxml");
        if (resource == null) {
            showPlaceholder("library-view.fxml");
            return;
        }
        try {
            FXMLLoader loader = new FXMLLoader(resource);
            Node view = loader.load();
            LibraryController lc = loader.getController();
            // Wire open-book callback so library cards open the reader
            lc.setOnOpenBook(book -> openReader(book,
                    lastActiveBtn != null ? lastActiveBtn : btnLibrary));
            contentArea.getChildren().setAll(view);
        } catch (Exception e) {
            System.err.println("[Shell] library-view load failed: " + e.getMessage());
            showPlaceholder("library-view.fxml");
        }
    }

    // ── Reader ────────────────────────────────────────────────────────────────
    /**
     * Opens the reader for a book. Works when called from ANY tab.
     * Hides the sidebar for full-immersion reading.
     * When Back is pressed, restores sidebar and returns to the originating tab.
     *
     * @param book        the Book to open
     * @param returnToBtn the sidebar button of the tab that triggered this
     */
    public void openReader(Book book, Button returnToBtn) {
        URL resource = HelloApplication.class.getResource("reader-view.fxml");
        if (resource == null) {
            System.err.println("[Shell] reader-view.fxml not found");
            return;
        }
        try {
            FXMLLoader loader = new FXMLLoader(resource);
            Node view = loader.load();
            ReaderController rc = loader.getController();

            // Hide sidebar — full immersion like Aquile Reader
            sidebar.setVisible(false);
            sidebar.setManaged(false);

            rc.openBook(book, () -> {
                // Restore sidebar when Back is pressed
                sidebar.setVisible(true);
                sidebar.setManaged(true);

                // Return to the tab that opened the book
                Button ret = (returnToBtn != null) ? returnToBtn : btnLibrary;
                if (ret == btnHome) {
                    showHome();
                    setActiveNav(btnHome);
                } else if (ret == btnLibrary) {
                    showLibrary();
                    setActiveNav(btnLibrary);
                } else if (ret == btnCollections) {
                    loadTabSafe("collections-view.fxml");
                    setActiveNav(btnCollections);
                } else {
                    showLibrary();
                    setActiveNav(btnLibrary);
                }
            });

            contentArea.getChildren().setAll(view);
            setActiveNav(null); // no sidebar button active while reading
        } catch (Exception e) {
            System.err.println("[Shell] reader load failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private void loadTabSafe(String fxmlName) {
        URL resource = HelloApplication.class.getResource(fxmlName);
        if (resource == null) {
            showPlaceholder(fxmlName);
            return;
        }
        try {
            FXMLLoader loader = new FXMLLoader(resource);
            Node view = loader.load();
            contentArea.getChildren().setAll(view);
        } catch (Exception e) {
            System.err.println("[Shell] Failed to load " + fxmlName + ": " + e.getMessage());
            Throwable cause = e.getCause();
            while (cause != null) {
                System.err.println("  Caused by: " + cause);
                cause = cause.getCause();
            }
            showPlaceholder(fxmlName);
        }
    }

    private void showPlaceholder(String fxmlName) {
        String tabName = fxmlName.replace("-view.fxml", "").replace("-", " ");
        javafx.scene.control.Label label = new javafx.scene.control.Label(
                tabName.substring(0, 1).toUpperCase() + tabName.substring(1) + " — coming soon");
        label.setStyle("-fx-text-fill:#888888;-fx-font-size:16px;");
        StackPane placeholder = new StackPane(label);
        placeholder.setStyle("-fx-background-color:#2B2B2B;");
        contentArea.getChildren().setAll(placeholder);
    }

    private void setActiveNav(Button active) {
        if (active != null)
            lastActiveBtn = active;
        for (javafx.scene.Node n : sidebar.getChildren()) {
            if (n instanceof Button b)
                b.getStyleClass().remove("nav-active");
        }
        if (active != null)
            active.getStyleClass().add("nav-active");
    }
}