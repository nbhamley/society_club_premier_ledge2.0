package com.scpl.model;

public enum MatchStage {
    LEAGUE("League"),
    QUALIFIER("Qualifier"),
    SEMI_FINAL("Semi-Final"),
    FINAL("Final");

    private final String label;

    MatchStage(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}