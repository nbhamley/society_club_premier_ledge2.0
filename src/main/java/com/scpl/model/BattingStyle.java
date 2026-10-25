package com.scpl.model;

public enum BattingStyle {
    RIGHT_HAND("Right-hand bat"),
    LEFT_HAND("Left-hand bat");

    private final String label;

    BattingStyle(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}