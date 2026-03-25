package com.quietpages.quietpages.model;

import javafx.beans.property.*;

/**
 * Represents one download attempt shown in the Downloads panel
 * of the Online Books tab.
 *
 * Status transitions: DOWNLOADING → COMPLETED or FAILED
 */
public class DownloadEntry {

    public enum Status {
        DOWNLOADING, COMPLETED, FAILED
    }

    private final StringProperty fileName = new SimpleStringProperty("");
    private final ObjectProperty<Status> status = new SimpleObjectProperty<>(Status.DOWNLOADING);
    private final StringProperty message = new SimpleStringProperty("");

    public DownloadEntry() {
    }

    public DownloadEntry(String fileName) {
        this.fileName.set(fileName);
    }

    // ── Properties ────────────────────────────────────────────────────────────
    public StringProperty fileNameProperty() {
        return fileName;
    }

    public ObjectProperty<Status> statusProperty() {
        return status;
    }

    public StringProperty messageProperty() {
        return message;
    }

    // ── Getters / Setters ─────────────────────────────────────────────────────
    public String getFileName() {
        return fileName.get();
    }

    public void setFileName(String v) {
        fileName.set(v);
    }

    public Status getStatus() {
        return status.get();
    }

    public void setStatus(Status v) {
        status.set(v);
    }

    public String getMessage() {
        return message.get();
    }

    public void setMessage(String v) {
        message.set(v);
    }

    /** Human-readable status label shown in the Downloads panel */
    public String getStatusLabel() {
        return switch (status.get()) {
            case DOWNLOADING -> "Downloading...";
            case COMPLETED -> "Completed | Added to library";
            case FAILED -> "Failed";
        };
    }
}