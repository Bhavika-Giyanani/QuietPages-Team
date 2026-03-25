package com.quietpages.quietpages.controller;

import com.quietpages.quietpages.model.Book;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Text;

import java.io.InputStream;
import java.util.Objects;

/**
 * Controller for a single book card in the library grid.
 * Loaded from book-card.fxml.
 */
public class BookCardController {

    @FXML
    private VBox rootCard;
    @FXML
    private StackPane coverPane;
    @FXML
    private ImageView coverImage;
    @FXML
    private Label titleLabel;
    @FXML
    private StackPane checkOverlay; // blue tick overlay in multi-select mode
    @FXML
    private Rectangle selectionBorder;

    private Book book;
    private boolean selected = false;

    @FXML
    public void initialize() {
        checkOverlay.setVisible(false);
        selectionBorder.setVisible(false);
    }

    public void setBook(Book book) {
        this.book = book;
        refresh();
    }

    public Book getBook() {
        return book;
    }

    public void refresh() {
        if (book == null)
            return;

        // Cover image
        Image cover = book.getCoverImage();
        if (cover != null) {
            coverImage.setImage(cover);
        } else {
            // Fallback placeholder
            coverImage.setImage(loadPlaceholder());
        }

        // Title (truncated)
        String title = book.getTitle();
        if (title.length() > 20)
            title = title.substring(0, 18) + "...";
        titleLabel.setText(title);
    }

    // ── Multi-select mode ─────────────────────────────────────────────────────
    public void setMultiSelectMode(boolean enabled) {
        if (!enabled) {
            selected = false;
            checkOverlay.setVisible(false);
            selectionBorder.setVisible(false);
        }
    }

    public boolean isSelected() {
        return selected;
    }

    public void toggleSelected() {
        selected = !selected;
        checkOverlay.setVisible(selected);
        selectionBorder.setVisible(selected);
    }

    public void setSelected(boolean sel) {
        selected = sel;
        checkOverlay.setVisible(sel);
        selectionBorder.setVisible(sel);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private Image loadPlaceholder() {
        try (InputStream is = getClass().getResourceAsStream(
                "/com/quietpages/quietpages/images/book_placeholder.png")) {
            if (is != null)
                return new Image(is);
        } catch (Exception ignored) {
        }
        return null;
    }
}