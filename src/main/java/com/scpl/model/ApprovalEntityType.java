package com.scpl.model;

import java.util.Set;

/**
 * Values for AdminApproval.entityType. Kept as String constants (not an enum) because the
 * spec models the column as a String; the V1 CHECK constraint allows exactly ALL.
 */
public final class ApprovalEntityType {

    public static final String REGISTRATION = "REGISTRATION";
    public static final String REGISTRATION_BATCH = "REGISTRATION_BATCH";
    public static final String PLAYER_DROP = "PLAYER_DROP";
    public static final String BULK_IMPORT = "BULK_IMPORT";
    public static final String SEASON_DELETE = "SEASON_DELETE";
    public static final String ADMIN_INVITE = "ADMIN_INVITE";
    public static final String ADMIN_REMOVE = "ADMIN_REMOVE";
    public static final String TOURNAMENT_JOIN = "TOURNAMENT_JOIN";

    public static final Set<String> ALL = Set.of(
            REGISTRATION, REGISTRATION_BATCH, PLAYER_DROP, BULK_IMPORT,
            SEASON_DELETE, ADMIN_INVITE, ADMIN_REMOVE, TOURNAMENT_JOIN);

    private ApprovalEntityType() {
    }
}