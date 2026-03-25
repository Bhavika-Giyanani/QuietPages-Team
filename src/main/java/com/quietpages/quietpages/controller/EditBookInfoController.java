package com.quietpages.quietpages.controller;

import com.quietpages.quietpages.model.Book;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Controller for edit-book-info.fxml.
 * Manages the "Edit book information" dialog shown from both
 * the right-click context menu and the Book Info panel.
 */
public class EditBookInfoController {

    @FXML
    private ImageView coverPreview;
    @FXML
    private Button btnSelectCover;
    @FXML
    private Button btnResetCover;
    @FXML
    private TextField titleField;
    @FXML
    private TextField authorField;
    @FXML
    private TextField languageField;
    @FXML
    private TextField publisherField;

    private Book book;
    private byte[] newCoverBytes = null; // null = no change
    private boolean coverReset = false; // true = revert to embedded cover

    public void setBook(Book book) {
        this.book = book;

        titleField.setText(book.getTitle());
        authorField.setText(book.getAuthor());
        languageField.setText(book.getLanguage());
        publisherField.setText(book.getPublisher());

        Image cover = book.getCoverImage();
        if (cover != null)
            coverPreview.setImage(cover);
    }

    @FXML
    private void onSelectCover() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select Cover Image");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Image Files", "*.png", "*.jpg", "*.jpeg", "*.webp"));
        Stage stage = (Stage) coverPreview.getScene().getWindow();
        File file = chooser.showOpenDialog(stage);
        if (file == null)
            return;
        try {
            newCoverBytes = Files.readAllBytes(file.toPath());
            coverPreview.setImage(new Image(file.toURI().toString()));
            coverReset = false;
        } catch (IOException e) {
            System.err.println("[Edit] Failed to load cover: " + e.getMessage());
        }
    }

    @FXML
    private void onResetCover() {
        // Reset to original embedded cover
        newCoverBytes = null;
        coverReset = true;
        // We'd need to re-parse from file; for now just clear
        coverPreview.setImage(null);
    }

    /**
     * Called by LibraryController when the user presses Save.
     * Applies all field changes back to the Book object.
     */
    public void applyChanges() {
        if (book == null)
            return;
        book.setTitle(titleField.getText().trim());
        book.setAuthor(authorField.getText().trim());
        book.setLanguage(languageField.getText().trim());
        book.setPublisher(publisherField.getText().trim());
        if (newCoverBytes != null) {
            book.setCoverImageData(newCoverBytes);
        }
    }
}