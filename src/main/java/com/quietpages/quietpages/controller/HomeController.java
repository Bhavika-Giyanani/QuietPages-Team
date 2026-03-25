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

public class HomeController {

    @FXML
    private HBox recentBox;
    @FXML
    private HBox favBox;

    private final LibraryService service = LibraryService.getInstance();
    private final BookDAO dao = new BookDAO();

    @FXML
    public void initialize() {
        loadRecent();
        loadFavourites();
    }

    // ── RECENT BOOKS (FROM SERVICE) ─────────
    private void loadRecent() {
        List<Book> books = service.getFilteredBooks(FilterOption.ALL);

        recentBox.getChildren().clear();

        for (int i = 0; i < Math.min(5, books.size()); i++) {
            Node card = createBookCard(books.get(i));
            if (card != null) {
                recentBox.getChildren().add(card);
            }
        }
    }

    // ── FAVOURITE BOOKS (FROM DAO - SAFE) ───
    private void loadFavourites() {
        List<Book> books = dao.findFavourites();

        favBox.getChildren().clear();

        for (int i = 0; i < Math.min(5, books.size()); i++) {
            Node card = createBookCard(books.get(i));
            if (card != null) {
                favBox.getChildren().add(card);
            }
        }
    }

    // ── CREATE CARD (REUSE LIBRARY) ─────────
    private Node createBookCard(Book book) {
        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/com/quietpages/quietpages/book-card.fxml")
            );

            Node card = loader.load();

            BookCardController ctrl = loader.getController();
            ctrl.setBook(book);

            // CLICK → OPEN BOOK
            card.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY) {
                    openBook(book);
                }
            });

            return card;

        } catch (Exception e) {
            System.out.println("[Home] Error: " + e.getMessage());
            return null;
        }
    }

    // ── SAME AS LIBRARY ─────────────────────
    private void openBook(Book book) {
        System.out.println("[Home] Open book: " + book.getTitle());
    }

    // ── NAVIGATION ──────────────────────────
    @FXML
    private void onOpenLibrary() {
        if (HelloController.instance != null) {
            HelloController.instance.goToLibrary();
        }
    }

    @FXML
    private void onSeeMore() {
        if (HelloController.instance != null) {
            HelloController.instance.goToLibrary();
        }
    }
}
