package com.quietpages.quietpages.controller;

import com.quietpages.quietpages.db.DatabaseManager;
import com.quietpages.quietpages.model.Book;
import com.quietpages.quietpages.util.EpubRenderer;
import com.quietpages.quietpages.util.EpubRenderer.*;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.*;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import org.kordamp.ikonli.javafx.FontIcon;

import java.io.File;
import java.sql.PreparedStatement;
import java.util.List;

/**
 * ReaderController — drives reader-view.fxml.
 *
 * Architecture:
 * - EpubRenderer extracts the EPUB ZIP to a temp folder and serves file://
 * URLs.
 * - Two WebViews (left + right) show adjacent spine items (two-page spread).
 * - CSS is injected into each chapter HTML so our reader theme controls
 * fonts/colors.
 * - Page navigation: keyboard LEFT/RIGHT, scroll wheel, or on-screen arrows.
 * - TOC panel overlays on the left; Search and Text Style float top-right.
 * - All popup panels are built in code (not FXML) so they can be toggled
 * easily.
 */
public class ReaderController {

    // ── FXML injections ───────────────────────────────────────────────────────
    @FXML
    private BorderPane readerRoot;
    @FXML
    private HBox toolbarBox;
    @FXML
    private Button btnBack;
    @FXML
    private Label lblBookTitle;
    @FXML
    private Button btnToc;
    @FXML
    private Button btnSearch;
    @FXML
    private Button btnTextStyle;
    @FXML
    private Button btnFullscreen;
    @FXML
    private FontIcon iconFullscreen;
    @FXML
    private HBox webViewContainer;
    @FXML
    private StackPane readerStack;
    @FXML
    private Button btnPrevPage;
    @FXML
    private Button btnNextPage;
    @FXML
    private Label lblBreadcrumb;
    @FXML
    private Label lblProgress;

    // ── WebViews (built in code so we control sizing precisely) ───────────────
    private WebView leftWebView;
    private WebView rightWebView;
    private WebEngine leftEngine;
    private WebEngine rightEngine;

    // ── Reader state ──────────────────────────────────────────────────────────
    private Book book;
    private EpubRenderer renderer;
    private ReaderTheme theme = new ReaderTheme();
    private int currentIndex = 0; // spine index of left page
    private int totalChapters = 0;
    private boolean fullscreen = false;
    private Runnable onBackCallback;

    // Popup panel IDs (used to find and remove them from readerStack)
    private static final String ID_TOC = "qp-toc-panel";
    private static final String ID_SEARCH = "qp-search-popup";
    private static final String ID_STYLE = "qp-style-popup";

    // ── Init ──────────────────────────────────────────────────────────────────
    @FXML
    public void initialize() {
        buildWebViews();
        setupKeyAndScrollNav();
    }

    // ── Public entry point (called by HelloController) ────────────────────────

    /**
     * Opens the given book in the reader.
     * 
     * @param book   the Book to open
     * @param onBack callback fired when user presses the back button
     */
    public void openBook(Book book, Runnable onBack) {
        this.book = book;
        this.onBackCallback = onBack;

        lblBookTitle.setText(book.getTitle());
        setWindowTitle(book.getTitle() + " — QuietPages");

        // Load EPUB on background thread — never block the UI thread
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                renderer = new EpubRenderer(new File(book.getFilePath()));
                renderer.load();
                return null;
            }
        };
        task.setOnSucceeded(e -> Platform.runLater(() -> {
            totalChapters = renderer.getTotalChapters();
            // Restore last reading position
            currentIndex = (int) (book.getReadingProgress() * Math.max(1, totalChapters));
            currentIndex = Math.max(0, Math.min(currentIndex, totalChapters - 1));
            // Make sure left page is always even-indexed for clean spreads
            if (currentIndex % 2 != 0)
                currentIndex = Math.max(0, currentIndex - 1);
            loadSpread();
        }));
        task.setOnFailed(
                e -> Platform.runLater(() -> showError("Could not open book: " + task.getException().getMessage())));
        new Thread(task, "epub-loader").start();
    }

    // ── WebView construction ──────────────────────────────────────────────────

    private void buildWebViews() {
        leftWebView = new WebView();
        rightWebView = new WebView();
        leftEngine = leftWebView.getEngine();
        rightEngine = rightWebView.getEngine();

        // Disable right-click context menus inside the WebViews
        leftWebView.setContextMenuEnabled(false);
        rightWebView.setContextMenuEnabled(false);

        // Both columns grow equally to fill the window width
        HBox.setHgrow(leftWebView, Priority.ALWAYS);
        HBox.setHgrow(rightWebView, Priority.ALWAYS);
        leftWebView.setMaxWidth(Double.MAX_VALUE);
        rightWebView.setMaxWidth(Double.MAX_VALUE);

        // Thin 1px divider line between the pages
        Region divider = new Region();
        divider.getStyleClass().add("reader-page-divider");
        divider.setMinWidth(1);
        divider.setMaxWidth(1);

        webViewContainer.getChildren().addAll(leftWebView, divider, rightWebView);

        // Update breadcrumb/progress whenever left page finishes loading
        leftEngine.documentProperty().addListener(
                (obs, o, n) -> {
                    if (n != null)
                        Platform.runLater(this::updateStatusBar);
                });
    }

    private void setupKeyAndScrollNav() {
        // StackPane must be focusable to receive key events
        readerStack.setFocusTraversable(true);

        // Click on the reading area to give it focus (so keys work)
        readerStack.setOnMouseClicked(e -> {
            // Only steal focus if not clicking a popup
            if (e.getTarget() == readerStack || e.getTarget() instanceof WebView) {
                readerStack.requestFocus();
            }
        });

        // Keyboard navigation — LEFT/RIGHT arrows and Page Up/Down
        readerStack.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.LEFT || e.getCode() == KeyCode.PAGE_UP)
                onPrevPage();
            if (e.getCode() == KeyCode.RIGHT || e.getCode() == KeyCode.PAGE_DOWN)
                onNextPage();
        });

        // Mouse scroll — forward = next pages, backward = previous pages
        // Applied to both WebViews so scrolling anywhere in the text flips pages
        leftWebView.addEventFilter(ScrollEvent.SCROLL, e -> {
            if (e.getDeltaY() < 0)
                onNextPage();
            else if (e.getDeltaY() > 0)
                onPrevPage();
            e.consume(); // prevent WebView from scrolling internally
        });
        rightWebView.addEventFilter(ScrollEvent.SCROLL, e -> {
            if (e.getDeltaY() < 0)
                onNextPage();
            else if (e.getDeltaY() > 0)
                onPrevPage();
            e.consume();
        });
    }

    // ── Page loading ──────────────────────────────────────────────────────────

    /**
     * Loads the current two-page spread.
     * Left page = spine[currentIndex]
     * Right page = spine[currentIndex + 1] (or blank if last chapter)
     */
    private void loadSpread() {
        if (renderer == null || totalChapters == 0)
            return;
        currentIndex = Math.max(0, Math.min(currentIndex, totalChapters - 1));

        try {
            // Left page
            String leftUrl = renderer.getChapterUrlWithTheme(currentIndex, theme);
            leftEngine.load(leftUrl);

            // Right page — if there is a next chapter, show it; otherwise show blank
            if (currentIndex + 1 < totalChapters) {
                String rightUrl = renderer.getChapterUrlWithTheme(currentIndex + 1, theme);
                rightEngine.load(rightUrl);
            } else {
                rightEngine.loadContent(blankPage());
            }

            updateStatusBar();
            persistProgress();

        } catch (Exception ex) {
            showError(ex.getMessage());
        }
    }

    private String blankPage() {
        return "<html><body style='background:" + theme.bgColor
                + ";margin:0;padding:0;'></body></html>";
    }

    // ── Toolbar action handlers ───────────────────────────────────────────────

    @FXML
    private void onBack() {
        persistProgress();
        if (renderer != null)
            renderer.cleanup();
        setWindowTitle("QuietPages");
        closeAllPopups();
        if (onBackCallback != null)
            onBackCallback.run();
    }

    @FXML
    private void onToc() {
        if (renderer == null)
            return;
        // TOC panel is a sliding overlay from the left — toggle it
        Node existing = readerStack.lookup("#" + ID_TOC);
        if (existing != null) {
            readerStack.getChildren().remove(existing);
            btnToc.getStyleClass().remove("active");
        } else {
            closeAllPopups();
            showTocPanel(renderer.getToc());
            btnToc.getStyleClass().add("active");
        }
    }

    @FXML
    private void onSearch() {
        Node existing = readerStack.lookup("#" + ID_SEARCH);
        if (existing != null) {
            readerStack.getChildren().remove(existing);
            btnSearch.getStyleClass().remove("active");
            clearSearchHighlights();
        } else {
            closeAllPopups();
            showSearchPopup();
            btnSearch.getStyleClass().add("active");
        }
    }

    @FXML
    private void onTextStyle() {
        Node existing = readerStack.lookup("#" + ID_STYLE);
        if (existing != null) {
            readerStack.getChildren().remove(existing);
            btnTextStyle.getStyleClass().remove("active");
        } else {
            closeAllPopups();
            showTextStylePopup();
            btnTextStyle.getStyleClass().add("active");
        }
    }

    @FXML
    private void onFullscreen() {
        Stage s = stage();
        if (s == null)
            return;
        fullscreen = !fullscreen;
        s.setFullScreen(fullscreen);
        // Update icon: expand ↔ compress
        iconFullscreen.setIconLiteral(fullscreen ? "fas-compress" : "fas-expand");
    }

    @FXML
    private void onPrevPage() {
        if (currentIndex > 0) {
            currentIndex = Math.max(0, currentIndex - 2);
            loadSpread();
        }
    }

    @FXML
    private void onNextPage() {
        if (renderer == null)
            return;
        if (currentIndex + 2 < totalChapters) {
            currentIndex += 2;
            loadSpread();
        } else if (currentIndex + 1 < totalChapters) {
            currentIndex++;
            loadSpread();
        }
    }

    // ── TOC panel (slides in from the left) ───────────────────────────────────

    private void showTocPanel(List<TocEntry> tocList) {
        VBox panel = new VBox(0);
        panel.setId(ID_TOC);
        panel.getStyleClass().add("reader-toc-panel");
        panel.setPrefWidth(340);
        panel.setMaxWidth(340);

        // Header
        Label header = new Label("Table of Contents");
        header.getStyleClass().add("reader-toc-header");
        header.setMaxWidth(Double.MAX_VALUE);

        // TreeView for hierarchical TOC
        TreeView<TocEntry> tree = new TreeView<>();
        tree.setShowRoot(false);
        tree.getStyleClass().add("reader-toc-tree");
        tree.setStyle("-fx-focus-color: transparent;");
        VBox.setVgrow(tree, Priority.ALWAYS);

        TreeItem<TocEntry> root = new TreeItem<>();
        fillTocTree(root, tocList);
        tree.setRoot(root);

        tree.setCellFactory(tv -> new TreeCell<>() {
            @Override
            protected void updateItem(TocEntry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                // Indent child entries slightly
                boolean isChild = getTreeItem().getParent() != null
                        && getTreeItem().getParent().getParent() != null;
                setText(item.title);
                setStyle("-fx-text-fill: " + (isChild ? "#AAAAAA" : "#DDDDDD") + ";"
                        + "-fx-font-size: " + (isChild ? "12" : "13") + "px;"
                        + "-fx-padding: " + (isChild ? "5 8 5 24" : "6 8 6 8") + ";"
                        + "-fx-background-color: transparent;"
                        + "-fx-cursor: hand;");
            }
        });

        // Navigate on single click
        tree.getSelectionModel().selectedItemProperty().addListener((obs, ov, nv) -> {
            if (nv != null && nv.getValue() != null
                    && !nv.getValue().resolvedUrl.isBlank()) {
                navigateToTocEntry(nv.getValue());
                // Close panel after navigation
                Platform.runLater(() -> {
                    readerStack.getChildren().remove(panel);
                    btnToc.getStyleClass().remove("active");
                });
            }
        });

        ScrollPane scroll = new ScrollPane(tree);
        scroll.getStyleClass().add("reader-scroll-pane");
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        panel.getChildren().addAll(header, scroll);

        // Pin to left edge, full height
        StackPane.setAlignment(panel, Pos.CENTER_LEFT);
        readerStack.getChildren().add(panel);

        // Close when clicking outside the panel
        readerStack.setOnMouseClicked(e -> {
            if (e.getX() > 340) {
                readerStack.getChildren().remove(panel);
                btnToc.getStyleClass().remove("active");
                readerStack.setOnMouseClicked(null);
                readerStack.requestFocus();
            }
        });
    }

    private void fillTocTree(TreeItem<TocEntry> parent, List<TocEntry> list) {
        for (TocEntry entry : list) {
            TreeItem<TocEntry> item = new TreeItem<>(entry);
            item.setExpanded(true);
            parent.getChildren().add(item);
            if (!entry.children.isEmpty())
                fillTocTree(item, entry.children);
        }
    }

    private void navigateToTocEntry(TocEntry entry) {
        String base = entry.resolvedUrl.split("#")[0];
        int idx = renderer.findSpineIndex(base);
        // Snap to even index for clean two-page spread
        currentIndex = (idx % 2 == 0) ? idx : Math.max(0, idx - 1);
        loadSpread();

        // Scroll to anchor fragment after page loads
        if (entry.resolvedUrl.contains("#")) {
            final String anchor = entry.resolvedUrl.split("#")[1];
            leftEngine.documentProperty().addListener(
                    new javafx.beans.value.ChangeListener<org.w3c.dom.Document>() {
                        @Override
                        public void changed(
                                javafx.beans.value.ObservableValue<? extends org.w3c.dom.Document> obs,
                                org.w3c.dom.Document ov,
                                org.w3c.dom.Document nv) {
                            if (nv != null) {
                                try {
                                    leftEngine.executeScript(
                                            "var el=document.getElementById('"
                                                    + anchor + "');if(el)el.scrollIntoView();");
                                } catch (Exception ignored) {
                                }
                                leftEngine.documentProperty().removeListener(this);
                            }
                        }
                    });
        }
    }

    // ── Search popup (top-right, matches screenshot Image 2) ─────────────────

    private void showSearchPopup() {
        VBox popup = new VBox(8);
        popup.setId(ID_SEARCH);
        popup.getStyleClass().add("reader-popup");
        popup.setPadding(new Insets(10, 12, 10, 12));
        popup.setPrefWidth(360);
        popup.setMaxWidth(360);

        // Row 1: search field
        TextField field = new TextField();
        field.setPromptText("Find ...");
        field.getStyleClass().add("reader-search-field");
        field.setMaxWidth(Double.MAX_VALUE);

        // Row 2: Aa | Ab| | [scope dropdown] | → | ✕
        ToggleButton btnMatchCase = new ToggleButton("Aa");
        ToggleButton btnWholeWord = new ToggleButton("Ab|");
        btnMatchCase.getStyleClass().add("reader-search-toggle");
        btnWholeWord.getStyleClass().add("reader-search-toggle");

        ComboBox<String> scope = new ComboBox<>();
        scope.getItems().addAll("Current chapter", "Entire book");
        scope.setValue("Current chapter");
        scope.getStyleClass().add("reader-search-scope");
        scope.setPrefWidth(140);

        FontIcon goIcon = new FontIcon("fas-arrow-right");
        goIcon.setIconSize(12);
        goIcon.setStyle("-fx-icon-color:#AAAAAA;");
        Button btnGo = new Button("", goIcon);
        btnGo.getStyleClass().add("reader-search-icon-btn");

        FontIcon closeIcon = new FontIcon("fas-times");
        closeIcon.setIconSize(12);
        closeIcon.setStyle("-fx-icon-color:#AAAAAA;");
        Button btnClose = new Button("", closeIcon);
        btnClose.getStyleClass().add("reader-search-icon-btn");
        btnClose.setOnAction(e -> {
            readerStack.getChildren().remove(popup);
            btnSearch.getStyleClass().remove("active");
            clearSearchHighlights();
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row2 = new HBox(6, btnMatchCase, btnWholeWord, spacer, scope, btnGo, btnClose);
        row2.setAlignment(Pos.CENTER_LEFT);

        // Result label
        Label resultLbl = new Label();
        resultLbl.setStyle("-fx-font-size:11px;");

        popup.getChildren().addAll(field, row2, resultLbl);
        StackPane.setAlignment(popup, Pos.TOP_RIGHT);
        StackPane.setMargin(popup, new Insets(8, 8, 0, 0));
        readerStack.getChildren().add(popup);
        Platform.runLater(field::requestFocus);

        // Search logic
        Runnable doSearch = () -> {
            String term = field.getText().trim();
            if (term.isBlank())
                return;
            // Use browser's window.find() — works in WebKit/WebView
            String safe = term.replace("\\", "\\\\").replace("'", "\\'");
            String js = String.format(
                    "window.find('%s',%b,false,true,%b)",
                    safe, btnMatchCase.isSelected(), btnWholeWord.isSelected());
            try {
                boolean found = Boolean.parseBoolean(
                        String.valueOf(leftEngine.executeScript(js)));
                resultLbl.setText(found ? "✓ Match found" : "✗ No matches");
                resultLbl.getStyleClass().setAll(
                        found ? "reader-search-result-found" : "reader-search-result-none");
            } catch (Exception ex) {
                resultLbl.setText("Search error");
            }
        };
        btnGo.setOnAction(e -> doSearch.run());
        field.setOnAction(e -> doSearch.run());
    }

    private void clearSearchHighlights() {
        try {
            leftEngine.executeScript("window.getSelection().removeAllRanges()");
        } catch (Exception ignored) {
        }
        try {
            rightEngine.executeScript("window.getSelection().removeAllRanges()");
        } catch (Exception ignored) {
        }
    }

    // ── Text style popup (top-right, matches screenshot Image 1) ─────────────

    private void showTextStylePopup() {
        VBox popup = new VBox(10);
        popup.setId(ID_STYLE);
        popup.getStyleClass().add("reader-popup");
        popup.setPadding(new Insets(16));
        popup.setPrefWidth(340);
        popup.setMaxWidth(340);

        // ── Line space ────────────────────────────────────────────────────────
        Label lblLine = new Label("Line space");
        lblLine.getStyleClass().add("reader-popup-label");
        Slider sliderLine = makeSlider(1.0, 3.0, theme.lineHeight);

        // ── Word space ────────────────────────────────────────────────────────
        Label lblWord = new Label("Word space");
        lblWord.getStyleClass().add("reader-popup-label");
        Slider sliderWord = makeSlider(-0.05, 0.5, theme.wordSpacing);

        // ── Paragraph space ───────────────────────────────────────────────────
        Label lblPara = new Label("Paragraph space");
        lblPara.getStyleClass().add("reader-popup-label");
        Slider sliderPara = makeSlider(0.0, 3.0, theme.paragraphSpace);

        // ── Text alignment ────────────────────────────────────────────────────
        Label lblAlign = new Label("Text alignment");
        lblAlign.getStyleClass().add("reader-popup-label");

        ToggleGroup alignGroup = new ToggleGroup();
        ToggleButton aLeft = makeAlignBtn("fas-align-left", "left", alignGroup);
        ToggleButton aCenter = makeAlignBtn("fas-align-center", "center", alignGroup);
        ToggleButton aJustify = makeAlignBtn("fas-align-justify", "justify", alignGroup);
        ToggleButton aRight = makeAlignBtn("fas-align-right", "right", alignGroup);

        // Select whichever matches current theme
        alignGroup.getToggles().stream()
                .filter(t -> theme.textAlign.equals(t.getUserData()))
                .findFirst().ifPresent(alignGroup::selectToggle);

        HBox alignRow = new HBox(6, aLeft, aCenter, aJustify, aRight);
        alignRow.setAlignment(Pos.CENTER_LEFT);

        // ── Reset button ──────────────────────────────────────────────────────
        Button btnReset = new Button("↺");
        btnReset.getStyleClass().add("reader-reset-btn");
        btnReset.setOnAction(e -> {
            // Mutate in place — do NOT reassign theme (lambda capture issues)
            ReaderTheme defaults = new ReaderTheme();
            theme.lineHeight = defaults.lineHeight;
            theme.wordSpacing = defaults.wordSpacing;
            theme.paragraphSpace = defaults.paragraphSpace;
            theme.textAlign = defaults.textAlign;
            theme.fontSize = defaults.fontSize;
            theme.marginH = defaults.marginH;

            sliderLine.setValue(theme.lineHeight);
            sliderWord.setValue(theme.wordSpacing);
            sliderPara.setValue(theme.paragraphSpace);
            alignGroup.getToggles().stream()
                    .filter(t -> "justify".equals(t.getUserData()))
                    .findFirst().ifPresent(alignGroup::selectToggle);
            loadSpread();
        });

        HBox resetRow = new HBox(btnReset);
        resetRow.setAlignment(Pos.BOTTOM_RIGHT);

        popup.getChildren().addAll(
                lblLine, sliderLine,
                lblWord, sliderWord,
                lblPara, sliderPara,
                lblAlign, alignRow,
                resetRow);

        StackPane.setAlignment(popup, Pos.TOP_RIGHT);
        StackPane.setMargin(popup, new Insets(8, 8, 0, 0));
        readerStack.getChildren().add(popup);

        // Live preview — listeners use ReaderController.this to avoid capture issues
        sliderLine.valueProperty().addListener((o, ov, nv) -> {
            ReaderController.this.theme.lineHeight = nv.doubleValue();
            loadSpread();
        });
        sliderWord.valueProperty().addListener((o, ov, nv) -> {
            ReaderController.this.theme.wordSpacing = nv.doubleValue();
            loadSpread();
        });
        sliderPara.valueProperty().addListener((o, ov, nv) -> {
            ReaderController.this.theme.paragraphSpace = nv.doubleValue();
            loadSpread();
        });
        alignGroup.selectedToggleProperty().addListener((o, ov, nv) -> {
            if (nv != null) {
                ReaderController.this.theme.textAlign = (String) nv.getUserData();
                loadSpread();
            }
        });
    }

    // ── Status bar ────────────────────────────────────────────────────────────

    private void updateStatusBar() {
        if (renderer == null)
            return;

        // Breadcrumb: "BookTitle » ChapterName N/Total"
        String bookTitle = renderer.getBookTitle();
        String chapName = chapNameFor(currentIndex);
        String breadcrumb = chapName.isEmpty() || chapName.equalsIgnoreCase(bookTitle)
                ? bookTitle
                : bookTitle + " » " + chapName;
        breadcrumb += "   " + (currentIndex + 1) + "/" + totalChapters;
        lblBreadcrumb.setText(breadcrumb);

        // Progress percentage
        double pct = totalChapters > 0 ? currentIndex * 100.0 / totalChapters : 0.0;
        lblProgress.setText(String.format("%.2f%%", pct));
    }

    private String chapNameFor(int idx) {
        if (renderer == null)
            return "";
        String url = renderer.getChapterUrl(idx);
        if (url == null)
            return "";
        for (TocEntry e : renderer.getToc()) {
            if (e.resolvedUrl != null && e.resolvedUrl.split("#")[0].equals(url))
                return e.title;
        }
        return "";
    }

    // ── Progress persistence ──────────────────────────────────────────────────

    private void persistProgress() {
        if (book == null || totalChapters == 0)
            return;
        double p = (double) currentIndex / totalChapters;
        book.setReadingProgress(p);
        try (PreparedStatement ps = DatabaseManager.getInstance().getConnection()
                .prepareStatement(
                        "UPDATE books SET reading_progress=?," +
                                " reading_status='READING', last_read=datetime('now') WHERE id=?")) {
            ps.setDouble(1, p);
            ps.setInt(2, book.getId());
            ps.executeUpdate();
        } catch (Exception e) {
            System.err.println("[Reader] Progress save failed: " + e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void closeAllPopups() {
        for (String id : new String[] { ID_TOC, ID_SEARCH, ID_STYLE }) {
            Node n = readerStack.lookup("#" + id);
            if (n != null)
                readerStack.getChildren().remove(n);
        }
        btnToc.getStyleClass().remove("active");
        btnSearch.getStyleClass().remove("active");
        btnTextStyle.getStyleClass().remove("active");
    }

    private Slider makeSlider(double min, double max, double val) {
        Slider s = new Slider(min, max, val);
        s.getStyleClass().add("reader-style-slider");
        s.setMaxWidth(Double.MAX_VALUE);
        return s;
    }

    private ToggleButton makeAlignBtn(String iconLiteral, String align, ToggleGroup grp) {
        final FontIcon fi = new FontIcon(iconLiteral);
        fi.setIconSize(14);
        fi.setStyle("-fx-icon-color:#AAAAAA;");

        ToggleButton btn = new ToggleButton("", fi);
        btn.setToggleGroup(grp);
        btn.setUserData(align);
        btn.getStyleClass().add("reader-align-btn");

        // Keep icon color in sync with selected state
        btn.selectedProperty()
                .addListener((o, ov, nv) -> fi.setStyle("-fx-icon-color:" + (nv ? "#C0284A" : "#AAAAAA") + ";"));
        return btn;
    }

    private void setWindowTitle(String title) {
        Stage s = stage();
        if (s != null)
            s.setTitle(title);
    }

    private Stage stage() {
        if (readerRoot == null || readerRoot.getScene() == null)
            return null;
        return (Stage) readerRoot.getScene().getWindow();
    }

    private void showError(String msg) {
        leftEngine.loadContent(
                "<html><body style='background:#0D0D0D;color:#FF5555;" +
                        "font-family:sans-serif;padding:40px;font-size:15px;'>" +
                        "<b>Could not open book</b><br><br>" + msg + "</body></html>");
        rightEngine.loadContent(blankPage());
    }

    // ── Public API for Settings tab (future reader theme customization) ────────

    /**
     * Returns the current reader theme.
     * Settings tab can call getTheme(), modify fields, then call setTheme().
     */
    public ReaderTheme getTheme() {
        return theme;
    }

    /**
     * Applies a new reader theme and reloads the current spread.
     * Settings tab calls this when user switches between Dark / Light / Sepia.
     */
    public void setTheme(ReaderTheme t) {
        this.theme = t;
        if (renderer != null)
            loadSpread();
    }
}