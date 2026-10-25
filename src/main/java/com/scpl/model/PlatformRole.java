package com.scpl.model;

public enum PlatformRole {
    PLATFORM_ADMIN("Platform Admin"),
    SOCIETY_ADMIN("Society Admin"),
    SOCIETY_PLAYER("Society Player");

    private final String label;

    PlatformRole(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}