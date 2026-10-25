package com.scpl.dto;

import com.scpl.model.TournamentStatus;

import java.time.LocalDate;
import java.util.List;

public final class TournamentViews {

    private TournamentViews() {
    }

    public record ParticipantView(Long societyId, String name, boolean host, boolean accepted) {
    }

    public record TournamentView(Long id, String name, Long hostSocietyId, String hostSocietyName,
                                 LocalDate startDate, LocalDate endDate, TournamentStatus status,
                                 String statusLabel, String description, List<ParticipantView> participants,
                                 boolean viewerIsHost, boolean viewerCanJoin, boolean viewerAccepted) {
    }

    /** Aggregate roster line: name, role and stats only. No PII field exists on this type (§6, §14). */
    public record RosterEntry(String societyName, String playerName, String roleLabel, int matchesPlayed,
                              int runs, int wickets) {
    }
}