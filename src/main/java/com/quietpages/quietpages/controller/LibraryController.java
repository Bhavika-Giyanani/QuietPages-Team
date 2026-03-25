package com.quietpages.quietpages.controller;

import com.quietpages.quietpages.model.Book;
import com.quietpages.quietpages.service.LibraryService;
import com.quietpages.quietpages.service.LibraryService.*;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Controller for library-view.fxml — the Library tab.
 *
 * Responsibilities:
 *  - Toolbar: filter dropdown, search, group, sort, add, multi-select
 *  - Book grid: render covers + labels, grouping headers
 *  - Right-click context menu on book cards
 *  - Book Info side panel
 *  - Edit Book Info dialog
 *  - Multi-select: checkboxes, select all, delete selected
 */
public class LibraryController {

    // ── FXML injections ───────────────────────────────────────────────────────
    @FXML private Label         currentFilterLabel;   // "All Books ▾"
    @FXML private Button        btnFilter;            // filter dropdown trigger
    @FXML private Button        btnSearch;
    @FXML private Button        btnGroup;
    @FXML private Button        btnSort;
    @FXML private Button        btnAdd;
    @FXML private Button        btnMultiSelect;

    // Multi-select extras (hidden until multi-select active)
    @FXML private Button        btnSelectAll;
    @FXML private Button        btnDeleteSelected;
    @FXML private HBox          multiSelectBar;       // contains Select All + Delete

    // Search bar (hidden until search active)
    @FXML private TextField     searchField;
    @FXML private Button        btnSearchClear;
    @FXML private HBox          searchBar;

    // Main grid
    @FXML private ScrollPane    scrollPane;
    @FXML private VBox          contentVBox;          // groups stacked vertically

    // Book Info side panel
    @FXML private VBox          bookInfoPanel;
    @FXML private ImageView     infoCoverImage;
    @FXML private Label         infoTitle;
    @FXML private Label         infoAuthor;
    @FXML private Label         infoProgressText;
    @FXML private Label         infoDateAdded;
    @FXML private Label         infoLastRead;
    @FXML private Label         infoWordCount;
    @FXML private Label         infoLineCount;
    @FXML private Label         infoDescription;
    @FXML private Label         infoSeries;
    @FXML private Label         infoLanguage;
    @FXML private Label         infoPublisher;
    @FXML private Label         infoGenre;
    @FXML private Label         infoFilePath;
    @FXML private Button        btnInfoOpenBook;
    @FXML private Button        btnInfoEditBookInfo;
    @FXML private Button        btnInfoClose;

    // ── State ─────────────────────────────────────────────────────────────────
    private final LibraryService service = LibraryService.getInstance();
    private ObservableList<Book> currentBooks = FXCollections.observableArrayList();

    private FilterOption activeFilter   = FilterOption.ALL;
    private GroupOption  activeGroup    = GroupOption.NONE;
    private SortOption   activeSort     = SortOption.LAST_READ_TIME;

    private boolean multiSelectMode     = false;
    private boolean searchMode          = false;

    // Map from book card root node → its controller (for selection management)
    private final Map<Node, BookCardController> cardControllers = new LinkedHashMap<>();

    // Currently selected book for Info panel
    private Book selectedBook = null;

    // ── Init ──────────────────────────────────────────────────────────────────
    @FXML
    public void initialize() {
        multiSelectBar.setVisible(false);
        multiSelectBar.setManaged(false);
        searchBar.setVisible(false);
        searchBar.setManaged(false);
        bookInfoPanel.setVisible(false);
        bookInfoPanel.setManaged(false);

        searchField.textProperty().addListener((obs, old, val) -> performSearch(val));

        loadBooks();
    }

    // ── Load / Render ─────────────────────────────────────────────────────────
    private void loadBooks() {
        Task<ObservableList<Book>> task = new Task<>() {
            @Override protected ObservableList<Book> call() {
                ObservableList<Book> books = service.getFilteredBooks(activeFilter);
                service.sort(books, activeSort);
                return books;
            }
        };
        task.setOnSucceeded(e -> {
            currentBooks = task.getValue();
            renderGrid();
        });
        new Thread(task).start();
    }

    private void renderGrid() {
        contentVBox.getChildren().clear();
        cardControllers.clear();

        if (currentBooks.isEmpty()) {
            showEmptyState();
            return;
        }

        if (activeGroup == GroupOption.NONE) {
            FlowPane flow = createFlowPane();
            for (Book book : currentBooks) {
                Node card = createBookCard(book);
                if (card != null) flow.getChildren().add(card);
            }
            contentVBox.getChildren().add(flow);
        } else {
            // Group books
            Map<String, List<Book>> grouped = new LinkedHashMap<>();
            for (Book b : currentBooks) {
                String key = activeGroup.getGroupKey(b);
                grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(b);
            }
            // Sort group keys
            List<String> keys = new ArrayList<>(grouped.keySet());
            Collections.sort(keys);

            for (String groupKey : keys) {
                Label groupHeader = new Label(groupKey);
                groupHeader.getStyleClass().add("group-header");
                VBox.setMargin(groupHeader, new Insets(16, 0, 8, 0));
                contentVBox.getChildren().add(groupHeader);

                Separator sep = new Separator();
                sep.getStyleClass().add("group-separator");
                contentVBox.getChildren().add(sep);

                FlowPane flow = createFlowPane();
                for (Book book : grouped.get(groupKey)) {
                    Node card = createBookCard(book);
                    if (card != null) flow.getChildren().add(card);
                }
                contentVBox.getChildren().add(flow);
            }
        }
    }

    private FlowPane createFlowPane() {
        FlowPane flow = new FlowPane();
        flow.setHgap(16);
        flow.setVgap(16);
        flow.setPadding(new Insets(12, 16, 12, 16));
        return flow;
    }

    private Node createBookCard(Book book) {
        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/com/quietpages/quietpages/book-card.fxml"));
            Node card = loader.load();
            BookCardController ctrl = loader.getController();
            ctrl.setBook(book);
            cardControllers.put(card, ctrl);

            // Left-click handler
            card.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY) {
                    if (multiSelectMode) {
                        ctrl.toggleSelected();
                    } else {
                        showBookInfo(book);
                    }
                }
            });

            // Right-click context menu
            card.setOnContextMenuRequested(e ->
                    showContextMenu(book, ctrl, card, e.getScreenX(), e.getScreenY()));

            return card;
        } catch (IOException e) {
            System.err.println("[Library] Failed to load book card: " + e.getMessage());
            return null;
        }
    }

    private void showEmptyState() {
        Label empty = new Label("No books found.\nClick + to add books.");
        empty.setStyle("-fx-text-fill: -qp-text-secondary; -fx-font-size: 14; -fx-text-alignment: center;");
        empty.setAlignment(Pos.CENTER);
        VBox box = new VBox(empty);
        box.setAlignment(Pos.CENTER);
        box.setPrefHeight(400);
        contentVBox.getChildren().add(box);
    }

    // ── Toolbar Actions ───────────────────────────────────────────────────────

    @FXML
    private void onFilterClicked() {
        ContextMenu menu = new ContextMenu();
        for (FilterOption opt : FilterOption.values()) {
            MenuItem item = new MenuItem(opt.getLabel());
            item.setOnAction(e -> {
                activeFilter = opt;
                currentFilterLabel.setText(opt.getLabel());
                loadBooks();
            });
            menu.getItems().add(item);
        }
        menu.show(btnFilter, javafx.geometry.Side.BOTTOM, 0, 0);
    }

    @FXML
    private void onSearchClicked() {
        searchMode = !searchMode;
        searchBar.setVisible(searchMode);
        searchBar.setManaged(searchMode);
        if (searchMode) {
            searchField.requestFocus();
        } else {
            searchField.clear();
            loadBooks();
        }
    }

    @FXML
    private void onSearchClear() {
        searchField.clear();
        searchMode = false;
        searchBar.setVisible(false);
        searchBar.setManaged(false);
        loadBooks();
    }

    private void performSearch(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            loadBooks();
            return;
        }
        Task<ObservableList<Book>> task = new Task<>() {
            @Override protected ObservableList<Book> call() {
                return service.search(keyword);
            }
        };
        task.setOnSucceeded(e -> {
            currentBooks = task.getValue();
            renderGrid();
        });
        new Thread(task).start();
    }

    @FXML
    private void onGroupClicked() {
        ContextMenu menu = new ContextMenu();
        for (GroupOption opt : GroupOption.values()) {
            MenuItem item = new MenuItem(opt.getLabel());
            if (opt == activeGroup) item.setGraphic(makeCheckIcon());
            item.setOnAction(e -> {
                activeGroup = opt;
                renderGrid();
            });
            menu.getItems().add(item);
        }
        menu.show(btnGroup, javafx.geometry.Side.BOTTOM, 0, 0);
    }

    @FXML
    private void onSortClicked() {
        ContextMenu menu = new ContextMenu();
        for (SortOption opt : SortOption.values()) {
            MenuItem item = new MenuItem(opt.getLabel());
            if (opt == activeSort) item.setGraphic(makeCheckIcon());
            item.setOnAction(e -> {
                activeSort = opt;
                service.sort(currentBooks, activeSort);
                renderGrid();
            });
            menu.getItems().add(item);
        }
        menu.show(btnSort, javafx.geometry.Side.BOTTOM, 0, 0);
    }

    @FXML
    private void onAddClicked() {
        ContextMenu menu = new ContextMenu();

        MenuItem addFile = new MenuItem("  Add book file(s)");
        addFile.setOnAction(e -> importFiles());

        MenuItem addFolder = new MenuItem("  Add folder");
        addFolder.setOnAction(e -> importFolder());

        menu.getItems().addAll(addFile, addFolder);
        menu.show(btnAdd, javafx.geometry.Side.BOTTOM, 0, 0);
    }

    private void importFiles() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Add Book Files");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("eBook Files", "*.epub", "*.pdf"),
                new FileChooser.ExtensionFilter("EPUB Files", "*.epub"),
                new FileChooser.ExtensionFilter("PDF Files",  "*.pdf")
        );
        Stage stage = (Stage) btnAdd.getScene().getWindow();
        List<File> files = chooser.showOpenMultipleDialog(stage);
        if (files == null || files.isEmpty()) return;

        Task<Integer> task = new Task<>() {
            @Override protected Integer call() {
                int count = 0;
                for (File f : files) {
                    if (service.importFile(f) != null) count++;
                }
                return count;
            }
        };
        task.setOnSucceeded(e -> {
            int added = task.getValue();
            if (added > 0) loadBooks();
            showNotification(added > 0 ? "Added " + added + " book(s)." : "No new books added.");
        });
        new Thread(task).start();
    }

    private void importFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Add Folder");
        Stage stage = (Stage) btnAdd.getScene().getWindow();
        File folder = chooser.showDialog(stage);
        if (folder == null) return;

        Task<Integer> task = new Task<>() {
            @Override protected Integer call() {
                return service.importFolder(folder);
            }
        };
        task.setOnSucceeded(e -> {
            int added = task.getValue();
            if (added > 0) loadBooks();
            showNotification("Added " + added + " book(s) from folder.");
        });
        new Thread(task).start();
    }

    @FXML
    private void onMultiSelectClicked() {
        multiSelectMode = !multiSelectMode;
        if (multiSelectMode) {
            if (!btnMultiSelect.getStyleClass().contains("active"))
                btnMultiSelect.getStyleClass().add("active");
        } else {
            btnMultiSelect.getStyleClass().remove("active");
        }

        multiSelectBar.setVisible(multiSelectMode);
        multiSelectBar.setManaged(multiSelectMode);

        // Reset all card selections
        for (BookCardController ctrl : cardControllers.values()) {
            ctrl.setMultiSelectMode(multiSelectMode);
        }

        // Hide book info panel when entering multi-select
        if (multiSelectMode) {
            bookInfoPanel.setVisible(false);
            bookInfoPanel.setManaged(false);
        }
    }

    @FXML
    private void onSelectAll() {
        boolean allSelected = cardControllers.values().stream().allMatch(BookCardController::isSelected);
        for (BookCardController ctrl : cardControllers.values()) {
            ctrl.setSelected(!allSelected);
        }
    }

    @FXML
    private void onDeleteSelected() {
        List<Integer> ids = cardControllers.values().stream()
                .filter(BookCardController::isSelected)
                .map(c -> c.getBook().getId())
                .collect(Collectors.toList());

        if (ids.isEmpty()) return;

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Remove Books");
        alert.setHeaderText("Remove " + ids.size() + " book(s)?");
        alert.setContentText("This removes them from your library but does not delete the files.");
        alert.getDialogPane().getStylesheets().add(
                Objects.requireNonNull(getClass().getResource(
                        "/com/quietpages/quietpages/library.css")).toExternalForm());

        Optional<ButtonType> result = alert.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            service.removeAll(ids);
            // Exit multi-select and reload
            multiSelectMode = false;
            btnMultiSelect.getStyleClass().remove("active");
            multiSelectBar.setVisible(false);
            multiSelectBar.setManaged(false);
            loadBooks();
        }
    }

    // ── Context Menu ──────────────────────────────────────────────────────────

    private void showContextMenu(Book book, BookCardController ctrl, Node card,
                                 double screenX, double screenY) {
        ContextMenu menu = new ContextMenu();
        menu.getStyleClass().add("library-context-menu");

        MenuItem open     = menuItem("Open Book");
        MenuItem fav      = menuItem(book.isFavourite() ? "Remove Favourite" : "Add Favourite");
        MenuItem info     = menuItem("Book Info");
        MenuItem edit     = menuItem("Edit Book Info");
        MenuItem remove   = menuItem("Remove");
        MenuItem pin      = menuItem(book.isPinnedToStart() ? "Unpin from Start" : "Pin to Start");

        open.setOnAction(e -> openBook(book));
        fav.setOnAction(e -> {
            service.toggleFavourite(book);
            ctrl.refresh();
        });
        info.setOnAction(e -> showBookInfo(book));
        edit.setOnAction(e -> showEditBookInfo(book, ctrl));
        remove.setOnAction(e -> removeBook(book));
        pin.setOnAction(e -> service.togglePinned(book));

        menu.getItems().addAll(open, fav, info, edit, remove, pin);
        menu.show(card, screenX, screenY);
    }

    private MenuItem menuItem(String text) {
        MenuItem item = new MenuItem(text);
        return item;
    }

    // ── Book Info Panel ───────────────────────────────────────────────────────

    private void showBookInfo(Book book) {
        selectedBook = book;
        bookInfoPanel.setVisible(true);
        bookInfoPanel.setManaged(true);

        Image cover = book.getCoverImage();
        if (cover != null) infoCoverImage.setImage(cover);

        infoTitle.setText(book.getTitle());
        infoAuthor.setText(book.getAuthor());
        infoProgressText.setText("Percentage read : " + book.getReadingProgressText());

        String added = book.getDateAdded() != null
                ? book.getDateAdded().toString().replace("T", " ") : "—";
        infoDateAdded.setText("Date added : " + added);

        String lastRead = book.getLastRead() != null
                ? book.getLastRead().toString().replace("T", " ") : "—";
        infoLastRead.setText("Last read : " + lastRead);

        infoWordCount.setText("Word count : " + book.getWordCount());
        infoLineCount.setText("Line count : " + book.getLineCount());
        infoDescription.setText("Description : " + book.getDescription());
        infoSeries.setText("Series : " + (book.getSeries().isEmpty() ? "—" :
                "[" + book.getSeriesNumber() + "]"));
        infoLanguage.setText("Language : " + book.getLanguage());
        infoPublisher.setText("Publisher : " + book.getPublisher());
        infoGenre.setText("Genre : " + book.getGenre());
        infoFilePath.setText("File path : " + book.getFilePath());
    }

    @FXML private void onInfoOpenBook()     { if (selectedBook != null) openBook(selectedBook); }
    @FXML private void onInfoEditBookInfo() { if (selectedBook != null) {
        showEditBookInfo(selectedBook, null);
    }}
    @FXML private void onInfoClose() {
        bookInfoPanel.setVisible(false);
        bookInfoPanel.setManaged(false);
        selectedBook = null;
    }

    // ── Edit Book Info Dialog ─────────────────────────────────────────────────

    private void showEditBookInfo(Book book, BookCardController ctrl) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource(
                    "/com/quietpages/quietpages/edit-book-info.fxml"));
            DialogPane dialogPane = loader.load();
            EditBookInfoController editCtrl = loader.getController();
            editCtrl.setBook(book);

            Dialog<ButtonType> dialog = new Dialog<>();
            dialog.setDialogPane(dialogPane);
            dialog.setTitle("Edit book information");
            dialog.getDialogPane().getStylesheets().add(
                    Objects.requireNonNull(getClass().getResource(
                            "/com/quietpages/quietpages/library.css")).toExternalForm());

            Optional<ButtonType> result = dialog.showAndWait();
            if (result.isPresent() && result.get().getButtonData() == ButtonBar.ButtonData.OK_DONE) {
                editCtrl.applyChanges();
                service.save(book);
                if (ctrl != null) ctrl.refresh();
                if (selectedBook != null && selectedBook.getId() == book.getId()) {
                    showBookInfo(book);
                }
                loadBooks();
            }
        } catch (IOException e) {
            System.err.println("[Library] Failed to open edit dialog: " + e.getMessage());
        }
    }

    // ── Operations ────────────────────────────────────────────────────────────

    private void openBook(Book book) {
        // Notify Home/Reader tab via shared event bus or direct controller reference
        // For now: print to console. The Reader tab teammate hooks in here.
        System.out.println("[Library] Open book requested: " + book.getTitle());
        // TODO: HelloApplication.getInstance().showReaderTab(book);
    }

    private void removeBook(Book book) {
        Alert alert = createStyledAlert(
                "Remove Book",
                "Remove \"" + book.getTitle() + "\"?",
                "This removes it from your library but does not delete the file.");
        Optional<ButtonType> result = alert.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            service.remove(book.getId());
            if (selectedBook != null && selectedBook.getId() == book.getId()) {
                bookInfoPanel.setVisible(false);
                bookInfoPanel.setManaged(false);
            }
            loadBooks();
        }
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private Node makeCheckIcon() {
        Label lbl = new Label("✓");
        // Use hardcoded hex — CSS variables don't resolve in MenuItem graphics
        lbl.setStyle("-fx-text-fill: #C0284A; -fx-font-weight: bold;");
        return lbl;
    }
    // ── Styled alert helper ───────────────────────────────────────────────────
    private Alert createStyledAlert(String title, String header, String content) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setGraphic(null);

        javafx.scene.control.Label headerLbl = new javafx.scene.control.Label(header);
        headerLbl.setStyle(
                "-fx-font-size: 14px; -fx-font-weight: bold;" +
                        "-fx-text-fill: #DDDDDD; -fx-wrap-text: true; -fx-max-width: 340;");

        javafx.scene.control.Label contentLbl = new javafx.scene.control.Label(content);
        contentLbl.setStyle(
                "-fx-font-size: 12px; -fx-text-fill: #999999;" +
                        "-fx-wrap-text: true; -fx-max-width: 340;");

        javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(8, headerLbl, contentLbl);
        box.setPadding(new javafx.geometry.Insets(20, 20, 12, 20));
        box.setStyle("-fx-background-color: #333333;");

        alert.getDialogPane().setHeader(null);
        alert.getDialogPane().setGraphic(null);
        alert.getDialogPane().setContent(box);

        alert.getDialogPane().setStyle(
                "-fx-background-color: #333333;" +
                        "-fx-border-color: #3A3A3A;" +
                        "-fx-border-width: 1;" +
                        "-fx-border-radius: 6;" +
                        "-fx-background-radius: 6;");

        alert.getDialogPane().getStylesheets().add(
                java.util.Objects.requireNonNull(getClass().getResource(
                        "/com/quietpages/quietpages/library.css")).toExternalForm());

        javafx.scene.Node buttonBar = alert.getDialogPane().lookup(".button-bar");
        if (buttonBar != null) {
            buttonBar.setStyle("-fx-background-color: #333333; -fx-padding: 0 12 12 12;");
        }

        return alert;
    }
    private void showNotification(String message) {
        // Simple tooltip-style notification — can be upgraded with ControlsFX Notifications
        Platform.runLater(() -> {
            Tooltip tip = new Tooltip(message);
            tip.setAutoHide(true);
            tip.show(btnAdd.getScene().getWindow(),
                    btnAdd.localToScreen(0, 0).getX(),
                    btnAdd.localToScreen(0, 0).getY() + 30);
            new java.util.Timer().schedule(new java.util.TimerTask() {
                public void run() { Platform.runLater(tip::hide); }
            }, 2000);
        });
    }
}