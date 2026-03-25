package com.quietpages.quietpages.db;

import com.quietpages.quietpages.model.OnlineSite;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * DAO for the online_sites table.
 * Called only by OnlineBooksService.
 */
public class OnlineSiteDAO {

    private Connection conn() {
        return DatabaseManager.getInstance().getConnection();
    }

    // ── INSERT ────────────────────────────────────────────────────────────────
    public boolean insert(OnlineSite site) {
        String sql = "INSERT INTO online_sites (title, url, is_default, icon_data) VALUES (?,?,?,?)";
        try (PreparedStatement ps = conn().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, site.getTitle());
            ps.setString(2, site.getUrl());
            ps.setInt   (3, site.isDefault() ? 1 : 0);
            ps.setBytes (4, site.getIconData());
            int rows = ps.executeUpdate();
            if (rows > 0) {
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) site.setId(keys.getInt(1));
                }
                return true;
            }
        } catch (SQLException e) {
            System.err.println("[SiteDAO] insert failed: " + e.getMessage());
        }
        return false;
    }

    // ── UPDATE ────────────────────────────────────────────────────────────────
    public boolean update(OnlineSite site) {
        String sql = "UPDATE online_sites SET title=?, url=?, icon_data=? WHERE id=? AND is_default=0";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, site.getTitle());
            ps.setString(2, site.getUrl());
            ps.setBytes (3, site.getIconData());
            ps.setInt   (4, site.getId());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[SiteDAO] update failed: " + e.getMessage());
            return false;
        }
    }

    // ── DELETE ────────────────────────────────────────────────────────────────
    public boolean delete(int id) {
        // Guard: cannot delete default sites
        try (PreparedStatement ps = conn().prepareStatement(
                "DELETE FROM online_sites WHERE id=? AND is_default=0")) {
            ps.setInt(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[SiteDAO] delete failed: " + e.getMessage());
            return false;
        }
    }

    // ── SELECT ────────────────────────────────────────────────────────────────
    public List<OnlineSite> findAll() {
        List<OnlineSite> list = new ArrayList<>();
        String sql = "SELECT * FROM online_sites ORDER BY is_default DESC, id ASC";
        try (Statement st = conn().createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) list.add(map(rs));
        } catch (SQLException e) {
            System.err.println("[SiteDAO] findAll failed: " + e.getMessage());
        }
        return list;
    }

    public boolean hasDefaultSites() {
        try (Statement st = conn().createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM online_sites WHERE is_default=1")) {
            return rs.next() && rs.getInt(1) > 0;
        } catch (SQLException e) {
            return false;
        }
    }

    // ── Mapping ───────────────────────────────────────────────────────────────
    private OnlineSite map(ResultSet rs) throws SQLException {
        OnlineSite s = new OnlineSite();
        s.setId(rs.getInt("id"));
        s.setTitle(rs.getString("title"));
        s.setUrl(rs.getString("url"));
        s.setDefault(rs.getInt("is_default") == 1);
        s.setIconData(rs.getBytes("icon_data"));
        return s;
    }
}