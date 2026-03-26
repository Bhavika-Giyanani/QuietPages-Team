package com.quietpages.quietpages.controller;

import com.quietpages.quietpages.HelloController;
import com.quietpages.quietpages.db.BookDAO;
import com.quietpages.quietpages.model.Book;
import com.quietpages.quietpages.service.LibraryService;
import com.quietpages.quietpages.service.LibraryService.FilterOption;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;

import java.util.List;
import java.util.function.Consumer;

public class HomeController {

    @FXML
    private HBox recentBox;
    @FXML
    private HBox favBox;

    private final LibraryService service = LibraryService.getInstance();
    private final BookDAO dao = new BookDAO();

    // Callback wired by HelloController so clicking a card opens the reader
    private Consumer<Book> onOpenBook;

    public void setOnOpenBook(Consumer<Book> callback) {
        this.onOpenBook = callback;
    }

    @FXML
    public void initialize() {
        loadRecent();
        loadFavourites();
    }

    // ── Recent books ──────────────────────────────────────────────────────────
    private void loadRecent() {
        List<Book> books = new java.util.ArrayList<>(service.getFilteredBooks(FilterOption.ALL));
        recentBox.getChildren().clear();
        for (int i = 0; i < Math.min(5, books.size()); i++) {
            Node card = createBookCard(books.get(i));
            if (card != null)
                recentBox.getChildren().add(card);
        }
    }

    // ── Favourite books ───────────────────────────────────────────────────────
    private void loadFavourites() {
        List<Book> books = dao.findFavourites();
        favBox.getChildren().clear();
        for (int i = 0; i < Math.min(5, books.size()); i++) {
            Node card = createBookCard(books.get(i));
            if (card != null)
                favBox.getChildren().add(card);
        }
    }

    // ── Create book card reusing library's book-card.fxml ─────────────────────
    private Node createBookCard(Book book) {
        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/com/quietpages/quietpages/book-card.fxml"));
            Node card = loader.load();
            BookCardController ctrl = loader.getController();
            ctrl.setBook(book);

            card.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY) {
                    openBook(book);
                }
            });
            return card;
        } catch (Exception e) {
            System.err.println("[Home] Card load error: " + e.getMessage());
            return null;
        }
    }

    // ── Open book — uses callback if set, falls back to HelloController.instance
    private void openBook(Book book) {
        if (onOpenBook != null) {
            // Preferred: HelloController wired this callback when loading home-view.fxml
            onOpenBook.accept(book);
        } else if (HelloController.instance != null) {
            // Fallback: use static instance (works even if callback not wired)
            HelloController.instance.openReader(book,
                    HelloController.instance.getBtnHome());
        } else {
            System.out.println("[Home] Cannot open book — no callback or instance available");
        }
    }

    // ── Navigation ────────────────────────────────────────────────────────────
    @FXML
    private void onOpenLibrary() {
        if (HelloController.instance != null)
            HelloController.instance.goToLibrary();
    }

    @FXML
    private void onSeeMore() {
        if (HelloController.instance != null)
            HelloController.instance.goToLibrary();
    }
}