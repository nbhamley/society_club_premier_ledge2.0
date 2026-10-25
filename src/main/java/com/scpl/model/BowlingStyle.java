package com.scpl.model;

public enum BowlingStyle {
    NONE("Does not bowl"),
    RIGHT_ARM_FAST("Right-arm fast"),
    LEFT_ARM_FAST("Left-arm fast"),
    RIGHT_ARM_MEDIUM("Right-arm medium"),
    LEFT_ARM_MEDIUM("Left-arm medium"),
    RIGHT_ARM_SPIN("Right-arm spin"),
    LEFT_ARM_SPIN("Left-arm spin");

    private final String label;

    BowlingStyle(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}