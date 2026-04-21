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

/**
 * ReaderController — JS-driven manual pagination. No CSS columns.
 *
 * ── WHY CSS COLUMNS FAIL IN JAVAFX WEBKIT ────────────────────────────────────
 *
 * JavaFX embeds an old WebKit (roughly Safari 8-era).
 * In this version:
 * • scrollLeft on a column-count:2 element always returns 0 — no-op.
 * • translateX on overflow:visible leaks the 3rd column into the viewport.
 * • translateX on overflow:hidden clips correctly BUT scrollWidth reports
 * clientWidth (i.e. it cannot see past the clip) so we cannot count pages.
 * Every CSS-column approach hits one of these three bugs.
 *
 * ── THE SOLUTION: JS MANUAL PAGINATION ───────────────────────────────────────
 *
 * We abandon CSS columns entirely and do pagination in JavaScript:
 *
 * 1. Reset the document to a normal single-column flow inside a fixed-width,
 * fixed-height container (pageW = VW/2, pageH = VH).
 *
 * 2. After reflow, iterate every direct child of <body>. Read each element's
 * offsetTop + offsetHeight. Assign each element to a PAGE number:
 * page = floor(element.offsetTop / pageH)
 * If an element straddles a boundary (offsetTop < page*pageH but
 * offsetTop+offsetHeight > page*pageH) it goes on the earlier page.
 *
 * 3. Wrap each page's elements in a <div class="qp-page"> absolutely
 * positioned at left = page * pageW (for even pages = left side of spread)
 * or left = pageW + (page-1)*pageW for odd pages (right side of spread).
 * Actually simpler: place page P at left = P * pageW, top = 0,
 * width = pageW, height = pageH, overflow = hidden.
 *
 * 4. A "spread" shows page 2N (left) and page 2N+1 (right) simultaneously.
 * The outer container is VW wide, overflow:hidden.
 * To show spread S, set container.scrollLeft = S * VW.
 * But since each .qp-page is absolutely positioned at left=P*pageW,
 * the whole layout is already a horizontal strip — we just clip it.
 *
 * 5. Navigation: advance spread index, update which pages are visible.
 * No reflow. Instant.
 *
 * This is 100% immune to the CSS column bugs because we never use
 * column-count, column-gap, or column-fill.
 *
 * ── SPREAD / PAGE RELATIONSHIP ───────────────────────────────────────────────
 *
 * pageW = VW / 2 (half the WebView width)
 * pageH = VH (full WebView height)
 * spread S shows pages 2S and 2S+1
 * totalPages = number of .qp-page divs created
 * totalSpreads = ceil(totalPages / 2)
 *
 * ── CHAPTER BOUNDARIES ───────────────────────────────────────────────────────
 *
 * Next at last spread → Java loads next chapter at spread 0.
 * Prev at spread 0 → Java loads prev chapter at LAST_SPREAD
 * (resolved to totalSpreads-1 after pagination).
 */
public class ReaderController {

    // ── FXML ──────────────────────────────────────────────────────────────────
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
    private Button btnTheme;
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

    private static final int LAST_SPREAD = Integer.MAX_VALUE;
    private int pendingSpread = 0;

    private final AtomicBoolean busy = new AtomicBoolean(false);

    /** Debounce timer for resize/fullscreen re-pagination. */
    private java.util.Timer resizeDebounceTimer = null;

    private StackPane transitionOverlay;
    private Popup tocPopup;
    private Popup searchPopup;
    private Popup stylePopup;
    private Popup themePopup;
    private static final String ID_SEARCH = "qp-search-popup";
    private static final String ID_STYLE = "qp-style-popup";
    private static final String ID_THEME = "qp-theme-popup";

    // Reader colour theme names (match EpubRenderer.ReaderTheme bgColor choices)
    private String readerColorTheme = "dark";

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
            double saved = book.getReadingProgress();
            spineIndex = Math.max(0, Math.min(
                    (int) (saved * Math.max(1, totalChapters)), totalChapters - 1));
            wireKeyboardNav();
            loadChapter(spineIndex, 0);
        }));
        task.setOnFailed(e -> Platform.runLater(
                () -> showError("Could not open: " + task.getException().getMessage())));
        new Thread(task, "epub-loader").start();
    }

    /**
     * Wire keyboard arrow keys using an event FILTER on the scene.
     * A filter fires during the capture phase — before WebView (or any
     * focused node) gets a chance to consume the event. Using setOnKeyPressed
     * (a handler) fires too late: WebView swallows the event first.
     */
    private void wireKeyboardNav() {
        javafx.scene.Scene scene = readerRoot.getScene();
        if (scene != null) {
            scene.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
                if (e.getCode() == KeyCode.RIGHT || e.getCode() == KeyCode.PAGE_DOWN) {
                    doNext();
                    e.consume();
                } else if (e.getCode() == KeyCode.LEFT || e.getCode() == KeyCode.PAGE_UP) {
                    doPrev();
                    e.consume();
                }
            });
        } else {
            // Scene not yet attached — wait for it
            readerRoot.sceneProperty().addListener((obs, ov, nv) -> {
                if (nv != null)
                    wireKeyboardNav();
            });
        }
    }

    // ── Build WebView ─────────────────────────────────────────────────────────
    private void buildWebView() {
        webView = new WebView();
        engine = webView.getEngine();
        webView.setContextMenuEnabled(false);
        webView.setMaxWidth(Double.MAX_VALUE);
        webView.setMaxHeight(Double.MAX_VALUE);
        HBox.setHgrow(webView, Priority.ALWAYS);
        webViewContainer.getChildren().add(webView);

        transitionOverlay = new StackPane();
        transitionOverlay.setStyle("-fx-background-color:#0D0D0D;");
        transitionOverlay.setVisible(false);
        transitionOverlay.setMouseTransparent(true);

        // Document ready → run pagination
        engine.documentProperty().addListener((obs, oldDoc, newDoc) -> {
            if (newDoc == null)
                return;
            busy.set(false);
            // Short delay to let WebKit finish its initial layout pass
            Platform.runLater(() -> runPagination(pendingSpread));
        });

        // JS → Java messages
        engine.setOnStatusChanged(evt -> {
            if (evt.getData() != null)
                Platform.runLater(() -> handleStatus(evt.getData()));
        });

        // Re-paginate on resize or fullscreen — debounced so rapid events
        // (fullscreen fires width+height in quick succession) coalesce into one call.
        webViewContainer.widthProperty().addListener((obs, ov, nv) -> {
            if (renderer != null && nv.doubleValue() > 10)
                scheduleRepaginate();
        });
        webViewContainer.heightProperty().addListener((obs, ov, nv) -> {
            if (renderer != null && nv.doubleValue() > 10)
                scheduleRepaginate();
        });
    }

    // ── Status handler ────────────────────────────────────────────────────────
    private void handleStatus(String msg) {
        if (msg.startsWith("qp:ready:")) {
            String[] p = msg.split(":");
            if (p.length >= 4) {
                try {
                    totalSpreads = Integer.parseInt(p[2]);
                    currentSpread = Integer.parseInt(p[3]);
                } catch (NumberFormatException ignored) {
                }
            }
            busy.set(false);
            hideTransitionOverlay();
            updateStatusBar();
            persistProgress();

        } else if (msg.startsWith("qp:turned:")) {
            try {
                currentSpread = Integer.parseInt(msg.split(":")[2]);
            } catch (NumberFormatException ignored) {
            }
            busy.set(false);
            updateStatusBar();

        } else if ("qp:next-chapter".equals(msg)) {
            if (spineIndex + 1 < totalChapters) {
                spineIndex++;
                persistProgress();
                loadChapter(spineIndex, 0);
            } else {
                busy.set(false);
            }

        } else if ("qp:prev-chapter".equals(msg)) {
            if (spineIndex > 0) {
                spineIndex--;
                persistProgress();
                loadChapter(spineIndex, LAST_SPREAD);
            } else {
                busy.set(false);
            }
        }
    }

    // ── Nav setup ─────────────────────────────────────────────────────────────
    private void setupNav() {
        readerStack.setFocusTraversable(true);
        readerRoot.setFocusTraversable(true);
        readerStack.setOnMouseClicked(e -> readerRoot.requestFocus());
        readerRoot.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.RIGHT || e.getCode() == KeyCode.PAGE_DOWN) {
                doNext();
                e.consume();
            } else if (e.getCode() == KeyCode.LEFT || e.getCode() == KeyCode.PAGE_UP) {
                doPrev();
                e.consume();
            }
        });
        readerStack.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.RIGHT || e.getCode() == KeyCode.PAGE_DOWN) {
                doNext();
                e.consume();
            } else if (e.getCode() == KeyCode.LEFT || e.getCode() == KeyCode.PAGE_UP) {
                doPrev();
                e.consume();
            }
        });
        webView.addEventFilter(ScrollEvent.SCROLL, e -> {
            e.consume();
            double d = Math.abs(e.getDeltaY()) >= Math.abs(e.getDeltaX())
                    ? e.getDeltaY()
                    : -e.getDeltaX();
            if (d < 0)
                doNext();
            else
                doPrev();
        });
    }

    // ── Overlay ───────────────────────────────────────────────────────────────
    private void showTransitionOverlay() {
        if (!readerStack.getChildren().contains(transitionOverlay))
            readerStack.getChildren().add(transitionOverlay);
        transitionOverlay.setOpacity(1.0);
        transitionOverlay.setVisible(true);
    }

    private void hideTransitionOverlay() {
        if (transitionOverlay == null)
            return;
        javafx.animation.FadeTransition ft = new javafx.animation.FadeTransition(
                javafx.util.Duration.millis(150), transitionOverlay);
        ft.setFromValue(1.0);
        ft.setToValue(0.0);
        ft.setOnFinished(e -> transitionOverlay.setVisible(false));
        ft.play();
    }

    // ── Chapter load ──────────────────────────────────────────────────────────
    private void loadChapter(int index, int targetSpread) {
        if (renderer == null)
            return;
        index = Math.max(0, Math.min(index, totalChapters - 1));
        spineIndex = index;
        currentSpread = 0;
        totalSpreads = 1;
        pendingSpread = targetSpread;
        busy.set(false);
        showTransitionOverlay();
        try {
            engine.load(renderer.getChapterUrlWithTheme(index, theme));
        } catch (Exception ex) {
            hideTransitionOverlay();
            showError(ex.getMessage());
        }
    }

    // ── Debounced re-paginate (used by resize / fullscreen listeners) ─────────
    /**
     * Cancels any pending re-pagination and schedules a new one 250 ms later.
     * This collapses the burst of width+height events that fullscreen toggle
     * produces into a single repagination call once the size has settled.
     */
    private void scheduleRepaginate() {
        if (resizeDebounceTimer != null) {
            resizeDebounceTimer.cancel();
        }
        resizeDebounceTimer = new java.util.Timer(true);
        resizeDebounceTimer.schedule(new java.util.TimerTask() {
            @Override
            public void run() {
                Platform.runLater(() -> {
                    busy.set(false);
                    // Reload from original HTML — the DOM is already restructured
                    // from the previous pagination run so we cannot re-paginate
                    // in place. A chapter reload gives clean HTML at the new size.
                    loadChapter(spineIndex, currentSpread);
                });
            }
        }, 350);
    }

    // ── Core: element-grouping pagination ────────────────────────────────────
    /**
     * APPROACH: physically move every block element into its own .qp-page div.
     * No clipping of a scrolling column. No coordinate math. No bleed possible.
     */
    private void runPagination(int targetSpread) {
        double vw = webViewContainer.getWidth();
        double vh = webViewContainer.getHeight();
        if (vw < 10 || vh < 10) {
            Platform.runLater(() -> runPagination(targetSpread));
            return;
        }

        double pageW = vw / 2.0;
        double padH = theme.marginH;
        double padV = 44.0;
        double availH = vh - padV * 2.0;
        // packH: packing budget, slightly less than availH so last line
        // always has bottom clearance and is never half-clipped.
        double lineBuffer = theme.fontSize * theme.lineHeight + 4.0;
        double packH = availH - lineBuffer;

        String js = String.format("""
                (function() {
                    var VW=%f, VH=%f, PW=%f, PAD_H=%f, PAD_V=%f, AVAIL_H=%f, PACK_H=%f;
                    var TARGET=%d, LAST=%d;
                    var body = document.body;
                    if (!body) { window.status='qp:ready:1:0'; return; }

                    /* ── PHASE 0: tear down previous run ── */
                    var oldWrap = document.getElementById('qp-wrap');
                    if (oldWrap) oldWrap.parentNode.removeChild(oldWrap);

                    /* ── PHASE 1: single-column reflow at half-viewport width ── */
                    var innerW = PW - PAD_H * 2;
                    document.documentElement.style.cssText =
                        'margin:0;padding:0;width:'+PW+'px;height:auto;overflow:visible;';
                    body.style.cssText =
                        'margin:0;padding:0;box-sizing:border-box;' +
                        'width:'+innerW+'px;height:auto;overflow:visible;' +
                        'margin-left:'+PAD_H+'px;';

                    /* Constrain media to fit one page */
                    var allMedia = body.querySelectorAll('img,svg,video,figure,table');
                    for (var m = 0; m < allMedia.length; m++) {
                        var el = allMedia[m];
                        el.style.setProperty('max-width',  innerW+'px',         'important');
                        el.style.setProperty('max-height', (AVAIL_H*0.85)+'px', 'important');
                        el.style.setProperty('width',      'auto',               'important');
                        el.style.setProperty('height',     'auto',               'important');
                        el.style.setProperty('object-fit', 'contain',            'important');
                        el.style.setProperty('display',    'block',              'important');
                    }

                    /* ── PHASE 2: wait for reflow ── */
                    setTimeout(function() {

                        /* ────────────────────────────────────────────────────────────
                           STEP A: Collect block units from the full document tree.
                           We recurse into wrapper divs/sections to find real content
                           blocks (p, h1-h6, li, blockquote, img, figure, hr, pre).
                           ──────────────────────────────────────────────────────────── */
                        var ATOMIC = {IMG:1,SVG:1,FIGURE:1,HR:1,TABLE:1,PRE:1,OL:1,UL:1};
                        var BLOCK  = {P:1,H1:1,H2:1,H3:1,H4:1,H5:1,H6:1,
                                      LI:1,BLOCKQUOTE:1,FIGCAPTION:1};
                        var blocks = [];

                        function collectBlocks(node) {
                            if (node.nodeType !== 1) return;
                            var tag = node.tagName;
                            if (ATOMIC[tag] || BLOCK[tag]) {
                                if (node.getBoundingClientRect().height > 0)
                                    blocks.push(node);
                                return;
                            }
                            var kids = node.childNodes;
                            for (var i = 0; i < kids.length; i++) collectBlocks(kids[i]);
                        }
                        var bodyKids = body.childNodes;
                        for (var i = 0; i < bodyKids.length; i++) collectBlocks(bodyKids[i]);

                        if (blocks.length === 0) {
                            window.status = 'qp:ready:1:0'; return;
                        }

                        /* ────────────────────────────────────────────────────────────
                           STEP B: Split any block taller than AVAIL_H at word
                           boundaries using Range.getClientRects().

                           For each text node inside the block we iterate word by word,
                           using a Range to measure where each word ends in viewport Y.
                           When the next word would push past the current page's bottom,
                           we cut the text node there: leave the words up-to-cut in a
                           clone of the original element, and continue with the rest in
                           a new clone appended after it in the DOM.

                           After splitting, the block is replaced by 2+ shorter blocks
                           each <= AVAIL_H, all still in the DOM for measurement.
                           ──────────────────────────────────────────────────────────── */

                        function splitBlock(el, pageTop, pageBot) {
                            /* Walk all text nodes inside el. Find the last word that
                               fits within pageBot. Split at that position.
                               Returns the remainder element (or null if all fits). */
                            var range = document.createRange();
                            var textNodes = [];
                            function gatherText(n) {
                                if (n.nodeType === 3 && n.nodeValue.trim().length > 0)
                                    textNodes.push(n);
                                else if (n.nodeType === 1) {
                                    var kids = n.childNodes;
                                    for (var i = 0; i < kids.length; i++) gatherText(kids[i]);
                                }
                            }
                            gatherText(el);
                            if (textNodes.length === 0) return null;

                            var splitNode = null, splitOffset = 0;

                            outer:
                            for (var t = 0; t < textNodes.length; t++) {
                                var tn = textNodes[t];
                                var words = tn.nodeValue.split('');  /* char by char */
                                /* Find spaces to use as split points */
                                var len = tn.nodeValue.length;
                                for (var c = 0; c < len; c++) {
                                    range.setStart(tn, 0);
                                    range.setEnd(tn, c + 1);
                                    var rects = range.getClientRects();
                                    if (rects.length === 0) continue;
                                    var lastRect = rects[rects.length - 1];
                                    if (lastRect.bottom > pageBot) {
                                        /* Find previous space character to split cleanly */
                                        var cutAt = c;
                                        while (cutAt > 0 && tn.nodeValue[cutAt] !== ' ') cutAt--;
                                        if (cutAt === 0) cutAt = c; /* no space found, cut here */
                                        splitNode   = tn;
                                        splitOffset = cutAt;
                                        break outer;
                                    }
                                }
                            }

                            if (splitNode === null) return null; /* everything fits */

                            /* Clone the element, split at the found position */
                            var before = el.cloneNode(true);
                            var after  = el.cloneNode(true);

                            /* Rebuild text content: 'before' keeps up-to splitOffset,
                               'after' keeps the rest.
                               Simpler approach: use Range.extractContents / insertNode */
                            range.setStart(splitNode, splitOffset);
                            range.setEnd(el, el.childNodes.length > 0 ?
                                el.childNodes.length : 0);

                            /* Walk and truncate: delete from splitOffset onward in 'el' */
                            /* We do this by rebuilding using innerHTML split */
                            /* Simplest reliable approach for old WebKit: */
                            range.setStart(splitNode, splitOffset);
                            range.setEndAfter(el.lastChild || el);
                            var extracted = range.extractContents();  /* mutates el */

                            /* 'el' now contains content up to the cut point */
                            /* Create sibling with the extracted remainder */
                            var remainder = el.cloneNode(false);  /* clone tag + attrs, no children */
                            remainder.appendChild(extracted);
                            el.parentNode.insertBefore(remainder, el.nextSibling);
                            return remainder;
                        }

                        /* Apply splitting: for each block taller than AVAIL_H,
                           split repeatedly until all pieces fit */
                        var finalBlocks = [];
                        for (var i = 0; i < blocks.length; i++) {
                            var bl = blocks[i];
                            var r  = bl.getBoundingClientRect();
                            if (r.height <= AVAIL_H) {
                                finalBlocks.push(bl);
                                continue;
                            }
                            /* Block is taller than one page — split it */
                            var pageTop = r.top;
                            var piece = bl;
                            var safety = 0;
                            while (piece && safety < 50) {
                                safety++;
                                var pr = piece.getBoundingClientRect();
                                var pageBot = pageTop + AVAIL_H;
                                if (pr.bottom <= pageBot) {
                                    finalBlocks.push(piece);
                                    break;
                                }
                                var remainder = splitBlock(piece, pageTop, pageBot);
                                finalBlocks.push(piece);
                                piece = remainder;
                                if (piece) {
                                    var nr = piece.getBoundingClientRect();
                                    pageTop = nr.top;
                                }
                            }
                            if (piece && safety >= 50) finalBlocks.push(piece);
                        }

                        /* ────────────────────────────────────────────────────────────
                           STEP C: Assign finalBlocks to pages.
                           Now every block fits within AVAIL_H, so we just use
                           getBoundingClientRect().top to determine which page it
                           belongs to. Accumulate heights to track page boundaries.
                           ──────────────────────────────────────────────────────────── */
                        var pageGroups = [];
                        var curPage = 0;
                        var usedH   = 0;     /* height used so far on curPage */

                        for (var i = 0; i < finalBlocks.length; i++) {
                            var fb = finalBlocks[i];
                            var r  = fb.getBoundingClientRect();
                            var h  = r.height;

                            /* If this block doesn't fit on curPage, start next page */
                            if (usedH > 0 && usedH + h > PACK_H) {
                                curPage++;
                                usedH = 0;
                            }
                            while (pageGroups.length <= curPage) pageGroups.push([]);
                            pageGroups[curPage].push(fb);
                            usedH += h;
                        }

                        /* ── PHASE 3: build page divs and install wrapper ── */
                        var totalPages = pageGroups.length;
                        if (totalPages === 0) totalPages = 1;
                        if (totalPages %% 2 !== 0) totalPages++;

                        var wrap = document.createElement('div');
                        wrap.id = 'qp-wrap';
                        wrap.style.cssText = [
                            'position:fixed','top:0','left:0',
                            'width:'+VW+'px','height:'+VH+'px',
                            'overflow:hidden','background:inherit'
                        ].join(';');

                        for (var p = 0; p < totalPages; p++) {
                            var isLeft  = (p %% 2 === 0);
                            var pageDiv = document.createElement('div');
                            pageDiv.className = 'qp-page';
                            pageDiv.setAttribute('data-page', p);
                            pageDiv.style.cssText = [
                                'position:absolute',
                                'top:'+PAD_V+'px',
                                'left:'+(isLeft ? PAD_H : (PW+PAD_H))+'px',
                                'width:'+innerW+'px',
                                'height:'+AVAIL_H+'px',
                                'overflow:hidden',
                                'display:none',
                                'padding:0','margin:0','box-sizing:border-box'
                            ].join(';');

                            var elems = pageGroups[p] || [];
                            for (var e = 0; e < elems.length; e++) {
                                var elem = elems[e];
                                elem.style.removeProperty('position');
                                elem.style.removeProperty('top');
                                elem.style.removeProperty('left');
                                pageDiv.appendChild(elem);
                            }
                            wrap.appendChild(pageDiv);
                        }

                        body.style.cssText = [
                            'margin:0!important','padding:0!important',
                            'width:'+VW+'px!important','height:'+VH+'px!important',
                            'overflow:hidden!important'
                        ].join(';');
                        document.documentElement.style.cssText = [
                            'margin:0!important','padding:0!important',
                            'width:'+VW+'px!important','height:'+VH+'px!important',
                            'overflow:hidden!important'
                        ].join(';');
                        while (body.firstChild) body.removeChild(body.firstChild);
                        body.appendChild(wrap);

                        /* ── PHASE 4: show target spread ── */
                        var totalSpreads = Math.ceil(totalPages / 2);
                        var landed = (TARGET === LAST)
                            ? totalSpreads - 1
                            : Math.min(TARGET, totalSpreads - 1);

                        function showSpread(s) {
                            var lp = s * 2, rp = s * 2 + 1;
                            var pages = wrap.querySelectorAll('.qp-page');
                            for (var i = 0; i < pages.length; i++) {
                                var idx = parseInt(pages[i].getAttribute('data-page'), 10);
                                pages[i].style.display = (idx===lp||idx===rp) ? 'block':'none';
                            }
                        }

                        showSpread(landed);

                        window._qpTotal      = totalSpreads;
                        window._qpCur        = landed;
                        window._qpShowSpread = showSpread;

                        window.status = 'qp:ready:' + totalSpreads + ':' + landed;

                    }, 600);
                })();
                """,
                vw, vh, pageW, padH, padV, availH, packH, targetSpread, LAST_SPREAD);

        try {
            engine.executeScript(js);
        } catch (Exception e) {
            System.err.println("[Reader] pagination error: " + e.getMessage());
            busy.set(false);
            hideTransitionOverlay();
        }
    }

    // ── Fast page-turn — show/hide page divs, no scrolling, no clipping math ─
    private static final String JS_NEXT = """
            (function() {
                var showSpread = window._qpShowSpread;
                var cur   = window._qpCur   !== undefined ? window._qpCur   : 0;
                var total = window._qpTotal  !== undefined ? window._qpTotal : 1;
                if (!showSpread) { window.status=''; window.status='qp:next-chapter'; return; }
                if (cur + 1 < total) {
                    var next = cur + 1;
                    showSpread(next);
                    window._qpCur = next;
                    window.status = ''; window.status = 'qp:turned:' + next;
                } else {
                    window.status = ''; window.status = 'qp:next-chapter';
                }
            })();
            """;

    private static final String JS_PREV = """
            (function() {
                var showSpread = window._qpShowSpread;
                var cur = window._qpCur !== undefined ? window._qpCur : 0;
                if (!showSpread) { window.status=''; window.status='qp:prev-chapter'; return; }
                if (cur > 0) {
                    var prev = cur - 1;
                    showSpread(prev);
                    window._qpCur = prev;
                    window.status = ''; window.status = 'qp:turned:' + prev;
                } else {
                    window.status = ''; window.status = 'qp:prev-chapter';
                }
            })();
            """;

    // ── Navigation entry points ───────────────────────────────────────────────
    @FXML
    void onNextPage() {
        doNext();
    }

    @FXML
    void onPrevPage() {
        doPrev();
    }

    private void doNext() {
        if (!busy.compareAndSet(false, true))
            return;
        try {
            engine.executeScript(JS_NEXT);
        } catch (Exception e) {
            busy.set(false);
        }
    }

    private void doPrev() {
        if (!busy.compareAndSet(false, true))
            return;
        try {
            engine.executeScript(JS_PREV);
        } catch (Exception e) {
            busy.set(false);
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
        if (searchPopup != null && searchPopup.isShowing()) {
            searchPopup.hide();
            btnSearch.getStyleClass().remove("active");
            clearSearchHighlights();
        } else {
            closeAllPopups();
            showSearchPopup();
            btnSearch.getStyleClass().add("active");
        }
    }

    @FXML
    private void onTheme() {
        if (themePopup != null && themePopup.isShowing()) {
            themePopup.hide();
            btnTheme.getStyleClass().remove("active");
        } else {
            closeAllPopups();
            showThemePopup();
            btnTheme.getStyleClass().add("active");
        }
    }

    @FXML
    private void onTextStyle() {
        if (stylePopup != null && stylePopup.isShowing()) {
            stylePopup.hide();
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
        // width/height listeners re-paginate automatically
    }

    // ── TOC popup ─────────────────────────────────────────────────────────────
    private void showTocPopup(List<TocEntry> tocList) {
        VBox content = new VBox(0);
        content.setStyle(
                "-fx-background-color:#1A1A1A;" +
                        "-fx-border-color:#333333;" +
                        "-fx-border-width:1;" +
                        "-fx-border-radius:9;" +
                        "-fx-background-radius:9;" +
                        "-fx-effect:dropshadow(gaussian,rgba(0,0,0,0.90),28,0,0,6);");
        content.setPrefWidth(320);
        content.setMaxWidth(320);
        content.setMaxHeight(500);

        // Header row with title + close button
        Label header = new Label("Table of Contents");
        header.setStyle(
                "-fx-text-fill:#DDDDDD;" +
                        "-fx-font-size:13px;" +
                        "-fx-font-weight:bold;" +
                        "-fx-padding:13 16 12 16;");
        header.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(header, Priority.ALWAYS);

        Button closeBtn = new Button("\u2715"); /* ✕ — no FontIcon needed */
        closeBtn.setStyle(
                "-fx-background-color:transparent;" +
                        "-fx-text-fill:#666666;" +
                        "-fx-font-size:14px;" +
                        "-fx-cursor:hand;" +
                        "-fx-padding:4 14 4 4;" +
                        "-fx-border-width:0;");
        closeBtn.setOnMouseEntered(e -> closeBtn.setStyle(closeBtn.getStyle()
                .replace("-fx-text-fill:#666666;", "-fx-text-fill:#CCCCCC;")));
        closeBtn.setOnMouseExited(e -> closeBtn.setStyle(closeBtn.getStyle()
                .replace("-fx-text-fill:#CCCCCC;", "-fx-text-fill:#666666;")));
        closeBtn.setOnAction(e -> {
            if (tocPopup != null)
                tocPopup.hide();
        });

        HBox headerRow = new HBox(header, closeBtn);
        headerRow.setAlignment(Pos.CENTER_LEFT);
        headerRow.setStyle(
                "-fx-background-color:#141414;" +
                        "-fx-border-color:transparent transparent #2E2E2E transparent;" +
                        "-fx-border-width:0 0 1 0;" +
                        "-fx-background-radius:9 9 0 0;");

        VBox list = new VBox(0);
        list.setStyle("-fx-background-color:#1A1A1A;");
        populateTocList(list, tocList, 0);

        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("toc-scroll-pane");
        scroll.setStyle("-fx-background-color:transparent;-fx-border-color:transparent;");
        scroll.getStylesheets().add(
                getClass().getResource("/com/quietpages/quietpages/reader.css").toExternalForm());
        VBox.setVgrow(scroll, Priority.ALWAYS);
        content.getChildren().addAll(headerRow, scroll);

        tocPopup = new Popup();
        tocPopup.getContent().add(content);
        tocPopup.setAutoHide(true);
        tocPopup.setOnHidden(e -> btnToc.getStyleClass().remove("active"));

        javafx.geometry.Bounds b = btnToc.localToScreen(btnToc.getBoundsInLocal());
        if (b != null)
            tocPopup.show(stage(), b.getMinX(), b.getMaxY() + 6);
        else
            tocPopup.show(stage());
    }

    private void populateTocList(VBox list, List<TocEntry> entries, int depth) {
        for (TocEntry entry : entries) {
            double lp = 16 + depth * 16.0;
            Label row = new Label(entry.title);
            row.setMaxWidth(Double.MAX_VALUE);
            row.setWrapText(false);
            String baseStyle = String.format(
                    "-fx-text-fill:%s;-fx-font-size:%s;-fx-padding:10 16 10 %.0fpx;" +
                            "-fx-cursor:hand;-fx-background-color:transparent;" +
                            "-fx-border-color:transparent transparent rgba(255,255,255,0.04) transparent;" +
                            "-fx-border-width:0 0 1 0;",
                    depth == 0 ? "#CCCCCC" : "#999999",
                    depth == 0 ? "13px" : "12px", lp);
            row.setStyle(baseStyle);
            row.setOnMouseEntered(e -> row.setStyle(baseStyle
                    .replace("-fx-background-color:transparent;",
                            "-fx-background-color:rgba(255,255,255,0.07);")));
            row.setOnMouseExited(e -> row.setStyle(baseStyle));
            final TocEntry fe = entry;
            row.setOnMouseClicked(e -> {
                if (tocPopup != null)
                    tocPopup.hide();
                int idx = renderer.findSpineIndex(fe.resolvedUrl != null
                        ? fe.resolvedUrl.split("#")[0]
                        : "");
                loadChapter(idx, 0);
            });
            list.getChildren().add(row);
            if (!entry.children.isEmpty())
                populateTocList(list, entry.children, depth + 1);
        }
    }

    // ── Search popup (floating Popup, compact card) ─────────────────────────
    private void showSearchPopup() {
        VBox card = new VBox(10);
        card.setStyle(
                "-fx-background-color:#1E1E1E;" +
                        "-fx-border-color:#383838;" +
                        "-fx-border-width:1;" +
                        "-fx-border-radius:8;" +
                        "-fx-background-radius:8;" +
                        "-fx-padding:14 14 12 14;" +
                        "-fx-effect:dropshadow(gaussian,rgba(0,0,0,0.85),22,0,0,5);");
        card.setPrefWidth(340);
        card.setMaxWidth(340);

        TextField field = new TextField();
        field.setPromptText("Find in chapter…");
        field.setStyle(
                "-fx-background-color:#141414;" +
                        "-fx-text-fill:#E0E0E0;" +
                        "-fx-prompt-text-fill:#555555;" +
                        "-fx-border-color:#C0284A;" +
                        "-fx-border-width:0 0 2 0;" +
                        "-fx-background-radius:4;" +
                        "-fx-padding:8 10;" +
                        "-fx-font-size:13px;");
        field.setMaxWidth(Double.MAX_VALUE);

        // Aa = match-case toggle, Ab| = whole-word toggle — plain text buttons
        ToggleButton btnMC = mkTextToggle("Aa", "Match case");
        ToggleButton btnWW = mkTextToggle("Ab|", "Whole word");

        // → next match, ✕ close — Unicode characters, no FontIcon dependency
        Button btnGo = mkFlatBtn("→", "Find next");
        Button btnClose = mkFlatBtn("✕", "Close");
        btnClose.setOnAction(e -> {
            if (searchPopup != null)
                searchPopup.hide();
            btnSearch.getStyleClass().remove("active");
            clearSearchHighlights();
        });

        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox controls = new HBox(6, btnMC, btnWW, gap, btnGo, btnClose);
        controls.setAlignment(Pos.CENTER_LEFT);

        Label resultLbl = new Label();
        resultLbl.setStyle("-fx-font-size:11px;-fx-text-fill:#888888;");

        card.getChildren().addAll(field, controls, resultLbl);

        searchPopup = new Popup();
        searchPopup.setAutoHide(true);
        searchPopup.getContent().add(card);
        searchPopup.setOnHidden(e -> {
            btnSearch.getStyleClass().remove("active");
            clearSearchHighlights();
        });

        Runnable search = () -> {
            String term = field.getText().trim();
            if (term.isBlank())
                return;
            boolean mc = btnMC.isSelected();
            boolean ww = btnWW.isSelected();
            String esc = term.replace("\\", "\\\\").replace("'", "\\'")
                    .replace("/", "\\/").replace(".", "\\.")
                    .replace("*", "\\*").replace("+", "\\+")
                    .replace("?", "\\?").replace("(", "\\(")
                    .replace(")", "\\)").replace("[", "\\[")
                    .replace("]", "\\]").replace("{", "\\{")
                    .replace("}", "\\}").replace("^", "\\^")
                    .replace("$", "\\$").replace("|", "\\|");
            String wbPre = ww ? "\\b" : "", wbPost = ww ? "\\b" : "";
            String flags = mc ? "g" : "gi";
            String js = "(function(){" +
                    "var re=new RegExp('" + wbPre + esc + wbPost + "','" + flags + "');" +
                    "var marks=document.querySelectorAll('mark.qp-hl');" +
                    "for(var i=0;i<marks.length;i++){marks[i].parentNode.replaceChild(document.createTextNode(marks[i].textContent),marks[i]);}"
                    +
                    "document.body.normalize();" +
                    "var wrap=document.getElementById('qp-wrap');" +
                    "if(!wrap)return 'nopages';" +
                    "var tw=document.createTreeWalker(wrap,NodeFilter.SHOW_TEXT,null,false);" +
                    "var txts=[],nd;" +
                    "while((nd=tw.nextNode())){if(re.test(nd.nodeValue))txts.push(nd);}" +
                    "var count=0;" +
                    "for(var i=0;i<txts.length;i++){" +
                    "  var tn=txts[i];re.lastIndex=0;" +
                    "  var val=tn.nodeValue,parts=[],last=0,m;" +
                    "  while((m=re.exec(val))!==null){" +
                    "    if(m.index>last)parts.push(document.createTextNode(val.slice(last,m.index)));" +
                    "    var mk=document.createElement('mark');mk.className='qp-hl';" +
                    "    mk.style.cssText='background:#FFD700;color:#000;border-radius:2px;padding:0 1px;';" +
                    "    mk.textContent=m[0];parts.push(mk);last=m.index+m[0].length;count++;}" +
                    "  if(parts.length>0){" +
                    "    if(last<val.length)parts.push(document.createTextNode(val.slice(last)));" +
                    "    var f=document.createDocumentFragment();" +
                    "    for(var j=0;j<parts.length;j++)f.appendChild(parts[j]);" +
                    "    tn.parentNode.replaceChild(f,tn);}}" +
                    "if(count===0)return 'notfound';" +
                    "var first=document.querySelector('mark.qp-hl');" +
                    "if(!first)return 'notfound';" +
                    "var pg=first;" +
                    "while(pg&&!pg.classList.contains('qp-page'))pg=pg.parentElement;" +
                    "if(pg&&window._qpShowSpread){" +
                    "  var idx=parseInt(pg.getAttribute('data-page'),10);" +
                    "  var s=Math.floor(idx/2);" +
                    "  window._qpShowSpread(s);window._qpCur=s;" +
                    "  window.status='';window.status='qp:turned:'+s;}" +
                    "return 'found:'+count;})()";
            try {
                Object res = engine.executeScript(js);
                String r = res != null ? res.toString() : "error";
                if (r.startsWith("found:")) {
                    int n = Integer.parseInt(r.split(":")[1]);
                    resultLbl.setText("\u2713 " + n + " match" + (n == 1 ? "" : "es") + " highlighted");
                    resultLbl.setStyle("-fx-font-size:11px;-fx-text-fill:#55cc77;");
                } else {
                    resultLbl.setText("\u2717 No matches found");
                    resultLbl.setStyle("-fx-font-size:11px;-fx-text-fill:#cc5555;");
                }
            } catch (Exception ex) {
                resultLbl.setText("Search error");
            }
        };
        btnGo.setOnAction(e -> search.run());
        field.setOnAction(e -> search.run());

        javafx.geometry.Bounds b = btnSearch.localToScreen(btnSearch.getBoundsInLocal());
        if (b != null)
            searchPopup.show(stage(), b.getMaxX() - 340, b.getMaxY() + 6);
        else
            searchPopup.show(stage());
        Platform.runLater(field::requestFocus);
    }

    private void clearSearchHighlights() {
        try {
            engine.executeScript(
                    "(function(){var m=document.querySelectorAll('mark.qp-hl');" +
                            "for(var i=0;i<m.length;i++){m[i].parentNode.replaceChild(document.createTextNode(m[i].textContent),m[i]);}"
                            +
                            "document.body.normalize();})()");
        } catch (Exception ignored) {
        }
    }

    // ── Text Style popup (floating Popup) ───────────────────────────────────
    private void showTextStylePopup() {
        VBox card = new VBox(12);
        card.setStyle(
                "-fx-background-color:#1E1E1E;" +
                        "-fx-border-color:#383838;" +
                        "-fx-border-width:1;" +
                        "-fx-border-radius:8;" +
                        "-fx-background-radius:8;" +
                        "-fx-padding:16;" +
                        "-fx-effect:dropshadow(gaussian,rgba(0,0,0,0.85),22,0,0,5);");
        card.setPrefWidth(300);
        card.setMaxWidth(300);

        Slider sl = mkSlider(1.2, 3.0, theme.lineHeight);
        Slider sf = mkSlider(12, 28, theme.fontSize);
        Slider sp = mkSlider(0, 3.0, theme.paragraphSpace);
        Slider sm = mkSlider(16, 80, theme.marginH);

        // Alignment buttons using plain Unicode — no FontIcon dependency
        ToggleGroup ag = new ToggleGroup();
        ToggleButton taL = mkAlignBtn("\u2630", "left", ag, "Left");
        ToggleButton taC = mkAlignBtn("\u2261", "center", ag, "Center");
        ToggleButton taJ = mkAlignBtn("\u2630", "justify", ag, "Justify");
        ToggleButton taR = mkAlignBtn("\u2630", "right", ag, "Right");

        // Better visual: use actual alignment symbols
        taL.setText("\u2630"); // Menu = left-ish
        taC.setText("\u2261"); // Three lines centered
        taJ.setText("\u2630");
        taR.setText("\u2630");

        // Use clear label text instead — most reliable cross-platform
        taL.setText("L");
        taC.setText("C");
        taJ.setText("J");
        taR.setText("R");

        ag.getToggles().stream().filter(t -> theme.textAlign.equals(t.getUserData()))
                .findFirst().ifPresent(ag::selectToggle);
        HBox ar = new HBox(6, taL, taC, taJ, taR);
        ar.setAlignment(Pos.CENTER_LEFT);

        Button btnReset = mkFlatBtn("↺  Reset", "Reset to defaults");
        btnReset.setStyle(btnReset.getStyle() +
                "-fx-min-width:80px;-fx-padding:6 14;-fx-font-size:12px;");
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
        Button btnClose = mkFlatBtn("✕", "Close");
        btnClose.setOnAction(e -> {
            if (stylePopup != null)
                stylePopup.hide();
            btnTextStyle.getStyleClass().remove("active");
        });
        Region rx = new Region();
        HBox.setHgrow(rx, Priority.ALWAYS);
        HBox br = new HBox(8, btnReset, rx, btnClose);
        br.setAlignment(Pos.CENTER_LEFT);

        card.getChildren().addAll(
                mkPopupLbl("Line spacing"), sl,
                mkPopupLbl("Font size"), sf,
                mkPopupLbl("Para spacing"), sp,
                mkPopupLbl("Page margin"), sm,
                mkPopupLbl("Alignment"), ar, br);

        stylePopup = new Popup();
        stylePopup.setAutoHide(true);
        stylePopup.getContent().add(card);
        stylePopup.setOnHidden(e -> btnTextStyle.getStyleClass().remove("active"));

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

        javafx.geometry.Bounds b = btnTextStyle.localToScreen(btnTextStyle.getBoundsInLocal());
        if (b != null)
            stylePopup.show(stage(), b.getMaxX() - 300, b.getMaxY() + 6);
        else
            stylePopup.show(stage());
    }

    private void reloadCurrentChapter() {
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
                ? ((spineIndex + (totalSpreads > 1
                        ? (double) currentSpread / totalSpreads
                        : 0.0))
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
                .prepareStatement("UPDATE books SET reading_progress=?,reading_status='READING'," +
                        "last_read=datetime('now') WHERE id=?")) {
            ps.setDouble(1, p);
            ps.setInt(2, book.getId());
            ps.executeUpdate();
        } catch (Exception e) {
            System.err.println("[Reader] progress: " + e.getMessage());
        }
    }

    // ── Utility ───────────────────────────────────────────────────────────────
    private void closeAllPopups() {
        if (searchPopup != null && searchPopup.isShowing()) {
            searchPopup.hide();
        }
        if (stylePopup != null && stylePopup.isShowing()) {
            stylePopup.hide();
        }
        if (themePopup != null && themePopup.isShowing()) {
            themePopup.hide();
        }
        if (tocPopup != null && tocPopup.isShowing()) {
            tocPopup.hide();
        }
        btnSearch.getStyleClass().remove("active");
        btnTextStyle.getStyleClass().remove("active");
        if (btnTheme != null)
            btnTheme.getStyleClass().remove("active");
        btnToc.getStyleClass().remove("active");
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
        return mkPopupLbl(t);
    }

    // ── Popup helper factories ────────────────────────────────────────────────

    /**
     * Alignment toggle button — text label, fully visible, no FontIcon dependency.
     */
    private ToggleButton mkAlignBtn(String unusedIcon, String align, ToggleGroup grp, String label) {
        ToggleButton btn = new ToggleButton(label);
        btn.setToggleGroup(grp);
        btn.setUserData(align);
        btn.setStyle(
                "-fx-background-color:#252525;" +
                        "-fx-text-fill:#AAAAAA;" +
                        "-fx-font-size:12px;-fx-font-weight:bold;" +
                        "-fx-border-color:#444444;-fx-border-width:1;" +
                        "-fx-border-radius:5;-fx-background-radius:5;" +
                        "-fx-pref-width:54px;-fx-pref-height:34px;" +
                        "-fx-cursor:hand;");
        btn.selectedProperty().addListener((o, ov, nv) -> btn.setStyle(
                "-fx-background-color:" + (nv ? "rgba(192,40,74,0.18)" : "#252525") + ";" +
                        "-fx-text-fill:" + (nv ? "#C0284A" : "#AAAAAA") + ";" +
                        "-fx-font-size:12px;-fx-font-weight:bold;" +
                        "-fx-border-color:" + (nv ? "#C0284A" : "#444444") + ";-fx-border-width:" + (nv ? "2" : "1")
                        + ";" +
                        "-fx-border-radius:5;-fx-background-radius:5;" +
                        "-fx-pref-width:54px;-fx-pref-height:34px;" +
                        "-fx-cursor:hand;"));
        return btn;
    }

    /** Plain text toggle for search options (Aa, Ab|). */
    private ToggleButton mkTextToggle(String label, String tooltip) {
        ToggleButton btn = new ToggleButton(label);
        btn.setStyle(
                "-fx-background-color:#252525;" +
                        "-fx-text-fill:#AAAAAA;" +
                        "-fx-font-size:12px;-fx-font-weight:bold;" +
                        "-fx-border-color:#444444;-fx-border-width:1;" +
                        "-fx-border-radius:4;-fx-background-radius:4;" +
                        "-fx-pref-height:30px;-fx-padding:0 12;" +
                        "-fx-cursor:hand;");
        btn.setTooltip(new Tooltip(tooltip));
        btn.selectedProperty().addListener((o, ov, nv) -> btn.setStyle(
                "-fx-background-color:" + (nv ? "rgba(192,40,74,0.18)" : "#252525") + ";" +
                        "-fx-text-fill:" + (nv ? "#C0284A" : "#AAAAAA") + ";" +
                        "-fx-font-size:12px;-fx-font-weight:bold;" +
                        "-fx-border-color:" + (nv ? "#C0284A" : "#444444") + ";-fx-border-width:" + (nv ? "2" : "1")
                        + ";" +
                        "-fx-border-radius:4;-fx-background-radius:4;" +
                        "-fx-pref-height:30px;-fx-padding:0 12;" +
                        "-fx-cursor:hand;"));
        return btn;
    }

    /** Small icon-style flat button using a Unicode character. */
    private Button mkFlatBtn(String symbol, String tooltipText) {
        Button btn = new Button(symbol);
        btn.setStyle(
                "-fx-background-color:#252525;" +
                        "-fx-text-fill:#AAAAAA;" +
                        "-fx-font-size:13px;" +
                        "-fx-border-color:#444444;-fx-border-width:1;" +
                        "-fx-border-radius:4;-fx-background-radius:4;" +
                        "-fx-min-width:30px;-fx-min-height:30px;" +
                        "-fx-padding:0 8;" +
                        "-fx-cursor:hand;");
        btn.setTooltip(new Tooltip(tooltipText));
        btn.setOnMouseEntered(e -> btn.setStyle(btn.getStyle()
                .replace("-fx-background-color:#252525;", "-fx-background-color:#333333;")
                .replace("-fx-text-fill:#AAAAAA;", "-fx-text-fill:#EEEEEE;")));
        btn.setOnMouseExited(e -> btn.setStyle(btn.getStyle()
                .replace("-fx-background-color:#333333;", "-fx-background-color:#252525;")
                .replace("-fx-text-fill:#EEEEEE;", "-fx-text-fill:#AAAAAA;")));
        return btn;
    }

    /** Section label for style popup rows. */
    private Label mkPopupLbl(String text) {
        Label l = new Label(text.toUpperCase());
        l.setStyle(
                "-fx-text-fill:rgba(255,255,255,0.40);" +
                        "-fx-font-size:9px;-fx-font-weight:bold;" +
                        "-fx-padding:4 0 0 0;");
        return l;
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

    public ReaderTheme getTheme() {
        return theme;
    }

    public void setTheme(ReaderTheme t) {
        this.theme = t;
        if (renderer != null)
            reloadCurrentChapter();
    }

    // ── Theme popup (floating Popup) ───────────────────────────────────────
    private void showThemePopup() {
        VBox card = new VBox(12);
        card.setStyle(
                "-fx-background-color:#1E1E1E;" +
                        "-fx-border-color:#383838;" +
                        "-fx-border-width:1;" +
                        "-fx-border-radius:8;" +
                        "-fx-background-radius:8;" +
                        "-fx-padding:14;" +
                        "-fx-effect:dropshadow(gaussian,rgba(0,0,0,0.85),22,0,0,5);");
        card.setPrefWidth(270);
        card.setMaxWidth(270);

        // Header: title on the left, close button pinned to the right
        Label titleLbl = new Label("Reader Theme");
        titleLbl.setStyle("-fx-text-fill:#CCCCCC;-fx-font-size:12px;-fx-font-weight:bold;");
        HBox.setHgrow(titleLbl, Priority.ALWAYS);

        Button closeBtn = new Button("\u2715");
        closeBtn.setStyle(
                "-fx-background-color:transparent;" +
                        "-fx-text-fill:#666666;" +
                        "-fx-font-size:13px;" +
                        "-fx-cursor:hand;" +
                        "-fx-padding:2 6 2 6;" +
                        "-fx-background-radius:4;" +
                        "-fx-border-width:0;" +
                        "-fx-min-width:24px;-fx-min-height:24px;");
        closeBtn.setOnMouseEntered(ev -> closeBtn.setStyle(closeBtn.getStyle()
                .replace("-fx-text-fill:#666666;", "-fx-text-fill:#EEEEEE;")
                .replace("-fx-background-color:transparent;", "-fx-background-color:rgba(255,255,255,0.08);")));
        closeBtn.setOnMouseExited(ev -> closeBtn.setStyle(closeBtn.getStyle()
                .replace("-fx-text-fill:#EEEEEE;", "-fx-text-fill:#666666;")
                .replace("-fx-background-color:rgba(255,255,255,0.08);", "-fx-background-color:transparent;")));
        closeBtn.setOnAction(e -> {
            if (themePopup != null)
                themePopup.hide();
            btnTheme.getStyleClass().remove("active");
        });

        HBox titleRow = new HBox(titleLbl, closeBtn);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        titleRow.setPadding(new Insets(0, 0, 8, 0));

        String[][] themes = {
                { "dark", "#0D0D0D", "#E8E8E8", "Dark" },
                { "light", "#F5F0E8", "#1A1A1A", "Light" },
                { "sepia", "#F4ECD8", "#3B2F2F", "Sepia" },
                { "night", "#0A0A1A", "#9BA8C0", "Night" },
                { "forest", "#0F1A0F", "#C8E0C8", "Forest" },
                { "ocean", "#0A1520", "#C0D8EE", "Ocean" },
        };

        javafx.scene.layout.FlowPane swatchRow = new javafx.scene.layout.FlowPane(8, 10);
        swatchRow.setPrefWrapLength(250);

        for (String[] th : themes) {
            String thName = th[0], thBg = th[1], thText = th[2], thLabel = th[3];
            boolean isActive = readerColorTheme.equals(thName);

            VBox swatch = new VBox(4);
            swatch.setAlignment(Pos.CENTER);
            swatch.setCursor(javafx.scene.Cursor.HAND);
            swatch.setPrefWidth(62);

            javafx.scene.layout.StackPane chip = new javafx.scene.layout.StackPane();
            chip.setPrefSize(62, 40);
            String border = isActive ? "#C0284A" : "#444444";
            String chipStyle = "-fx-background-color:" + thBg + ";" +
                    "-fx-border-color:" + border + ";" +
                    "-fx-border-width:" + (isActive ? "2.5" : "1.5") + ";" +
                    "-fx-border-radius:6;-fx-background-radius:6;";
            chip.setStyle(chipStyle);

            Label sample = new Label("Aa");
            sample.setStyle(
                    "-fx-text-fill:" + thText + ";" +
                            "-fx-font-size:14px;" +
                            "-fx-font-weight:bold;");
            chip.getChildren().add(sample);

            Label lbl = new Label(thLabel);
            lbl.setStyle(
                    "-fx-text-fill:" + (isActive ? "#C0284A" : "#888888") + ";" +
                            "-fx-font-size:10px;" +
                            (isActive ? "-fx-font-weight:bold;" : ""));

            swatch.getChildren().addAll(chip, lbl);

            swatch.setOnMouseEntered(
                    e -> chip.setStyle(chipStyle.replace("border-color:" + border, "border-color:#C0284A")
                            .replace("border-width:" + (isActive ? "2.5" : "1.5"), "border-width:2.5")));
            swatch.setOnMouseExited(e -> chip.setStyle(chipStyle));

            swatch.setOnMouseClicked(e -> {
                readerColorTheme = thName;
                theme.bgColor = thBg;
                theme.textColor = thText;
                if (themePopup != null)
                    themePopup.hide();
                btnTheme.getStyleClass().remove("active");
                reloadCurrentChapter();
            });
            swatchRow.getChildren().add(swatch);
        }

        card.getChildren().addAll(titleRow, swatchRow);

        themePopup = new Popup();
        themePopup.setAutoHide(true);
        themePopup.getContent().add(card);
        themePopup.setOnHidden(e -> btnTheme.getStyleClass().remove("active"));

        javafx.geometry.Bounds b = btnTheme.localToScreen(btnTheme.getBoundsInLocal());
        if (b != null)
            themePopup.show(stage(), b.getMaxX() - 270, b.getMaxY() + 6);
        else
            themePopup.show(stage());
    }

}