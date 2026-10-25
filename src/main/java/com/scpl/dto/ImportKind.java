package com.scpl.dto;

import java.util.Locale;

/**
 * What a recognised row would become. Declaration order is the processing order: matches need
 * their teams, awards need players or teams, bills need their vendors, so dependencies come first.
 */
public enum ImportKind {
    PLAYER("Players"),
    TEAM("Teams"),
    VENDOR("Vendors"),
    MATCH("Matches"),
    AWARD("Awards"),
    BILL("Bills");

    private final String label;

    ImportKind(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** Key used in the local-AI JSON contract: "players", "teams", "matches", ... */
    public String jsonKey() {
        return label.toLowerCase(Locale.ROOT);
    }

    /** Tolerant parse for review files and AI output: "Player", "players" and "PLAYER" all work. */
    public static ImportKind parse(String raw) {
        if (raw == null) {
            return null;
        }
        String n = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        for (ImportKind k : values()) {
            if (n.equals(k.name().toLowerCase(Locale.ROOT)) || n.equals(k.jsonKey())) {
                return k;
            }
        }
        return null;
    }
}