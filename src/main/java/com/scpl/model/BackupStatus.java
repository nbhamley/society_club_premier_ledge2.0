package com.scpl.model;

public enum BackupStatus {
    GENERATING("Generating"),
    READY("Ready"),
    DOWNLOADED("Downloaded"),
    FAILED("Failed");

    private final String label;

    BackupStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}