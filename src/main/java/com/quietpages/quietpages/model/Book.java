package com.quietpages.quietpages.model;

import javafx.beans.property.*;
import javafx.scene.image.Image;

import java.time.LocalDateTime;

/**
 * Represents a book in the QuietPages library.
 * All fields match what is stored in SQLite and displayed in the Library tab.
 */
public class Book {

    // ── Identity ──────────────────────────────────────────────────────────────
    private final IntegerProperty id = new SimpleIntegerProperty();

    // ── File ──────────────────────────────────────────────────────────────────
    private final StringProperty filePath  = new SimpleStringProperty("");
    private final StringProperty fileType  = new SimpleStringProperty("epub"); // epub | pdf

    // ── Metadata (from EPUB or user-edited) ───────────────────────────────────
    private final StringProperty title      = new SimpleStringProperty("Unknown Title");
    private final StringProperty author     = new SimpleStringProperty("Unknown Author");
    private final StringProperty publisher  = new SimpleStringProperty("");
    private final StringProperty language   = new SimpleStringProperty("en");
    private final StringProperty genre      = new SimpleStringProperty("");
    private final StringProperty series     = new SimpleStringProperty("");
    private final IntegerProperty seriesNumber = new SimpleIntegerProperty(0);
    private final StringProperty description = new SimpleStringProperty("");

    // ── Stats ─────────────────────────────────────────────────────────────────
    private final IntegerProperty wordCount  = new SimpleIntegerProperty(0);
    private final IntegerProperty lineCount  = new SimpleIntegerProperty(0);
    private final DoubleProperty  readingProgress = new SimpleDoubleProperty(0.0); // 0.0–1.0

    // ── Timestamps ────────────────────────────────────────────────────────────
    private LocalDateTime dateAdded;
    private LocalDateTime lastRead;

    // ── Flags ─────────────────────────────────────────────────────────────────
    private final BooleanProperty favourite  = new SimpleBooleanProperty(false);
    private final BooleanProperty pinnedToStart = new SimpleBooleanProperty(false);

    /**
     * Reading status: NOT_STARTED | READING | COMPLETED | DOWNLOADED
     * "Downloaded" maps to the Downloads filter in the dropdown.
     */
    public enum ReadingStatus { NOT_STARTED, READING, COMPLETED, DOWNLOADED }
    private final ObjectProperty<ReadingStatus> readingStatus =
            new SimpleObjectProperty<>(ReadingStatus.NOT_STARTED);

    // ── Cover image (loaded lazily, not persisted as object) ──────────────────
    /** Raw bytes stored in DB; loaded into Image on demand. */
    private byte[] coverImageData;
    private transient Image coverImage; // cached JavaFX Image

    // ── Constructors ──────────────────────────────────────────────────────────
    public Book() {}

    public Book(int id, String title, String author, String filePath, String fileType) {
        this.id.set(id);
        this.title.set(title);
        this.author.set(author);
        this.filePath.set(filePath);
        this.fileType.set(fileType);
    }

    // ── JavaFX property accessors ─────────────────────────────────────────────
    public IntegerProperty idProperty()               { return id; }
    public StringProperty  titleProperty()            { return title; }
    public StringProperty  authorProperty()           { return author; }
    public StringProperty  filePathProperty()         { return filePath; }
    public StringProperty  fileTypeProperty()         { return fileType; }
    public StringProperty  publisherProperty()        { return publisher; }
    public StringProperty  languageProperty()         { return language; }
    public StringProperty  genreProperty()            { return genre; }
    public StringProperty  seriesProperty()           { return series; }
    public IntegerProperty seriesNumberProperty()     { return seriesNumber; }
    public StringProperty  descriptionProperty()      { return description; }
    public IntegerProperty wordCountProperty()        { return wordCount; }
    public IntegerProperty lineCountProperty()        { return lineCount; }
    public DoubleProperty  readingProgressProperty()  { return readingProgress; }
    public BooleanProperty favouriteProperty()        { return favourite; }
    public BooleanProperty pinnedToStartProperty()    { return pinnedToStart; }
    public ObjectProperty<ReadingStatus> readingStatusProperty() { return readingStatus; }

    // ── Plain getters / setters ───────────────────────────────────────────────
    public int    getId()               { return id.get(); }
    public void   setId(int v)          { id.set(v); }

    public String getTitle()            { return title.get(); }
    public void   setTitle(String v)    { title.set(v); }

    public String getAuthor()           { return author.get(); }
    public void   setAuthor(String v)   { author.set(v); }

    public String getFilePath()         { return filePath.get(); }
    public void   setFilePath(String v) { filePath.set(v); }

    public String getFileType()         { return fileType.get(); }
    public void   setFileType(String v) { fileType.set(v); }

    public String getPublisher()        { return publisher.get(); }
    public void   setPublisher(String v){ publisher.set(v); }

    public String getLanguage()         { return language.get(); }
    public void   setLanguage(String v) { language.set(v); }

    public String getGenre()            { return genre.get(); }
    public void   setGenre(String v)    { genre.set(v); }

    public String getSeries()           { return series.get(); }
    public void   setSeries(String v)   { series.set(v); }

    public int    getSeriesNumber()     { return seriesNumber.get(); }
    public void   setSeriesNumber(int v){ seriesNumber.set(v); }

    public String getDescription()      { return description.get(); }
    public void   setDescription(String v){ description.set(v); }

    public int    getWordCount()        { return wordCount.get(); }
    public void   setWordCount(int v)   { wordCount.set(v); }

    public int    getLineCount()        { return lineCount.get(); }
    public void   setLineCount(int v)   { lineCount.set(v); }

    public double getReadingProgress()        { return readingProgress.get(); }
    public void   setReadingProgress(double v){ readingProgress.set(v); }

    public LocalDateTime getDateAdded()           { return dateAdded; }
    public void          setDateAdded(LocalDateTime v){ dateAdded = v; }

    public LocalDateTime getLastRead()            { return lastRead; }
    public void          setLastRead(LocalDateTime v){ lastRead = v; }

    public boolean isFavourite()          { return favourite.get(); }
    public void    setFavourite(boolean v){ favourite.set(v); }

    public boolean isPinnedToStart()           { return pinnedToStart.get(); }
    public void    setPinnedToStart(boolean v) { pinnedToStart.set(v); }

    public ReadingStatus getReadingStatus()           { return readingStatus.get(); }
    public void          setReadingStatus(ReadingStatus v){ readingStatus.set(v); }

    public byte[] getCoverImageData()           { return coverImageData; }
    public void   setCoverImageData(byte[] v)   { coverImageData = v; coverImage = null; }

    /** Returns cached JavaFX Image, loading from bytes if needed. */
    public Image getCoverImage() {
        if (coverImage == null && coverImageData != null && coverImageData.length > 0) {
            try {
                coverImage = new Image(new java.io.ByteArrayInputStream(coverImageData));
            } catch (Exception ignored) {}
        }
        return coverImage;
    }

    /** Human-readable reading progress percentage. */
    public String getReadingProgressText() {
        return (int)(readingProgress.get() * 100) + " %";
    }

    @Override
    public String toString() {
        return "Book{id=" + getId() + ", title='" + getTitle() + "', author='" + getAuthor() + "'}";
    }
}