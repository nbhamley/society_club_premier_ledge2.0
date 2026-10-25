package com.scpl.dto;

/** One line of the points table. */
public record StandingRow(Long teamId, String teamName, String shortName, String color,
                          int played, int won, int lost, int tied, int points, double netRunRate) {
}