package com.quietpages.quietpages.controller;

import com.quietpages.quietpages.model.DownloadEntry;
import com.quietpages.quietpages.model.OnlineSite;
import com.quietpages.quietpages.service.LibraryService;
import com.quietpages.quietpages.service.OnlineBooksService;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public class OnlineBooksController {

    // ── FXML — Toolbar ────────────────────────────────────────────────────────
    @FXML private Button btnAddSite;
    @FXML private Button btnDownloads;
    @FXML private Button btnInfo;
    @FXML private Button btnHome;
    @FXML private Button btnRefresh;
    @FXML private Button btnBack;
    @FXML private Button btnForward;

    // ── FXML — Left sidebar ───────────────────────────────────────────────────
    @FXML private VBox siteListVBox;

    // ── FXML — Main content area ──────────────────────────────────────────────
    @FXML private StackPane webContainer;
    @FXML private Label     lblSelectSite;

    // ── FXML — Downloads panel ────────────────────────────────────────────────
    @FXML private VBox  downloadsPanel;
    @FXML private VBox  downloadsListVBox;
    @FXML private Label lblNoDownloads;

    // ── State ─────────────────────────────────────────────────────────────────
    private final OnlineBooksService service        = OnlineBooksService.getInstance();
    private final LibraryService     libraryService = LibraryService.getInstance();

    private ObservableList<OnlineSite>    sites     = FXCollections.observableArrayList();
    private ObservableList<DownloadEntry> downloads = FXCollections.observableArrayList();

    private Object webViewObj;   // javafx.scene.web.WebView
    private Object engineObj;    // javafx.scene.web.WebEngine

    private OnlineSite activeSite = null;

    // ── Init ──────────────────────────────────────────────────────────────────
    @FXML
    public void initialize() {
        downloadsPanel.setVisible(false);
        downloadsPanel.setManaged(false);
        setupWebView();
        loadSites();
    }

    // Tracks URLs we have already queued for download to prevent duplicates
    private final java.util.Set<String> downloadedUrls =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private void setupWebView() {
        javafx.scene.web.WebView wv = new javafx.scene.web.WebView();
        javafx.scene.web.WebEngine we = wv.getEngine();
        webViewObj = wv;
        engineObj  = we;
        wv.setVisible(false);

        // Use createPopupHandler to intercept file downloads.
        // The location listener only shows/hides the placeholder.
        we.locationProperty().addListener((obs, oldUrl, newUrl) -> {
            if (newUrl == null || newUrl.isBlank()) return;
            String lower = newUrl.toLowerCase();

            // Intercept EPUB/PDF — cancel navigation, start download
            if (lower.endsWith(".epub") || lower.endsWith(".pdf")) {
                if (!downloadedUrls.contains(newUrl)) {
                    downloadedUrls.add(newUrl);
                    final String downloadUrl = newUrl;
                    final String returnUrl   = (oldUrl != null && !oldUrl.isBlank()
                            && !oldUrl.equals(newUrl)) ? oldUrl : null;
                    // Schedule on next pulse so the engine state is stable
                    Platform.runLater(() -> {
                        if (returnUrl != null) we.load(returnUrl);
                        startDownload(downloadUrl);
                        // Allow re-download of same URL after 5 seconds
                        new java.util.Timer(true).schedule(new java.util.TimerTask() {
                            public void run() { downloadedUrls.remove(downloadUrl); }
                        }, 5000);
                    });
                }
                return;
            }

            // Normal page navigation
            Platform.runLater(() -> {
                lblSelectSite.setVisible(false);
                wv.setVisible(true);
            });
        });

        webContainer.getChildren().add(0, wv);
        StackPane.setAlignment(wv, Pos.CENTER);
    }

    // ── Site list ─────────────────────────────────────────────────────────────
    private void loadSites() {
        sites = service.getAllSites();
        renderSiteList();
    }

    private void renderSiteList() {
        siteListVBox.getChildren().clear();
        for (OnlineSite site : sites) {
            siteListVBox.getChildren().add(createSiteRow(site));
        }
    }

    private HBox createSiteRow(OnlineSite site) {
        HBox row = new HBox(12);
        row.getStyleClass().add("site-row");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPrefHeight(60);

        ImageView icon = new ImageView();
        icon.setFitWidth(36);
        icon.setFitHeight(36);
        icon.setPreserveRatio(true);

        if (site.getIconData() != null && site.getIconData().length > 0) {
            try { icon.setImage(new Image(new ByteArrayInputStream(site.getIconData()))); }
            catch (Exception ignored) {}
        } else {
            // Fetch favicon and also persist it to DB for next launch
            loadFaviconAsync(site.getUrl(), icon, fetchedBytes -> {
                if (fetchedBytes != null && fetchedBytes.length > 0) {
                    site.setIconData(fetchedBytes);
                    if (!site.isDefault()) service.updateSite(site);
                    else persistDefaultSiteIcon(site, fetchedBytes);
                }
            });
        }

        Label lbl = new Label(site.getTitle());
        lbl.getStyleClass().add("site-title");
        row.getChildren().addAll(icon, lbl);
        row.setUserData(site);

        row.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                activateSite(site, row);
            } else if (e.getButton() == MouseButton.SECONDARY && !site.isDefault()) {
                showSiteContextMenu(site, row);
            }
        });
        return row;
    }

    /** Persist icon for a default site (update only icon_data, not url/title). */
    private void persistDefaultSiteIcon(OnlineSite site, byte[] iconBytes) {
        try (java.sql.PreparedStatement ps =
                     com.quietpages.quietpages.db.DatabaseManager.getInstance()
                             .getConnection()
                             .prepareStatement(
                                     "UPDATE online_sites SET icon_data=? WHERE id=?")) {
            ps.setBytes(1, iconBytes);
            ps.setInt(2, site.getId());
            ps.executeUpdate();
        } catch (Exception e) {
            System.err.println("[Online] Failed to persist default icon: " + e.getMessage());
        }
    }

    private void activateSite(OnlineSite site, HBox row) {
        activeSite = site;
        siteListVBox.getChildren().forEach(n -> n.getStyleClass().remove("site-row-active"));
        row.getStyleClass().add("site-row-active");
        getEngine().load(site.getUrl());
    }

    private void showSiteContextMenu(OnlineSite site, Node anchor) {
        ContextMenu menu = new ContextMenu();
        MenuItem edit   = new MenuItem("  Edit");
        MenuItem remove = new MenuItem("  Remove");
        edit.setOnAction(e -> showAddSiteDialog(site));
        remove.setOnAction(e -> {
            service.removeSite(site.getId());
            if (activeSite != null && activeSite.getId() == site.getId()) {
                activeSite = null;
                lblSelectSite.setVisible(true);
                getWebView().setVisible(false);
            }
            loadSites();
        });
        menu.getItems().addAll(edit, remove);
        menu.show(anchor, Side.BOTTOM, 0, 0);
    }

    // ── Toolbar ───────────────────────────────────────────────────────────────
    @FXML private void onAddSite()   { showAddSiteDialog(null); }

    @FXML private void onDownloads() {
        boolean show = !downloadsPanel.isVisible();
        downloadsPanel.setVisible(show);
        downloadsPanel.setManaged(show);
        if (show) updateDownloadsPanel();
    }

    @FXML private void onHome() {
        if (activeSite != null) getEngine().load(activeSite.getUrl());
    }

    @FXML private void onRefresh()  { getEngine().reload(); }
    @FXML private void onBack()     { getEngine().executeScript("history.back()"); }
    @FXML private void onForward()  { getEngine().executeScript("history.forward()"); }
    @FXML private void onInfo()     { /* reserved */ }

    // ── Add / Edit dialog ─────────────────────────────────────────────────────
    private void showAddSiteDialog(OnlineSite existing) {
        boolean isEdit = existing != null;

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(isEdit ? "Edit site" : "Add site");

        DialogPane pane = new DialogPane();
        pane.getStylesheets().add(
                getClass().getResource("/com/quietpages/quietpages/library.css").toExternalForm());
        pane.getStyleClass().add("edit-dialog-pane");
        pane.setPrefWidth(460);

        VBox form = new VBox(14);
        form.setPadding(new Insets(16));

        Label heading = new Label(isEdit ? "Edit site" : "Add site");
        heading.setStyle("-fx-font-size:18px;-fx-font-weight:bold;-fx-text-fill:#DDDDDD;");

        // URL row
        Label urlLbl = new Label("Site url :");
        urlLbl.getStyleClass().add("edit-field-label");
        TextField urlField = new TextField(isEdit ? existing.getUrl() : "");
        urlField.getStyleClass().add("edit-field");
        urlField.setMaxWidth(Double.MAX_VALUE);
        HBox urlRow = new HBox(12, urlLbl, urlField);
        urlRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(urlField, Priority.ALWAYS);

        // Title row
        Label titleLbl = new Label("Title :");
        titleLbl.getStyleClass().add("edit-field-label");
        TextField titleField = new TextField(isEdit ? existing.getTitle() : "");
        titleField.getStyleClass().add("edit-field");
        titleField.setMaxWidth(Double.MAX_VALUE);
        HBox titleRow = new HBox(12, titleLbl, titleField);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(titleField, Priority.ALWAYS);

        // Icon row
        final byte[][] iconBytes = {isEdit ? existing.getIconData() : null};
        ImageView iconPreview = new ImageView();
        iconPreview.setFitWidth(48);
        iconPreview.setFitHeight(48);
        iconPreview.setPreserveRatio(true);
        if (iconBytes[0] != null && iconBytes[0].length > 0) {
            try { iconPreview.setImage(new Image(new ByteArrayInputStream(iconBytes[0]))); }
            catch (Exception ignored) {}
        }
        StackPane iconBox = new StackPane(iconPreview);
        iconBox.setStyle("-fx-background-color:#3A3A3A;-fx-background-radius:4;");
        iconBox.setMinSize(58, 58);
        iconBox.setMaxSize(58, 58);

        Button selectIconBtn = new Button("Select icc");
        selectIconBtn.getStyleClass().add("edit-cover-btn");
        selectIconBtn.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Select Icon");
            fc.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("Image Files",
                            "*.png","*.jpg","*.jpeg","*.ico","*.webp"));
            java.io.File f = fc.showOpenDialog(selectIconBtn.getScene().getWindow());
            if (f != null) {
                try {
                    iconBytes[0] = Files.readAllBytes(f.toPath());
                    iconPreview.setImage(new Image(f.toURI().toString()));
                } catch (Exception ex) {
                    System.err.println("[Online] Icon load: " + ex.getMessage());
                }
            }
        });

        // ── Auto-fetch icon when user moves focus away from URL field ──────
        urlField.focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
            // Trigger when focus LEAVES the URL field (user clicked elsewhere)
            if (wasFocused && !isNowFocused && !urlField.getText().isBlank()) {
                loadFaviconAsync(urlField.getText(), iconPreview, fetched -> {
                    if (fetched != null) iconBytes[0] = fetched;
                });
            }
        });

        Label iccLbl = new Label("Icon :");
        iccLbl.getStyleClass().add("edit-field-label");
        HBox iconRow = new HBox(12, iconBox, selectIconBtn);
        iconRow.setAlignment(Pos.CENTER_LEFT);
        HBox iconFormRow = new HBox(12, iccLbl, iconRow);
        iconFormRow.setAlignment(Pos.CENTER_LEFT);

        form.getChildren().addAll(heading, urlRow, titleRow, iconFormRow);
        pane.setContent(form);

        ButtonType saveType = new ButtonType(
                isEdit ? "Update" : "Add", ButtonBar.ButtonData.OK_DONE);
        pane.getButtonTypes().addAll(saveType,
                new ButtonType("Close", ButtonBar.ButtonData.CANCEL_CLOSE));
        dialog.setDialogPane(pane);

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isPresent() && result.get().getButtonData() == ButtonBar.ButtonData.OK_DONE) {
            String url   = urlField.getText().trim();
            String title = titleField.getText().trim();
            if (url.isBlank() || title.isBlank()) return;

            if (isEdit) {
                existing.setUrl(url);
                existing.setTitle(title);
                if (iconBytes[0] != null) existing.setIconData(iconBytes[0]);
                service.updateSite(existing);
            } else {
                OnlineSite newSite = new OnlineSite();
                newSite.setUrl(url);
                newSite.setTitle(title);
                newSite.setDefault(false);
                newSite.setIconData(iconBytes[0]);
                service.addSite(newSite);
            }
            loadSites();
        }
    }

    // ── Downloads panel ───────────────────────────────────────────────────────
    private void updateDownloadsPanel() {
        downloadsListVBox.getChildren().clear();
        if (downloads.isEmpty()) {
            lblNoDownloads.setVisible(true);
            lblNoDownloads.setManaged(true);
            return;
        }
        lblNoDownloads.setVisible(false);
        lblNoDownloads.setManaged(false);

        for (DownloadEntry entry : downloads) {
            VBox item = new VBox(3);
            item.getStyleClass().add("download-item");

            Label nameLbl = new Label(entry.getFileName());
            nameLbl.getStyleClass().add("download-name");
            nameLbl.setWrapText(true);
            nameLbl.setMaxWidth(270);

            Label statusLbl = new Label(entry.getStatusLabel());
            statusLbl.getStyleClass().add(
                    entry.getStatus() == DownloadEntry.Status.FAILED
                            ? "download-status-failed" : "download-status-ok");

            item.getChildren().addAll(nameLbl, statusLbl);
            downloadsListVBox.getChildren().add(item);
        }
    }

    // ── Download file ─────────────────────────────────────────────────────────
    private void startDownload(String fileUrl) {
        // Decode URL-encoded filename
        String rawName = fileUrl.contains("/")
                ? fileUrl.substring(fileUrl.lastIndexOf('/') + 1) : fileUrl;
        if (rawName.contains("?")) rawName = rawName.substring(0, rawName.indexOf('?'));

        String decoded;
        try { decoded = URLDecoder.decode(rawName, StandardCharsets.UTF_8); }
        catch (Exception e) { decoded = rawName; }

        // Remove illegal Windows filename characters
        String fileName = decoded
                .replace('\\', '_').replace('/', '_').replace(':', '_')
                .replace('*', '_').replace('?', '_').replace('"', '_')
                .replace('<', '_').replace('>', '_').replace('|', '_')
                .trim();

        // Truncate to 80 chars
        if (fileName.length() > 80) {
            int dotIdx = fileName.lastIndexOf('.');
            String ext = dotIdx > 0 ? fileName.substring(dotIdx) : "";
            fileName = fileName.substring(0, 80 - ext.length()) + ext;
        }

        DownloadEntry entry = new DownloadEntry(fileName);
        downloads.add(0, entry);
        // Show downloads panel automatically
        downloadsPanel.setVisible(true);
        downloadsPanel.setManaged(true);
        updateDownloadsPanel();

        final String finalFileName = fileName;
        final String finalUrl = fileUrl;

        Task<java.io.File> task = new Task<>() {
            @Override protected java.io.File call() throws Exception {
                Path tmpDir = Path.of(System.getProperty("java.io.tmpdir"), "QuietPages");
                Files.createDirectories(tmpDir);
                Path dest = tmpDir.resolve(finalFileName);

                // Follow up to 10 redirects manually so we get the final URL
                String currentUrl = finalUrl;
                HttpURLConnection conn = null;
                for (int redirects = 0; redirects < 10; redirects++) {
                    conn = (HttpURLConnection) URI.create(currentUrl).toURL().openConnection();
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("User-Agent",
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                    "Chrome/120.0.0.0 Safari/537.36");
                    conn.setRequestProperty("Accept",
                            "application/epub+zip,application/pdf,application/octet-stream,*/*");
                    conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
                    conn.setConnectTimeout(20000);
                    conn.setReadTimeout(120000);
                    conn.setInstanceFollowRedirects(false); // handle manually
                    conn.connect();

                    int code = conn.getResponseCode();
                    if (code == 301 || code == 302 || code == 303
                            || code == 307 || code == 308) {
                        String loc = conn.getHeaderField("Location");
                        conn.disconnect();
                        if (loc == null) break;
                        // Handle relative redirects
                        if (loc.startsWith("/")) {
                            URL base = URI.create(currentUrl).toURL();
                            loc = base.getProtocol() + "://" + base.getHost() + loc;
                        }
                        currentUrl = loc;
                        continue;
                    }
                    if (code == 200) break;
                    conn.disconnect();
                    throw new IOException("HTTP " + code + " for: " + currentUrl);
                }

                if (conn == null) throw new IOException("No connection established");

                long contentLength = conn.getContentLengthLong();
                try (InputStream  in  = new BufferedInputStream(conn.getInputStream(), 65536);
                     OutputStream out = new BufferedOutputStream(
                             Files.newOutputStream(dest), 65536)) {
                    in.transferTo(out);
                }
                conn.disconnect();

                long fileSize = Files.size(dest);
                System.out.println("[Download] Saved " + fileSize + " bytes → " + dest);

                // Reject obviously incomplete files
                if (fileSize < 1024) {
                    Files.deleteIfExists(dest);
                    throw new IOException("Downloaded file too small (" + fileSize
                            + " bytes) — likely an error page, not an EPUB");
                }

                // Verify EPUB is a valid ZIP
                if (finalFileName.toLowerCase().endsWith(".epub")) {
                    try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(dest.toFile())) {
                        if (zip.size() == 0) {
                            Files.deleteIfExists(dest);
                            throw new IOException("Downloaded EPUB has no entries");
                        }
                    } catch (java.util.zip.ZipException ze) {
                        Files.deleteIfExists(dest);
                        throw new IOException("Downloaded file is not a valid EPUB: "
                                + ze.getMessage());
                    }
                }
                return dest.toFile();
            }
        };

        task.setOnSucceeded(e -> {
            java.io.File downloaded = task.getValue();
            com.quietpages.quietpages.model.Book book =
                    libraryService.importFile(downloaded);
            Platform.runLater(() -> {
                entry.setStatus(book != null
                        ? DownloadEntry.Status.COMPLETED
                        : DownloadEntry.Status.FAILED);
                updateDownloadsPanel();
            });
        });

        task.setOnFailed(e -> Platform.runLater(() -> {
            entry.setStatus(DownloadEntry.Status.FAILED);
            updateDownloadsPanel();
            System.err.println("[Download] Failed: " + task.getException().getMessage());
        }));

        Thread t = new Thread(task);
        t.setDaemon(true);
        t.start();
    }

    // ── Favicon helpers ───────────────────────────────────────────────────────
    @FunctionalInterface
    interface IconCallback { void onFetched(byte[] bytes); }

    private void loadFaviconAsync(String siteUrl, ImageView target, IconCallback callback) {
        Task<byte[]> task = new Task<>() {
            @Override protected byte[] call() { return fetchFaviconBytes(siteUrl); }
        };
        task.setOnSucceeded(e -> Platform.runLater(() -> {
            byte[] bytes = task.getValue();
            if (bytes != null && bytes.length > 0) {
                try { target.setImage(new Image(new ByteArrayInputStream(bytes))); }
                catch (Exception ignored) {}
            }
            if (callback != null) callback.onFetched(bytes);
        }));
        task.setOnFailed(e -> {
            if (callback != null) Platform.runLater(() -> callback.onFetched(null));
        });
        Thread t = new Thread(task);
        t.setDaemon(true);
        t.start();
    }

    private byte[] fetchFaviconBytes(String siteUrl) {
        try {
            if (!siteUrl.startsWith("http")) siteUrl = "https://" + siteUrl;
            // Encode spaces if present
            siteUrl = siteUrl.replace(" ", "%20");
            URL url = URI.create(siteUrl).toURL();
            String base = url.getProtocol() + "://" + url.getHost();

            // Try multiple locations — Google's service is most reliable fallback
            String[] candidates = {
                    base + "/favicon.ico",
                    base + "/favicon.png",
                    base + "/apple-touch-icon.png",
                    base + "/apple-touch-icon-precomposed.png",
                    "https://www.google.com/s2/favicons?domain=" + url.getHost() + "&sz=64"
            };

            for (String candidate : candidates) {
                try {
                    HttpURLConnection conn = (HttpURLConnection)
                            URI.create(candidate).toURL().openConnection();
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    conn.setRequestProperty("User-Agent",
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
                    conn.setInstanceFollowRedirects(true);
                    conn.connect();
                    if (conn.getResponseCode() == 200) {
                        try (InputStream is = conn.getInputStream()) {
                            byte[] bytes = is.readAllBytes();
                            if (bytes.length > 64) return bytes;  // reject tiny/empty responses
                        }
                    }
                    conn.disconnect();
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ── WebView accessors ─────────────────────────────────────────────────────
    private javafx.scene.web.WebView   getWebView() { return (javafx.scene.web.WebView)   webViewObj; }
    private javafx.scene.web.WebEngine getEngine()  { return (javafx.scene.web.WebEngine) engineObj;  }
}