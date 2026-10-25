package com.scpl.service;

/** Audit action names. Constants, so reports and tests never depend on a typo. */
public final class AuditActions {

    public static final String SOCIETY_CREATED = "SOCIETY_CREATED";
    public static final String SOCIETY_UPDATED = "SOCIETY_UPDATED";
    public static final String SOCIETY_ACTIVATED = "SOCIETY_ACTIVATED";
    public static final String SOCIETY_SUSPENDED = "SOCIETY_SUSPENDED";
    public static final String ADMIN_ADDED = "ADMIN_ADDED";
    public static final String ADMIN_REMOVED = "ADMIN_REMOVED";

    public static final String APPROVAL_RAISED = "APPROVAL_RAISED";
    public static final String APPROVAL_VOTE_CAST = "APPROVAL_VOTE_CAST";
    public static final String APPROVAL_APPROVED = "APPROVAL_APPROVED";
    public static final String APPROVAL_REJECTED = "APPROVAL_REJECTED";
    public static final String APPROVAL_WITHDRAWN = "APPROVAL_WITHDRAWN";

    public static final String REGISTRATION_APPROVED = "REGISTRATION_APPROVED";
    public static final String REGISTRATION_REJECTED = "REGISTRATION_REJECTED";
    public static final String PLAYER_CREATED = "PLAYER_CREATED";
    public static final String PLAYER_UPDATED = "PLAYER_UPDATED";
    public static final String PLAYER_DROPPED = "PLAYER_DROPPED";

    public static final String SEASON_CREATED = "SEASON_CREATED";
    public static final String SEASON_UPDATED = "SEASON_UPDATED";
    public static final String SEASON_STATUS_CHANGED = "SEASON_STATUS_CHANGED";
    public static final String SEASON_DELETED = "SEASON_DELETED";
    public static final String TEAM_CREATED = "TEAM_CREATED";
    public static final String TEAM_UPDATED = "TEAM_UPDATED";
    public static final String TEAM_DELETED = "TEAM_DELETED";

    public static final String AUCTION_SOLD = "AUCTION_SOLD";
    public static final String AUCTION_UNSOLD = "AUCTION_UNSOLD";
    public static final String AUCTION_RESET = "AUCTION_RESET";
    public static final String AUCTION_BID_OVERRIDE = "AUCTION_BID_OVERRIDE";

    public static final String MATCH_CREATED = "MATCH_CREATED";
    public static final String MATCH_UPDATED = "MATCH_UPDATED";
    public static final String MATCH_DELETED = "MATCH_DELETED";

    // Used by the 4b services; declared here so the audit vocabulary lives in one file.
    public static final String IMPORT_APPROVED = "IMPORT_APPROVED";
    public static final String IMPORT_REJECTED = "IMPORT_REJECTED";
    public static final String BACKUP_GENERATED = "BACKUP_GENERATED";
    public static final String BACKUP_RESTORED = "BACKUP_RESTORED";
    public static final String PLAN_CHANGED = "PLAN_CHANGED";

    public static final String SUBSCRIPTION_STARTED = "SUBSCRIPTION_STARTED";
    public static final String SUBSCRIPTION_STATUS_SET = "SUBSCRIPTION_STATUS_SET";
    public static final String SUBSCRIPTION_PAST_DUE = "SUBSCRIPTION_PAST_DUE";
    public static final String SUBSCRIPTION_CANCELLED = "SUBSCRIPTION_CANCELLED";
    public static final String AUTO_RENEW_CHANGED = "AUTO_RENEW_CHANGED";
    public static final String PAYMENT_SUCCEEDED = "PAYMENT_SUCCEEDED";
    public static final String PAYMENT_FAILED = "PAYMENT_FAILED";

    public static final String VENDOR_CREATED = "VENDOR_CREATED";
    public static final String VENDOR_UPDATED = "VENDOR_UPDATED";
    public static final String VENDOR_DELETED = "VENDOR_DELETED";
    public static final String BILL_CREATED = "BILL_CREATED";
    public static final String BILL_UPDATED = "BILL_UPDATED";
    public static final String BILL_STATUS_CHANGED = "BILL_STATUS_CHANGED";
    public static final String BILL_DELETED = "BILL_DELETED";

    public static final String SHARE_CREATED = "SHARE_CREATED";

    public static final String TOURNAMENT_PROPOSED = "TOURNAMENT_PROPOSED";
    public static final String TOURNAMENT_JOINED = "TOURNAMENT_JOINED";
    public static final String TOURNAMENT_DECLINED = "TOURNAMENT_DECLINED";
    public static final String TOURNAMENT_STATUS_CHANGED = "TOURNAMENT_STATUS_CHANGED";
    // Sponsors, awards, gallery and events log as <ENTITY>_CREATED / _UPDATED / _DELETED,
    // composed in ContentService.

    public static final String IMPORT_UPLOADED = "IMPORT_UPLOADED";
    public static final String IMPORT_REVIEW_REPLACED = "IMPORT_REVIEW_REPLACED";
    public static final String IMPORT_FAILED = "IMPORT_FAILED";
    public static final String BACKUP_DOWNLOADED = "BACKUP_DOWNLOADED";

    public static final String PROFILE_UPDATED = "PROFILE_UPDATED";
    public static final String PASSWORD_CHANGED = "PASSWORD_CHANGED";

    public static final String CONTACT_ADDED = "CONTACT_ADDED";
    public static final String CONTACT_DELETED = "CONTACT_DELETED";

    public static final String FULL_DUMP_STAGED = "FULL_DUMP_STAGED";

    public static final String NIGHTLY_BACKUP = "NIGHTLY_BACKUP";

    private AuditActions() {
    }
}