package com.quietpages.quietpages.model;

import javafx.beans.property.*;

/**
 * Represents one entry in the Online Books tab left sidebar.
 *
 * Default sites (Gutenberg, Standard Ebooks) have isDefault=true
 * and cannot be edited or removed.
 *
 * iconData holds the raw bytes of the favicon/custom icon (stored as BLOB in
 * DB).
 */
public class OnlineSite {

    private final IntegerProperty id = new SimpleIntegerProperty();
    private final StringProperty title = new SimpleStringProperty("");
    private final StringProperty url = new SimpleStringProperty("");
    private final BooleanProperty isDefault = new SimpleBooleanProperty(false);

    /** Raw bytes of the site icon image — null means use generated placeholder */
    private byte[] iconData;

    // ── Constructors ──────────────────────────────────────────────────────────
    public OnlineSite() {
    }

    public OnlineSite(int id, String title, String url, boolean isDefault) {
        this.id.set(id);
        this.title.set(title);
        this.url.set(url);
        this.isDefault.set(isDefault);
    }

    // ── Properties ────────────────────────────────────────────────────────────
    public IntegerProperty idProperty() {
        return id;
    }

    public StringProperty titleProperty() {
        return title;
    }

    public StringProperty urlProperty() {
        return url;
    }

    public BooleanProperty isDefaultProperty() {
        return isDefault;
    }

    // ── Getters / Setters ─────────────────────────────────────────────────────
    public int getId() {
        return id.get();
    }

    public void setId(int v) {
        id.set(v);
    }

    public String getTitle() {
        return title.get();
    }

    public void setTitle(String v) {
        title.set(v);
    }

    public String getUrl() {
        return url.get();
    }

    public void setUrl(String v) {
        url.set(v);
    }

    public boolean isDefault() {
        return isDefault.get();
    }

    public void setDefault(boolean v) {
        isDefault.set(v);
    }

    public byte[] getIconData() {
        return iconData;
    }

    public void setIconData(byte[] v) {
        iconData = v;
    }

    @Override
    public String toString() {
        return "OnlineSite{id=" + getId() + ", title='" + getTitle() + "'}";
    }
}