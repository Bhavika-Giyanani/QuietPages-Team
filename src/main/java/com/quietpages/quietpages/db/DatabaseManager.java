package com.quietpages.quietpages.db;

import java.io.File;
import java.sql.*;

/**
 * Manages the single SQLite connection for QuietPages.
 * One connection, opened once, kept open for the app lifetime.
 *
 * Database file:
 *   Windows : %APPDATA%\QuietPages\quietpages.db
 *   macOS   : ~/Library/Application Support/QuietPages/quietpages.db
 *   Linux   : ~/.config/QuietPages/quietpages.db
 */
public class DatabaseManager {

    private static final String DB_FILE_NAME = "quietpages.db";
    private static DatabaseManager instance;

    private Connection connection;

    // ── Singleton ─────────────────────────────────────────────────────────────
    private DatabaseManager() {
        openConnection();
        createTables();
        // Seed is done AFTER constructor completes — avoids StackOverflow
        // because seedDefaultSites() creates an OnlineSiteDAO which calls
        // getInstance(), which would re-enter this constructor.
    }

    public static synchronized DatabaseManager getInstance() {
        if (instance == null) {
            instance = new DatabaseManager();
            // Seed only after instance is fully assigned —
            // so re-entrant getInstance() calls return the same instance.
            instance.seedDefaultSites();
        }
        return instance;
    }

    // ── Connection ────────────────────────────────────────────────────────────
    private void openConnection() {
        try {
            String dbPath = resolveDbPath();
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL;");
                st.execute("PRAGMA foreign_keys=ON;");
            }
            System.out.println("[DB] Connected → " + dbPath);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to connect to SQLite database", e);
        }
    }

    /** Returns the single shared connection. Never null after construction. */
    public Connection getConnection() {
        return connection;
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // ── Schema ────────────────────────────────────────────────────────────────
    private void createTables() {
        String createBooks = """
            CREATE TABLE IF NOT EXISTS books (
                id               INTEGER PRIMARY KEY AUTOINCREMENT,
                title            TEXT    NOT NULL DEFAULT 'Unknown Title',
                author           TEXT    NOT NULL DEFAULT 'Unknown Author',
                file_path        TEXT    NOT NULL UNIQUE,
                file_type        TEXT    NOT NULL DEFAULT 'epub',
                publisher        TEXT    DEFAULT '',
                language         TEXT    DEFAULT 'en',
                genre            TEXT    DEFAULT '',
                series           TEXT    DEFAULT '',
                series_number    INTEGER DEFAULT 0,
                description      TEXT    DEFAULT '',
                word_count       INTEGER DEFAULT 0,
                line_count       INTEGER DEFAULT 0,
                reading_progress REAL    DEFAULT 0.0,
                reading_status   TEXT    DEFAULT 'NOT_STARTED',
                favourite        INTEGER DEFAULT 0,
                pinned_to_start  INTEGER DEFAULT 0,
                date_added       TEXT    NOT NULL DEFAULT (datetime('now')),
                last_read        TEXT,
                cover_image      BLOB
            );""";

        String createSessions = """
            CREATE TABLE IF NOT EXISTS reading_sessions (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                book_id     INTEGER NOT NULL REFERENCES books(id) ON DELETE CASCADE,
                started_at  TEXT NOT NULL,
                ended_at    TEXT,
                pages_read  INTEGER DEFAULT 0
            );""";

        String createAnnotations = """
            CREATE TABLE IF NOT EXISTS annotations (
                id           INTEGER PRIMARY KEY AUTOINCREMENT,
                book_id      INTEGER NOT NULL REFERENCES books(id) ON DELETE CASCADE,
                type         TEXT    NOT NULL DEFAULT 'HIGHLIGHT',
                content      TEXT    NOT NULL DEFAULT '',
                note         TEXT    DEFAULT '',
                cfi_location TEXT,
                chapter      TEXT    DEFAULT '',
                created_at   TEXT    NOT NULL DEFAULT (datetime('now')),
                color        TEXT    DEFAULT '#FFD700'
            );""";

        String createBookmarks = """
            CREATE TABLE IF NOT EXISTS bookmarks (
                id           INTEGER PRIMARY KEY AUTOINCREMENT,
                book_id      INTEGER NOT NULL REFERENCES books(id) ON DELETE CASCADE,
                label        TEXT    DEFAULT '',
                cfi_location TEXT,
                chapter      TEXT    DEFAULT '',
                created_at   TEXT    NOT NULL DEFAULT (datetime('now'))
            );""";

        String createSettings = """
            CREATE TABLE IF NOT EXISTS app_settings (
                key   TEXT PRIMARY KEY,
                value TEXT NOT NULL DEFAULT ''
            );""";

        String createOnlineSites = """
            CREATE TABLE IF NOT EXISTS online_sites (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                title      TEXT    NOT NULL,
                url        TEXT    NOT NULL,
                is_default INTEGER NOT NULL DEFAULT 0,
                icon_data  BLOB
            );""";

        try (Statement st = connection.createStatement()) {
            st.execute(createBooks);
            st.execute(createSessions);
            st.execute(createAnnotations);
            st.execute(createBookmarks);
            st.execute(createSettings);
            st.execute(createOnlineSites);
            System.out.println("[DB] Schema ready.");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create database schema", e);
        }
    }

    // ── Seed default online sites ─────────────────────────────────────────────
    private void seedDefaultSites() {
        // Check directly via SQL — do NOT create OnlineSiteDAO here (avoids
        // re-entrant getInstance() calls during construction).
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM online_sites WHERE is_default=1")) {
            if (rs.next() && rs.getInt(1) > 0) return; // already seeded
        } catch (SQLException e) {
            System.err.println("[DB] Seed check failed: " + e.getMessage());
            return;
        }

        String sql = "INSERT INTO online_sites (title, url, is_default) VALUES (?,?,1)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, "Gutenberg");
            ps.setString(2, "https://www.gutenberg.org/");
            ps.executeUpdate();

            ps.setString(1, "Standard Ebooks");
            ps.setString(2, "https://standardebooks.org/");
            ps.executeUpdate();

            System.out.println("[DB] Default online sites seeded.");
        } catch (SQLException e) {
            System.err.println("[DB] Seed insert failed: " + e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private static String resolveDbPath() {
        String os = System.getProperty("os.name").toLowerCase();
        String base;
        if (os.contains("win")) {
            base = System.getenv("APPDATA");
        } else if (os.contains("mac")) {
            base = System.getProperty("user.home") + "/Library/Application Support";
        } else {
            base = System.getProperty("user.home") + "/.config";
        }
        File dir = new File(base, "QuietPages");
        dir.mkdirs();
        return new File(dir, DB_FILE_NAME).getAbsolutePath();
    }
}