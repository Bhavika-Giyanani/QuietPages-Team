package com.quietpages.quietpages;

import com.quietpages.quietpages.db.DatabaseManager;
import javafx.scene.Scene;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;

/**
 * ThemeManager — bridges the Settings tab theme buttons
 * with QuietPages' CSS variable system.
 *
 * Injects a small .root { } override that redefines CSS variables,
 * so library.css stays loaded and all layout rules still apply.
 * Theme choice is persisted to app_settings so it survives restarts.
 */
public class ThemeManager {

    private static Scene scene;
    private static String injectedStylesheet = null;
    private static String currentTheme = "dark"; // default

    public static void setScene(Scene sc) {
        scene = sc;
    }

    public static String getCurrentTheme() {
        return currentTheme;
    }

    /**
     * Apply a named theme. Accepted values:
     * light, dark, ocean, sunset, forest, lavender, rose, midnight
     *
     * Also accepts "dark.css", "light.css" etc. (teammate format).
     */
    public static void applyTheme(String themeName) {
        if (scene == null)
            return;
        String name = themeName.toLowerCase().replace(".css", "").trim();

        String css = buildThemeCss(name);
        if (css == null)
            return;

        currentTheme = name;

        // Remove previous override
        if (injectedStylesheet != null) {
            scene.getStylesheets().remove(injectedStylesheet);
            injectedStylesheet = null;
        }

        // Inject as data URI — no extra file needed
        String encoded = URLEncoder.encode(css, StandardCharsets.UTF_8)
                .replace("+", "%20");
        injectedStylesheet = "data:text/css," + encoded;
        scene.getStylesheets().add(injectedStylesheet);

        persistTheme(name);
    }

    /** Call after setScene() on app start to restore last saved theme. */
    public static void restoreTheme() {
        try (var stmt = DatabaseManager.getInstance().getConnection()
                .prepareStatement("SELECT value FROM app_settings WHERE key='theme'")) {
            var rs = stmt.executeQuery();
            if (rs.next()) {
                applyTheme(rs.getString("value"));
                return;
            }
        } catch (Exception ignored) {
        }
        applyTheme("dark"); // default
    }

    // ── Theme palette definitions ─────────────────────────────────────────────

    private static String buildThemeCss(String name) {
        return switch (name) {
            case "light" -> vars(
                    "#FFFFFF", "#F0F0F0", "#FAFAFA", "#F5F5F5",
                    "#1A1A1A", "#666666", "#444444",
                    "#CCCCCC", "#AAAAAA",
                    "#C0284A", "#D42F54");

            case "dark" -> vars(
                    "#2B2B2B", "#1F1F1F", "#2B2B2B", "#333333",
                    "#DDDDDD", "#999999", "#BBBBBB",
                    "#3A3A3A", "#555555",
                    "#C0284A", "#D42F54");

            case "ocean" -> vars(
                    "#0D1B2A", "#0A1520", "#0D1B2A", "#1A2E45",
                    "#C8E6FF", "#7EB8D4", "#A0CDE0",
                    "#1E3A5A", "#2A5070",
                    "#2196F3", "#42A5F5");

            case "sunset" -> vars(
                    "#1A0A00", "#2A1000", "#1A0A00", "#2E1A08",
                    "#FFD9B3", "#CC8855", "#E0AA77",
                    "#3A2010", "#553020",
                    "#FF6B35", "#FF8C55");

            case "forest" -> vars(
                    "#0A1A0A", "#0F2010", "#0A1A0A", "#162816",
                    "#C8E8C8", "#7AB87A", "#A0CCA0",
                    "#1A3A1A", "#2A5A2A",
                    "#4CAF50", "#66BB6A");

            case "lavender" -> vars(
                    "#1A1020", "#221530", "#1A1020", "#2A1E35",
                    "#E8D8FF", "#AA88CC", "#C8A8E8",
                    "#3A2855", "#553A77",
                    "#9C27B0", "#AB47BC");

            case "rose" -> vars(
                    "#1A0A10", "#2A1018", "#1A0A10", "#2E1520",
                    "#FFD8E4", "#CC7799", "#E8A8BE",
                    "#3A1525", "#551E35",
                    "#E91E63", "#F06292");

            case "midnight" -> vars(
                    "#050510", "#080818", "#050510", "#0D0D25",
                    "#C8D8FF", "#6688BB", "#9AAECC",
                    "#101030", "#181840",
                    "#3F51B5", "#5C6BC0");

            default -> null;
        };
    }

    private static String vars(
            String bgApp, String bgSidebar, String bgToolbar, String bgDialog,
            String textPri, String textSec, String textGroup,
            String border, String borderInput,
            String accent, String accentHover) {

        return String.format("""
                .root {
                    -qp-accent:            %s;
                    -qp-accent-hover:      %s;
                    -qp-accent-light:      rgba(128,0,64,0.15);
                    -qp-bg-app:            %s;
                    -qp-bg-sidebar:        %s;
                    -qp-bg-toolbar:        %s;
                    -qp-bg-dialog:         %s;
                    -qp-bg-input:          %s;
                    -qp-bg-menu:           %s;
                    -qp-bg-menu-hover:     %s;
                    -qp-bg-info-panel:     %s;
                    -qp-text-primary:      %s;
                    -qp-text-secondary:    %s;
                    -qp-text-group-header: %s;
                    -qp-border:            %s;
                    -qp-border-input:      %s;
                }
                """,
                accent, accentHover,
                bgApp, bgSidebar, bgToolbar, bgDialog,
                bgDialog, // bg-input
                bgDialog, // bg-menu
                bgSidebar, // bg-menu-hover
                bgApp, // bg-info-panel
                textPri, textSec, textGroup,
                border, borderInput);
    }

    private static void persistTheme(String name) {
        try (PreparedStatement ps = DatabaseManager.getInstance().getConnection()
                .prepareStatement(
                        "INSERT OR REPLACE INTO app_settings(key,value) VALUES('theme',?)")) {
            ps.setString(1, name);
            ps.executeUpdate();
        } catch (Exception ignored) {
        }
    }
}