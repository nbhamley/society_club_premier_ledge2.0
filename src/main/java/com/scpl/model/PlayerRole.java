package com.scpl.model;

public enum PlayerRole {
    BATSMAN("Batsman"),
    BOWLER("Bowler"),
    ALL_ROUNDER("All-Rounder"),
    WICKET_KEEPER("Wicket-Keeper");

    private final String label;

    PlayerRole(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}