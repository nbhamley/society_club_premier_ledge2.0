package com.scpl.model;

public enum AwardCategory {
    CHAMPION_TEAM("Champion Team"),
    RUNNER_UP("Runner-Up"),
    MAN_OF_THE_SERIES("Player of the Series"),
    BEST_BATSMAN("Best Batsman"),
    BEST_BOWLER("Best Bowler"),
    BEST_FIELDER("Best Fielder"),
    EMERGING_PLAYER("Emerging Player"),
    FAIR_PLAY("Fair Play"),
    OTHER("Other");

    private final String label;

    AwardCategory(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}