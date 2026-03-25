package com.quietpages.quietpages.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import com.quietpages.quietpages.model.Book;

/**
 * Data Access Object for the books table.
 * All Library tab database operations go through here.
 */
public class BookDAO {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Connection conn() {
        return DatabaseManager.getInstance().getConnection();
    }

    // ── INSERT ────────────────────── ──────────────────────────────────────────
    /**
     * Inserts a new book. Sets the generated ID back on the book object.
     * Returns true on success.
     */
    public boolean insert(Book book) {
        String sql = """
                INSERT INTO books
                  (title, author, file_path, file_type, publisher, language, genre,
                   series, series_number, description, word_count, line_count,
                   reading_progress, reading_status, favourite, pinned_to_start,
                   date_added, last_read, cover_image)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """;
        try (PreparedStatement ps = conn().prepareStatement(sql,
                Statement.RETURN_GENERATED_KEYS)) {
            bindInsert(ps, book);
            int rows = ps.executeUpdate();
            if (rows > 0) {
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next())
                        book.setId(keys.getInt(1));
                }
                return true;
            }
        } catch (SQLException e) {
            System.err.println("[BookDAO] insert failed: " + e.getMessage());
        }
        return false;
    }

    // ── UPDATE ────────────────────────────────────────────────────────────────
    public boolean update(Book book) {
        String sql = """
                UPDATE books SET
                  title=?, author=?, file_path=?, file_type=?, publisher=?, language=?,
                  genre=?, series=?, series_number=?, description=?, word_count=?,
                  line_count=?, reading_progress=?, reading_status=?, favourite=?,
                  pinned_to_start=?, last_read=?, cover_image=?
                WHERE id=?
                """;
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, book.getTitle());
            ps.setString(2, book.getAuthor());
            ps.setString(3, book.getFilePath());
            ps.setString(4, book.getFileType());
            ps.setString(5, book.getPublisher());
            ps.setString(6, book.getLanguage());
            ps.setString(7, book.getGenre());
            ps.setString(8, book.getSeries());
            ps.setInt(9, book.getSeriesNumber());
            ps.setString(10, book.getDescription());
            ps.setInt(11, book.getWordCount());
            ps.setInt(12, book.getLineCount());
            ps.setDouble(13, book.getReadingProgress());
            ps.setString(14, book.getReadingStatus().name());
            ps.setInt(15, book.isFavourite() ? 1 : 0);
            ps.setInt(16, book.isPinnedToStart() ? 1 : 0);
            ps.setString(17, book.getLastRead() != null ? book.getLastRead().format(FMT) : null);
            ps.setBytes(18, book.getCoverImageData());
            ps.setInt(19, book.getId());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[BookDAO] update failed: " + e.getMessage());
            return false;
        }
    }

    // ── DELETE ────────────────────────────────────────────────────────────────
    public boolean delete(int id) {
        try (PreparedStatement ps = conn().prepareStatement(
                "DELETE FROM books WHERE id=?")) {
            ps.setInt(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[BookDAO] delete failed: " + e.getMessage());
            return false;
        }
    }

    public boolean deleteAll(List<Integer> ids) {
        if (ids.isEmpty())
            return true;
        StringBuilder sb = new StringBuilder("DELETE FROM books WHERE id IN (");
        for (int i = 0; i < ids.size(); i++) {
            sb.append(i == 0 ? "?" : ",?");
        }
        sb.append(")");
        try (PreparedStatement ps = conn().prepareStatement(sb.toString())) {
            for (int i = 0; i < ids.size(); i++)
                ps.setInt(i + 1, ids.get(i));
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[BookDAO] deleteAll failed: " + e.getMessage());
            return false;
        }
    }

    // ── SELECT ────────────────────────────────────────────────────────────────
    public List<Book> findAll() {
        return query("SELECT * FROM books ORDER BY date_added DESC");
    }

    public List<Book> findByStatus(Book.ReadingStatus status) {
        return query("SELECT * FROM books WHERE reading_status=? ORDER BY title",
                status.name());
    }

    public List<Book> findFavourites() {
        return query("SELECT * FROM books WHERE favourite=1 ORDER BY title");
    }

    public List<Book> findDownloads() {
        return query("SELECT * FROM books WHERE reading_status='DOWNLOADED' ORDER BY date_added DESC");
    }

    public List<Book> search(String keyword) {
        String like = "%" + keyword + "%";
        return query("SELECT * FROM books WHERE title LIKE ? OR author LIKE ? ORDER BY title",
                like, like);
    }

    public Book findById(int id) {
        List<Book> list = query("SELECT * FROM books WHERE id=?", String.valueOf(id));
        return list.isEmpty() ? null : list.get(0);
    }

    /** Check if a file path is already in the library. */
    public boolean existsByFilePath(String filePath) {
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT COUNT(*) FROM books WHERE file_path=?")) {
            ps.setString(1, filePath);
            ResultSet rs = ps.executeQuery();
            return rs.next() && rs.getInt(1) > 0;
        } catch (SQLException e) {
            return false;
        }
    }

    // ── Quick flag updates (avoid full object round-trip) ─────────────────────
    public void setFavourite(int id, boolean fav) {
        execUpdate("UPDATE books SET favourite=? WHERE id=?", fav ? 1 : 0, id);
    }

    public void setPinned(int id, boolean pinned) {
        execUpdate("UPDATE books SET pinned_to_start=? WHERE id=?", pinned ? 1 : 0, id);
    }

    public void updateReadingProgress(int id, double progress, Book.ReadingStatus status) {
        execUpdate(
                "UPDATE books SET reading_progress=?, reading_status=?, last_read=datetime('now') WHERE id=?",
                progress, status.name(), id);
    }

    // ── Private helpers ───────────────────────────────────────────────────────
    private List<Book> query(String sql, Object... params) {
        List<Book> list = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next())
                    list.add(map(rs));
            }
        } catch (SQLException e) {
            System.err.println("[BookDAO] query failed: " + e.getMessage());
        }
        return list;
    }

    private void execUpdate(String sql, Object... params) {
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++)
                ps.setObject(i + 1, params[i]);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[BookDAO] execUpdate failed: " + e.getMessage());
        }
    }

    private void bindInsert(PreparedStatement ps, Book b) throws SQLException {
        ps.setString(1, b.getTitle());
        ps.setString(2, b.getAuthor());
        ps.setString(3, b.getFilePath());
        ps.setString(4, b.getFileType());
        ps.setString(5, b.getPublisher());
        ps.setString(6, b.getLanguage());
        ps.setString(7, b.getGenre());
        ps.setString(8, b.getSeries());
        ps.setInt(9, b.getSeriesNumber());
        ps.setString(10, b.getDescription());
        ps.setInt(11, b.getWordCount());
        ps.setInt(12, b.getLineCount());
        ps.setDouble(13, b.getReadingProgress());
        ps.setString(14, b.getReadingStatus().name());
        ps.setInt(15, b.isFavourite() ? 1 : 0);
        ps.setInt(16, b.isPinnedToStart() ? 1 : 0);
        ps.setString(17, b.getDateAdded() != null
                ? b.getDateAdded().format(FMT)
                : LocalDateTime.now().format(FMT));
        ps.setString(18, b.getLastRead() != null ? b.getLastRead().format(FMT) : null);
        ps.setBytes(19, b.getCoverImageData());
    }

    private Book map(ResultSet rs) throws SQLException {
        Book b = new Book();
        b.setId(rs.getInt("id"));
        b.setTitle(rs.getString("title"));
        b.setAuthor(rs.getString("author"));
        b.setFilePath(rs.getString("file_path"));
        b.setFileType(rs.getString("file_type"));
        b.setPublisher(rs.getString("publisher"));
        b.setLanguage(rs.getString("language"));
        b.setGenre(rs.getString("genre"));
        b.setSeries(rs.getString("series"));
        b.setSeriesNumber(rs.getInt("series_number"));
        b.setDescription(rs.getString("description"));
        b.setWordCount(rs.getInt("word_count"));
        b.setLineCount(rs.getInt("line_count"));
        b.setReadingProgress(rs.getDouble("reading_progress"));
        b.setFavourite(rs.getInt("favourite") == 1);
        b.setPinnedToStart(rs.getInt("pinned_to_start") == 1);
        b.setCoverImageData(rs.getBytes("cover_image"));

        String statusStr = rs.getString("reading_status");
        try {
            b.setReadingStatus(Book.ReadingStatus.valueOf(statusStr));
        } catch (Exception ignored) {
            b.setReadingStatus(Book.ReadingStatus.NOT_STARTED);
        }

        String added = rs.getString("date_added");
        if (added != null) {
            try {
                b.setDateAdded(LocalDateTime.parse(added, FMT));
            } catch (Exception ignored) {
            }
        }
        String lastRead = rs.getString("last_read");
        if (lastRead != null) {
            try {
                b.setLastRead(LocalDateTime.parse(lastRead, FMT));
            } catch (Exception ignored) {
            }
        }
        return b;
    }
}