package com.quietpages.quietpages.service;

import com.quietpages.quietpages.db.OnlineSiteDAO;
import com.quietpages.quietpages.model.OnlineSite;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/**
 * Service layer for the Online Books tab.
 * Manages the list of bookstore sites shown in the left sidebar.
 */
public class OnlineBooksService {

    private static OnlineBooksService instance;
    private final OnlineSiteDAO dao = new OnlineSiteDAO();

    private OnlineBooksService() {
    }

    public static synchronized OnlineBooksService getInstance() {
        if (instance == null)
            instance = new OnlineBooksService();
        return instance;
    }

    // ── Fetch ─────────────────────────────────────────────────────────────────

    public ObservableList<OnlineSite> getAllSites() {
        return FXCollections.observableArrayList(dao.findAll());
    }

    // ── Mutations ─────────────────────────────────────────────────────────────

    public boolean addSite(OnlineSite site) {
        return dao.insert(site);
    }

    public boolean updateSite(OnlineSite site) {
        return dao.update(site);
    }

    public boolean removeSite(int id) {
        return dao.delete(id);
    }
}