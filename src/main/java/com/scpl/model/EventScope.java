package com.scpl.model;

public enum EventScope {
    PLATFORM("Platform"),
    SOCIETY("Society");

    private final String label;

    EventScope(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}