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
import javafx.stage.Popup;
import javafx.stage.Stage;
import org.kordamp.ikonli.javafx.FontIcon;

import java.io.File;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ReaderController — paginated EPUB reader using a CSS-column wrapper div
 * with CSS translateX pagination.
 *
 * LAYOUT MODEL (reliable in JavaFX WebKit):
 *
 * <html> — VW x VH, overflow:hidden (the viewport window)
 * <body> — VW x VH, overflow:hidden, no scroll, background only
 * <div id="qp-col-wrap"> — THE column container
 * width : VW px (one spread wide)
 * height : VH px
 * column-count : 2
 * overflow : visible → columns spill to the right
 * transform : translateX(-N*VW px) → pagination
 *
 * WHY translateX instead of scrollLeft / window.scrollTo:
 * JavaFX WebKit does not reliably honour scrollLeft on <html> or <body>
 * when CSS columns are active — the visual position stays at 0.
 * CSS transform: translateX() moves the rendered box directly and works
 * perfectly in every WebKit version.
 *
 * MEASUREMENT:
 * After the column reflow (setTimeout 320 ms) we read wrap.scrollWidth.
 * Because the wrapper has overflow:visible its scrollWidth equals the
 * full horizontal extent of all columns, so:
 * totalSpreads = Math.ceil(scrollWidth / VW)
 */
public class ReaderController {

    // ── FXML ─────────────────────────────────────────────────────────────────
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

    // ── WebView ───────────────────────────────────────────────────────────────
    private WebView webView;
    private WebEngine engine;

    // ── State ─────────────────────────────────────────────────────────────────
    private Book book;
    private EpubRenderer renderer;
    private ReaderTheme theme = new ReaderTheme();
    private int spineIndex = 0;
    private int totalChapters = 0;
    private int currentSpread = 0;
    private int totalSpreads = 1;
    private boolean fullscreen = false;
    private Runnable onBackCallback;

    private final AtomicBoolean turning = new AtomicBoolean(false);
    private final AtomicInteger jumpSpread = new AtomicInteger(0);
    private volatile boolean layoutPending = false;

    /**
     * Solid dark pane shown on top of WebView during chapter load to hide flash.
     */
    private StackPane transitionOverlay;

    private Popup tocPopup;
    private static final String ID_SEARCH = "qp-search-popup";
    private static final String ID_STYLE = "qp-style-popup";

    private static final double COLUMN_GAP = 48.0;

    // ── Init ──────────────────────────────────────────────────────────────────
    @FXML
    public void initialize() {
        buildWebView();
        setupNav();
    }

    // ── Entry point ───────────────────────────────────────────────────────────
    public void openBook(Book book, Runnable onBack) {
        this.book = book;
        this.onBackCallback = onBack;
        lblBookTitle.setText(book.getTitle());
        setWindowTitle(book.getTitle() + " — QuietPages");

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
            double progress = book.getReadingProgress();
            spineIndex = Math.max(0, Math.min(
                    (int) (progress * Math.max(1, totalChapters)), totalChapters - 1));
            jumpSpread.set(0);
            loadChapter(spineIndex, 0);
        }));
        task.setOnFailed(e -> Platform.runLater(
                () -> showError("Could not open: " + task.getException().getMessage())));
        new Thread(task, "epub-loader").start();
    }

    // ── WebView ───────────────────────────────────────────────────────────────
    private void buildWebView() {
        webView = new WebView();
        engine = webView.getEngine();
        webView.setContextMenuEnabled(false);
        webView.setMaxWidth(Double.MAX_VALUE);
        webView.setMaxHeight(Double.MAX_VALUE);
        HBox.setHgrow(webView, Priority.ALWAYS);
        webViewContainer.getChildren().add(webView);

        // Transition overlay — covers chapter-load flash
        transitionOverlay = new StackPane();
        transitionOverlay.setStyle("-fx-background-color: #0D0D0D;");
        transitionOverlay.setVisible(false);
        transitionOverlay.setMouseTransparent(true);

        engine.documentProperty().addListener((obs, oldDoc, newDoc) -> {
            if (newDoc == null)
                return;
            layoutPending = true;
            turning.set(false);
            Platform.runLater(() -> injectLayout(jumpSpread.get()));
        });

        engine.setOnStatusChanged(evt -> {
            String data = evt.getData();
            if (data == null)
                return;
            Platform.runLater(() -> handleStatus(data));
        });

        webView.widthProperty().addListener((obs, ov, nv) -> {
            if (renderer != null && !layoutPending) {
                layoutPending = true;
                Platform.runLater(() -> injectLayout(currentSpread));
            }
        });
        webView.heightProperty().addListener((obs, ov, nv) -> {
            if (renderer != null && !layoutPending) {
                layoutPending = true;
                Platform.runLater(() -> injectLayout(currentSpread));
            }
        });
    }

    private void handleStatus(String data) {
        if (data.startsWith("qp:layout:")) {
            String[] p = data.split(":");
            if (p.length >= 4) {
                try {
                    totalSpreads = Integer.parseInt(p[2]);
                    currentSpread = Integer.parseInt(p[3]);
                } catch (NumberFormatException ignored) {
                }
            }
            layoutPending = false;
            turning.set(false);
            hideTransitionOverlay();
            updateStatusBar();
            persistProgress();

        } else if (data.startsWith("qp:turned:")) {
            try {
                currentSpread = Integer.parseInt(data.split(":")[2]);
            } catch (NumberFormatException ignored) {
            }
            layoutPending = false;
            turning.set(false);
            updateStatusBar();

        } else if ("qp:next-chapter".equals(data)) {
            if (spineIndex + 1 < totalChapters) {
                spineIndex++;
                persistProgress();
                loadChapter(spineIndex, 0);
            } else {
                turning.set(false);
                layoutPending = false;
            }

        } else if ("qp:prev-chapter".equals(data)) {
            if (spineIndex > 0) {
                spineIndex--;
                persistProgress();
                loadChapter(spineIndex, Integer.MAX_VALUE);
            } else {
                turning.set(false);
                layoutPending = false;
            }
        }
    }

    private void setupNav() {
        readerStack.setFocusTraversable(true);
        readerStack.setOnMouseClicked(e -> readerStack.requestFocus());
        readerStack.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.RIGHT || e.getCode() == KeyCode.PAGE_DOWN)
                doNextSpread();
            if (e.getCode() == KeyCode.LEFT || e.getCode() == KeyCode.PAGE_UP)
                doPrevSpread();
        });
        webView.addEventFilter(ScrollEvent.SCROLL, e -> {
            e.consume();
            double d = Math.abs(e.getDeltaY()) >= Math.abs(e.getDeltaX())
                    ? e.getDeltaY()
                    : -e.getDeltaX();
            if (d < 0)
                doNextSpread();
            else
                doPrevSpread();
        });
    }

    // ── Transition overlay ────────────────────────────────────────────────────
    private void showTransitionOverlay() {
        if (!readerStack.getChildren().contains(transitionOverlay)) {
            readerStack.getChildren().add(transitionOverlay);
        }
        transitionOverlay.setOpacity(1.0);
        transitionOverlay.setVisible(true);
    }

    private void hideTransitionOverlay() {
        if (transitionOverlay == null)
            return;
        javafx.animation.FadeTransition ft = new javafx.animation.FadeTransition(javafx.util.Duration.millis(150),
                transitionOverlay);
        ft.setFromValue(1.0);
        ft.setToValue(0.0);
        ft.setOnFinished(e -> transitionOverlay.setVisible(false));
        ft.play();
    }

    // ── Chapter loading ───────────────────────────────────────────────────────
    private void loadChapter(int index, int targetSpread) {
        if (renderer == null)
            return;
        index = Math.max(0, Math.min(index, totalChapters - 1));
        spineIndex = index;
        currentSpread = 0;
        totalSpreads = 1;
        jumpSpread.set(targetSpread);
        turning.set(false);
        layoutPending = true;
        showTransitionOverlay();
        try {
            engine.load(renderer.getChapterUrlWithTheme(index, theme));
        } catch (Exception ex) {
            hideTransitionOverlay();
            showError(ex.getMessage());
        }
    }

    /**
     * Injects the pagination layout into the loaded chapter.
     *
     * Approach: wrap all body children in <div id="qp-col-wrap">, apply
     * column CSS to it, measure its scrollWidth, then use
     * CSS transform:translateX to navigate spreads.
     *
     * This bypasses WebKit's broken scrollLeft-on-root behaviour entirely.
     */
    private void injectLayout(int targetSpread) {
        double vw = webView.getWidth();
        double vh = webView.getHeight();
        if (vw <= 0 || vh <= 0) {
            Platform.runLater(() -> injectLayout(targetSpread));
            return;
        }

        double gap = COLUMN_GAP;
        double padH = theme.marginH;
        double padV = 32.0;
        int sentinel = Integer.MAX_VALUE;

        // %% → literal % inside String.format
        String js = String.format("""
                (function() {
                    var VW = %f, VH = %f, GAP = %f, PAD_H = %f, PAD_V = %f;
                    var TARGET = %d, SENTINEL = %d;

                    var html = document.documentElement;
                    var body = document.body;
                    if (!body) return;

                    /* ── 1. html: viewport clip ── */
                    html.style.cssText = [
                        'margin:0!important',
                        'padding:0!important',
                        'width:'+VW+'px!important',
                        'height:'+VH+'px!important',
                        'overflow:hidden!important'
                    ].join(';');

                    /* ── 2. body: same size, no scroll, background only ── */
                    var bodyBg = window.getComputedStyle(body).backgroundColor || '#0D0D0D';
                    body.style.cssText = [
                        'margin:0!important',
                        'padding:0!important',
                        'width:'+VW+'px!important',
                        'height:'+VH+'px!important',
                        'overflow:hidden!important',
                        'background-color:'+bodyBg+'!important'
                    ].join(';');

                    /* ── 3. Create or reuse the wrapper div ── */
                    var wrap = document.getElementById('qp-col-wrap');
                    if (!wrap) {
                        wrap = document.createElement('div');
                        wrap.id = 'qp-col-wrap';
                        /* Adopt every existing body child into wrap */
                        var kids = Array.prototype.slice.call(body.childNodes);
                        for (var k = 0; k < kids.length; k++) {
                            wrap.appendChild(kids[k]);
                        }
                        body.appendChild(wrap);
                    }

                    /* ── 4. Apply column CSS to the wrapper ──
                       overflow:visible is critical: the columns spill rightward
                       so wrap.scrollWidth reflects the full multi-column extent. */
                    wrap.setAttribute('style', [
                        'box-sizing:border-box!important',
                        'width:'+VW+'px!important',
                        'height:'+VH+'px!important',
                        'padding:'+PAD_V+'px '+PAD_H+'px!important',
                        'column-count:2!important',
                        'column-gap:'+GAP+'px!important',
                        'column-fill:auto!important',
                        '-webkit-column-count:2!important',
                        '-webkit-column-gap:'+GAP+'px!important',
                        '-webkit-column-fill:auto!important',
                        'overflow:visible!important',
                        'transform:translateX(0px)!important',
                        '-webkit-transform:translateX(0px)!important',
                        'will-change:transform!important',
                        'position:relative!important'
                    ].join(';'));

                    /* ── 5. Constrain images so they don't break columns ── */
                    var imgs = wrap.querySelectorAll('img,figure,svg,table');
                    for (var i = 0; i < imgs.length; i++) {
                        var el = imgs[i];
                        el.style.setProperty('-webkit-column-break-inside','avoid','important');
                        el.style.setProperty('break-inside',    'avoid',         'important');
                        el.style.setProperty('page-break-inside','avoid',        'important');
                        el.style.setProperty('max-width',       '100%%',         'important');
                        el.style.setProperty('max-height',      (VH*0.78)+'px', 'important');
                        el.style.setProperty('width',           'auto',          'important');
                        el.style.setProperty('height',          'auto',          'important');
                        el.style.setProperty('object-fit',      'contain',       'important');
                        el.style.setProperty('display',         'block',         'important');
                    }

                    /* ── 6. Wait for WebKit multi-column reflow then measure ──
                       320 ms is intentional: requestAnimationFrame fires before
                       multi-column layout is stable; a short setTimeout is needed. */
                    setTimeout(function() {
                        var sw    = wrap.scrollWidth;
                        var total = Math.max(1, Math.ceil(sw / VW));

                        var landed = (TARGET === SENTINEL)
                            ? total - 1
                            : Math.min(TARGET, total - 1);

                        /* Translate to the target spread */
                        var tx = landed * VW;
                        var tfm = 'translateX(-'+tx+'px)';
                        wrap.style.setProperty('transform',         tfm, 'important');
                        wrap.style.setProperty('-webkit-transform', tfm, 'important');

                        /* Store globals so page-turn JS can update cheaply */
                        window._qpVW           = VW;
                        window._qpTotalSpreads = total;
                        window._qpCurSpread    = landed;

                        /* Report to Java */
                        window.status = 'qp:layout:' + total + ':' + landed;

                    }, 320);
                })();
                """,
                vw, vh, gap, padH, padV, targetSpread, sentinel);

        try {
            engine.executeScript(js);
        } catch (Exception e) {
            System.err.println("[Reader] injectLayout error: " + e.getMessage());
            layoutPending = false;
            hideTransitionOverlay();
        }
    }

    // ── Navigation ────────────────────────────────────────────────────────────
    @FXML
    void onNextPage() {
        doNextSpread();
    }

    @FXML
    void onPrevPage() {
        doPrevSpread();
    }

    /**
     * Advances one spread within the current chapter by updating translateX.
     * If already on the last spread, signals Java to load the next chapter.
     * No page reload — instant, no flicker.
     */
    private void doNextSpread() {
        if (layoutPending)
            return;
        if (!turning.compareAndSet(false, true))
            return;

        try {
            engine.executeScript("""
                    (function() {
                        var cur   = (window._qpCurSpread    !== undefined) ? window._qpCurSpread    : 0;
                        var total = (window._qpTotalSpreads !== undefined) ? window._qpTotalSpreads : 1;
                        var vw    = (window._qpVW           !== undefined) ? window._qpVW           : 800;
                        var wrap  = document.getElementById('qp-col-wrap');
                        if (!wrap) { window.status = 'qp:next-chapter'; return; }
                        if (cur + 1 < total) {
                            var next = cur + 1;
                            window._qpCurSpread = next;
                            var tfm = 'translateX(-' + (next * vw) + 'px)';
                            wrap.style.setProperty('transform',         tfm, 'important');
                            wrap.style.setProperty('-webkit-transform', tfm, 'important');
                            window.status = 'qp:turned:' + next;
                        } else {
                            window.status = 'qp:next-chapter';
                        }
                    })();
                    """);
        } catch (Exception e) {
            turning.set(false);
        }
    }

    /**
     * Goes back one spread within the current chapter.
     * If on spread 0, signals Java to load the previous chapter at its last spread.
     */
    private void doPrevSpread() {
        if (layoutPending)
            return;
        if (!turning.compareAndSet(false, true))
            return;

        try {
            engine.executeScript("""
                    (function() {
                        var cur  = (window._qpCurSpread !== undefined) ? window._qpCurSpread : 0;
                        var vw   = (window._qpVW        !== undefined) ? window._qpVW        : 800;
                        var wrap = document.getElementById('qp-col-wrap');
                        if (!wrap) { window.status = 'qp:prev-chapter'; return; }
                        if (cur > 0) {
                            var prev = cur - 1;
                            window._qpCurSpread = prev;
                            var tfm = 'translateX(-' + (prev * vw) + 'px)';
                            wrap.style.setProperty('transform',         tfm, 'important');
                            wrap.style.setProperty('-webkit-transform', tfm, 'important');
                            window.status = 'qp:turned:' + prev;
                        } else {
                            window.status = 'qp:prev-chapter';
                        }
                    })();
                    """);
        } catch (Exception e) {
            turning.set(false);
        }
    }

    // ── Toolbar ───────────────────────────────────────────────────────────────
    @FXML
    private void onBack() {
        persistProgress();
        if (renderer != null)
            renderer.cleanup();
        setWindowTitle("QuietPages");
        closeAllPopups();
        if (tocPopup != null && tocPopup.isShowing())
            tocPopup.hide();
        if (onBackCallback != null)
            onBackCallback.run();
    }

    @FXML
    private void onToc() {
        if (renderer == null)
            return;
        if (tocPopup != null && tocPopup.isShowing()) {
            tocPopup.hide();
            btnToc.getStyleClass().remove("active");
            return;
        }
        showTocPopup(renderer.getToc());
        btnToc.getStyleClass().add("active");
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
        iconFullscreen.setIconLiteral(fullscreen ? "fas-compress" : "fas-expand");
        Platform.runLater(() -> {
            layoutPending = true;
            injectLayout(currentSpread);
        });
    }

    // ── TOC popup ─────────────────────────────────────────────────────────────
    private void showTocPopup(List<TocEntry> tocList) {
        VBox content = new VBox(0);
        content.setStyle("""
                -fx-background-color: #1C1C1C;
                -fx-border-color: #303030;
                -fx-border-width: 1;
                -fx-border-radius: 8;
                -fx-background-radius: 8;
                -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.85), 24, 0, 0, 8);
                """);
        content.setPrefWidth(300);
        content.setMaxHeight(460);

        Label header = new Label("Table of Contents");
        header.setStyle("""
                -fx-text-fill: #DDDDDD; -fx-font-size: 13px; -fx-font-weight: bold;
                -fx-padding: 12 16 11 16;
                -fx-border-color: transparent transparent #303030 transparent;
                -fx-border-width: 0 0 1 0;
                -fx-background-color: #232323; -fx-background-radius: 8 8 0 0;
                """);
        header.setMaxWidth(Double.MAX_VALUE);

        VBox list = new VBox(0);
        populateTocList(list, tocList, 0);

        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent; -fx-border-color: transparent;");
        scroll.getStylesheets().add(
                getClass().getResource("/com/quietpages/quietpages/library.css").toExternalForm());
        VBox.setVgrow(scroll, Priority.ALWAYS);
        content.getChildren().addAll(header, scroll);

        tocPopup = new Popup();
        tocPopup.getContent().add(content);
        tocPopup.setAutoHide(true);
        tocPopup.setOnHidden(e -> btnToc.getStyleClass().remove("active"));

        javafx.geometry.Bounds b = btnToc.localToScreen(btnToc.getBoundsInLocal());
        if (b != null)
            tocPopup.show(stage(), b.getMinX(), b.getMaxY() + 4);
        else
            tocPopup.show(stage());
    }

    private void populateTocList(VBox list, List<TocEntry> entries, int depth) {
        for (TocEntry entry : entries) {
            double lp = 16 + depth * 16.0;
            Label row = new Label(entry.title);
            row.setMaxWidth(Double.MAX_VALUE);
            row.setWrapText(false);
            row.setStyle(String.format(
                    "-fx-text-fill:%s;-fx-font-size:%s;-fx-padding:9 14 9 %.0fpx;" +
                            "-fx-cursor:hand;-fx-background-color:transparent;",
                    depth == 0 ? "#CCCCCC" : "#888888",
                    depth == 0 ? "13px" : "12px", lp));
            row.setOnMouseEntered(e -> row.setStyle(row.getStyle()
                    .replace("-fx-background-color:transparent;",
                            "-fx-background-color:rgba(255,255,255,0.06);")));
            row.setOnMouseExited(e -> row.setStyle(row.getStyle()
                    .replace("-fx-background-color:rgba(255,255,255,0.06);",
                            "-fx-background-color:transparent;")));
            final TocEntry fe = entry;
            row.setOnMouseClicked(e -> {
                if (tocPopup != null)
                    tocPopup.hide();
                navigateToTocEntry(fe);
            });
            list.getChildren().add(row);
            if (!entry.children.isEmpty())
                populateTocList(list, entry.children, depth + 1);
        }
    }

    private void navigateToTocEntry(TocEntry entry) {
        if (entry.resolvedUrl == null || entry.resolvedUrl.isBlank())
            return;
        int idx = renderer.findSpineIndex(entry.resolvedUrl.split("#")[0]);
        loadChapter(idx, 0);
    }

    // ── Search popup ──────────────────────────────────────────────────────────
    private void showSearchPopup() {
        VBox popup = new VBox(8);
        popup.setId(ID_SEARCH);
        popup.getStyleClass().add("reader-popup");
        popup.setPadding(new Insets(12, 14, 12, 14));
        popup.setPrefWidth(340);
        popup.setMaxWidth(340);

        TextField field = new TextField();
        field.setPromptText("Find in chapter...");
        field.getStyleClass().add("reader-search-field");
        field.setMaxWidth(Double.MAX_VALUE);

        ToggleButton btnMC = new ToggleButton("Aa");
        ToggleButton btnWW = new ToggleButton("Ab|");
        btnMC.getStyleClass().add("reader-search-toggle");
        btnWW.getStyleClass().add("reader-search-toggle");

        FontIcon gi = new FontIcon("fas-arrow-right");
        gi.setIconSize(12);
        Button btnGo = new Button("", gi);
        btnGo.getStyleClass().add("reader-search-icon-btn");

        FontIcon ci = new FontIcon("fas-times");
        ci.setIconSize(12);
        Button btnClose = new Button("", ci);
        btnClose.getStyleClass().add("reader-search-icon-btn");
        btnClose.setOnAction(e -> {
            readerStack.getChildren().remove(popup);
            btnSearch.getStyleClass().remove("active");
            clearSearchHighlights();
        });

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox row2 = new HBox(6, btnMC, btnWW, sp, btnGo, btnClose);
        row2.setAlignment(Pos.CENTER_LEFT);

        Label resultLbl = new Label();
        resultLbl.setStyle("-fx-font-size:11px;");
        popup.getChildren().addAll(field, row2, resultLbl);
        StackPane.setAlignment(popup, Pos.TOP_RIGHT);
        StackPane.setMargin(popup, new Insets(60, 8, 0, 0));
        readerStack.getChildren().add(popup);
        Platform.runLater(field::requestFocus);

        Runnable search = () -> {
            String term = field.getText().trim();
            if (term.isBlank())
                return;
            String safe = term.replace("\\", "\\\\").replace("'", "\\'");
            try {
                boolean found = Boolean.parseBoolean(String.valueOf(engine.executeScript(
                        "window.find('" + safe + "'," + btnMC.isSelected()
                                + ",false,true," + btnWW.isSelected() + ")")));
                resultLbl.setText(found ? "✓ Match found" : "✗ No matches");
                resultLbl.getStyleClass().setAll(found
                        ? "reader-search-result-found"
                        : "reader-search-result-none");
            } catch (Exception ex) {
                resultLbl.setText("Search error");
            }
        };
        btnGo.setOnAction(e -> search.run());
        field.setOnAction(e -> search.run());
    }

    private void clearSearchHighlights() {
        try {
            engine.executeScript("window.getSelection().removeAllRanges()");
        } catch (Exception ignored) {
        }
    }

    // ── Text Style popup ──────────────────────────────────────────────────────
    private void showTextStylePopup() {
        VBox popup = new VBox(10);
        popup.setId(ID_STYLE);
        popup.getStyleClass().add("reader-popup");
        popup.setPadding(new Insets(16));
        popup.setPrefWidth(300);
        popup.setMaxWidth(300);

        Label ll = mkLbl("Line spacing");
        Slider sl = mkSlider(1.2, 3.0, theme.lineHeight);
        Label lf = mkLbl("Font size");
        Slider sf = mkSlider(12, 28, theme.fontSize);
        Label lp = mkLbl("Para spacing");
        Slider sp = mkSlider(0, 3.0, theme.paragraphSpace);
        Label lm = mkLbl("Page margin");
        Slider sm = mkSlider(16, 80, theme.marginH);

        Label la = mkLbl("Alignment");
        ToggleGroup ag = new ToggleGroup();
        ToggleButton taL = mkAlignBtn("fas-align-left", "left", ag);
        ToggleButton taC = mkAlignBtn("fas-align-center", "center", ag);
        ToggleButton taJ = mkAlignBtn("fas-align-justify", "justify", ag);
        ToggleButton taR = mkAlignBtn("fas-align-right", "right", ag);
        ag.getToggles().stream().filter(t -> theme.textAlign.equals(t.getUserData()))
                .findFirst().ifPresent(ag::selectToggle);
        HBox ar = new HBox(6, taL, taC, taJ, taR);
        ar.setAlignment(Pos.CENTER_LEFT);

        Button btnReset = new Button("↺  Reset");
        btnReset.getStyleClass().add("reader-reset-btn");
        btnReset.setOnAction(e -> {
            ReaderTheme d = new ReaderTheme();
            theme.lineHeight = d.lineHeight;
            theme.fontSize = d.fontSize;
            theme.paragraphSpace = d.paragraphSpace;
            theme.textAlign = d.textAlign;
            theme.marginH = d.marginH;
            sl.setValue(d.lineHeight);
            sf.setValue(d.fontSize);
            sp.setValue(d.paragraphSpace);
            sm.setValue(d.marginH);
            ag.getToggles().stream().filter(t -> "justify".equals(t.getUserData()))
                    .findFirst().ifPresent(ag::selectToggle);
            reloadCurrentChapter();
        });

        FontIcon ci2 = new FontIcon("fas-times");
        ci2.setIconSize(11);
        Button btnClose = new Button("", ci2);
        btnClose.getStyleClass().add("reader-search-icon-btn");
        btnClose.setOnAction(e -> {
            readerStack.getChildren().remove(popup);
            btnTextStyle.getStyleClass().remove("active");
        });
        Region rx = new Region();
        HBox.setHgrow(rx, Priority.ALWAYS);
        HBox br = new HBox(8, btnReset, rx, btnClose);
        br.setAlignment(Pos.CENTER_LEFT);
        popup.getChildren().addAll(ll, sl, lf, sf, lp, sp, lm, sm, la, ar, br);
        StackPane.setAlignment(popup, Pos.TOP_RIGHT);
        StackPane.setMargin(popup, new Insets(60, 8, 0, 0));
        readerStack.getChildren().add(popup);

        sl.valueProperty().addListener((o, ov, nv) -> {
            theme.lineHeight = nv.doubleValue();
            reloadCurrentChapter();
        });
        sf.valueProperty().addListener((o, ov, nv) -> {
            theme.fontSize = nv.doubleValue();
            reloadCurrentChapter();
        });
        sp.valueProperty().addListener((o, ov, nv) -> {
            theme.paragraphSpace = nv.doubleValue();
            reloadCurrentChapter();
        });
        sm.valueProperty().addListener((o, ov, nv) -> {
            theme.marginH = nv.doubleValue();
            reloadCurrentChapter();
        });
        ag.selectedToggleProperty().addListener((o, ov, nv) -> {
            if (nv != null) {
                theme.textAlign = (String) nv.getUserData();
                reloadCurrentChapter();
            }
        });
    }

    private void reloadCurrentChapter() {
        layoutPending = true;
        loadChapter(spineIndex, currentSpread);
    }

    // ── Status bar ────────────────────────────────────────────────────────────
    private void updateStatusBar() {
        if (renderer == null)
            return;
        String chapName = chapNameFor(spineIndex);
        String title = renderer.getBookTitle();
        String crumb = (chapName.isEmpty() || chapName.equalsIgnoreCase(title))
                ? title
                : title + "  ›  " + chapName;
        crumb += "     " + (currentSpread + 1) + " / " + totalSpreads;
        lblBreadcrumb.setText(crumb);

        double pct = totalChapters > 0
                ? ((spineIndex + (totalSpreads > 1 ? (double) currentSpread / totalSpreads : 0.0))
                        / totalChapters) * 100.0
                : 0.0;
        lblProgress.setText(String.format("%.1f%%", pct));
    }

    private String chapNameFor(int idx) {
        if (renderer == null)
            return "";
        String url = renderer.getChapterUrl(idx);
        if (url == null)
            return "";
        String base = url.split("#")[0];
        for (TocEntry e : renderer.getToc())
            if (e.resolvedUrl != null && e.resolvedUrl.split("#")[0].equals(base))
                return e.title;
        return "";
    }

    // ── Progress ──────────────────────────────────────────────────────────────
    private void persistProgress() {
        if (book == null || totalChapters == 0)
            return;
        double p = (double) spineIndex / totalChapters;
        book.setReadingProgress(p);
        try (PreparedStatement ps = DatabaseManager.getInstance().getConnection()
                .prepareStatement("UPDATE books SET reading_progress=?, reading_status='READING'," +
                        " last_read=datetime('now') WHERE id=?")) {
            ps.setDouble(1, p);
            ps.setInt(2, book.getId());
            ps.executeUpdate();
        } catch (Exception e) {
            System.err.println("[Reader] progress: " + e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private void closeAllPopups() {
        for (String id : new String[] { ID_SEARCH, ID_STYLE }) {
            Node n = readerStack.lookup("#" + id);
            if (n != null)
                readerStack.getChildren().remove(n);
        }
        btnSearch.getStyleClass().remove("active");
        btnTextStyle.getStyleClass().remove("active");
    }

    private Slider mkSlider(double min, double max, double val) {
        Slider s = new Slider(min, max, val);
        s.getStyleClass().add("reader-style-slider");
        s.setMaxWidth(Double.MAX_VALUE);
        s.setShowTickMarks(false);
        s.setShowTickLabels(false);
        return s;
    }

    private Label mkLbl(String t) {
        Label l = new Label(t);
        l.getStyleClass().add("reader-popup-label");
        return l;
    }

    private ToggleButton mkAlignBtn(String icon, String align, ToggleGroup grp) {
        FontIcon fi = new FontIcon(icon);
        fi.setIconSize(13);
        fi.setStyle("-fx-icon-color:#777777;");
        ToggleButton btn = new ToggleButton("", fi);
        btn.setToggleGroup(grp);
        btn.setUserData(align);
        btn.getStyleClass().add("reader-align-btn");
        btn.selectedProperty()
                .addListener((o, ov, nv) -> fi.setStyle("-fx-icon-color:" + (nv ? "#C0284A" : "#777777") + ";"));
        return btn;
    }

    private void setWindowTitle(String t) {
        Stage s = stage();
        if (s != null)
            s.setTitle(t);
    }

    private Stage stage() {
        if (readerRoot == null || readerRoot.getScene() == null)
            return null;
        return (Stage) readerRoot.getScene().getWindow();
    }

    private void showError(String msg) {
        engine.loadContent("<html><body style='background:#0D0D0D;color:#FF6666;" +
                "font-family:sans-serif;padding:48px;font-size:15px;'>" +
                "<b>Could not open book</b><br><br>" + msg + "</body></html>", "text/html");
    }

    // ── Public API ────────────────────────────────────────────────────────────
    public ReaderTheme getTheme() {
        return theme;
    }

    public void setTheme(ReaderTheme t) {
        this.theme = t;
        if (renderer != null)
            reloadCurrentChapter();
    }
}