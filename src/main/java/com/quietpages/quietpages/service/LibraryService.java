package com.quietpages.quietpages.service;

import com.quietpages.quietpages.db.BookDAO;
import com.quietpages.quietpages.model.Book;
import com.quietpages.quietpages.util.EpubImporter;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.io.File;
import java.util.Comparator;
import java.util.List;

/**
 * Service layer for all Library tab operations.
 * Controllers call this — never the DAO directly.
 *
 * Thread note: heavy operations (import, scan folder) must be
 * called from a background Task; UI updates via Platform.runLater().
 */
public class LibraryService {

    private static LibraryService instance;
    private final BookDAO dao = new BookDAO();

    private LibraryService() {
    }

    public static synchronized LibraryService getInstance() {
        if (instance == null)
            instance = new LibraryService();
        return instance;
    }

    // ── Fetch ─────────────────────────────────────────────────────────────────

    public ObservableList<Book> getAllBooks() {
        return FXCollections.observableArrayList(dao.findAll());
    }

    public ObservableList<Book> getFilteredBooks(FilterOption filter) {
        List<Book> books = switch (filter) {
            case ALL -> dao.findAll();
            case FAVOURITES -> dao.findFavourites();
            case DOWNLOADS -> dao.findDownloads();
            case READING -> dao.findByStatus(Book.ReadingStatus.READING);
            case NOT_STARTED -> dao.findByStatus(Book.ReadingStatus.NOT_STARTED);
            case COMPLETED -> dao.findByStatus(Book.ReadingStatus.COMPLETED);
        };
        return FXCollections.observableArrayList(books);
    }

    public ObservableList<Book> search(String keyword) {
        return FXCollections.observableArrayList(dao.search(keyword));
    }

    // ── Sort ──────────────────────────────────────────────────────────────────

    public void sort(ObservableList<Book> books, SortOption sort) {
        Comparator<Book> cmp = switch (sort) {
            case TITLE -> Comparator.comparing(b -> b.getTitle().toLowerCase());
            case SERIES_NUMBER -> Comparator.comparingInt(Book::getSeriesNumber);
            case IMPORT_TIME -> Comparator.comparing(Book::getDateAdded,
                    Comparator.nullsLast(Comparator.reverseOrder()));
            case LAST_READ_TIME -> Comparator.comparing(Book::getLastRead,
                    Comparator.nullsLast(Comparator.reverseOrder()));
            case READING_PROGRESS -> Comparator.comparingDouble(Book::getReadingProgress).reversed();
        };
        books.sort(cmp);
    }

    // ── Import ────────────────────────────────────────────────────────────────

    /**
     * Imports a single EPUB or PDF file.
     * Returns the imported Book on success, null if skipped (already exists) or
     * failed.
     */
    public Book importFile(File file) {
        if (dao.existsByFilePath(file.getAbsolutePath())) {
            System.out.println("[Library] Skipped (already in library): " + file.getName());
            return null;
        }
        try {
            Book book;
            if (file.getName().toLowerCase().endsWith(".epub")) {
                book = EpubImporter.importEpub(file);
            } else {
                // PDF: minimal metadata
                book = new Book();
                book.setFilePath(file.getAbsolutePath());
                book.setFileType("pdf");
                String name = file.getName();
                book.setTitle(name.substring(0, name.lastIndexOf('.')));
            }
            if (dao.insert(book))
                return book;
        } catch (Exception e) {
            System.err.println("[Library] Import failed for " + file.getName() + ": " + e.getMessage());
        }
        return null;
    }

    /** Scans a folder recursively and imports all EPUB/PDF files found. */
    public int importFolder(File folder) {
        int count = 0;
        File[] files = folder.listFiles();
        if (files == null)
            return 0;
        for (File f : files) {
            if (f.isDirectory()) {
                count += importFolder(f);
            } else if (isSupportedFile(f)) {
                if (importFile(f) != null)
                    count++;
            }
        }
        return count;
    }

    // ── Mutations ─────────────────────────────────────────────────────────────

    public boolean save(Book book) {
        return dao.update(book);
    }

    public boolean remove(int id) {
        return dao.delete(id);
    }

    public boolean removeAll(List<Integer> ids) {
        return dao.deleteAll(ids);
    }

    public void toggleFavourite(Book book) {
        book.setFavourite(!book.isFavourite());
        dao.setFavourite(book.getId(), book.isFavourite());
    }

    public void togglePinned(Book book) {
        book.setPinnedToStart(!book.isPinnedToStart());
        dao.setPinned(book.getId(), book.isPinnedToStart());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isSupportedFile(File f) {
        String name = f.getName().toLowerCase();
        return name.endsWith(".epub") || name.endsWith(".pdf");
    }

    // ── Enums exposed to controllers ──────────────────────────────────────────

    public enum FilterOption {
        ALL("All Books"),
        FAVOURITES("Favourites"),
        DOWNLOADS("Downloads"),
        READING("Reading"),
        NOT_STARTED("Not Started"),
        COMPLETED("Completed");

        private final String label;

        FilterOption(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public enum GroupOption {
        NONE("(None)"),
        AUTHOR("Author"),
        FILE_PATH("File Path"),
        FILE_TYPE("File Type"),
        GENRE("Genre"),
        LANGUAGE("Language"),
        PUBLISHER("Publisher"),
        SERIES("Series");

        private final String label;

        GroupOption(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }

        @Override
        public String toString() {
            return label;
        }

        public String getGroupKey(Book book) {
            return switch (this) {
                case AUTHOR -> book.getAuthor();
                case FILE_PATH -> {
                    String p = book.getFilePath();
                    int idx = p.lastIndexOf(File.separatorChar);
                    yield idx >= 0 ? p.substring(0, idx) : p;
                }
                case FILE_TYPE -> book.getFileType().toUpperCase();
                case GENRE -> book.getGenre().isEmpty() ? "Unknown" : book.getGenre();
                case LANGUAGE -> book.getLanguage().isEmpty() ? "Unknown" : book.getLanguage();
                case PUBLISHER -> book.getPublisher().isEmpty() ? "Unknown" : book.getPublisher();
                case SERIES -> book.getSeries().isEmpty() ? "No Series" : book.getSeries();
                default -> "";
            };
        }
    }

    public enum SortOption {
        TITLE("Title"),
        SERIES_NUMBER("Series Number"),
        IMPORT_TIME("Import time"),
        LAST_READ_TIME("Last read time"),
        READING_PROGRESS("Reading progress");

        private final String label;

        SortOption(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }

        @Override
        public String toString() {
            return label;
        }
    }
}