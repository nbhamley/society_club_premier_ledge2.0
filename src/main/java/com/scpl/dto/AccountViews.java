package com.scpl.dto;

import java.time.LocalDateTime;

/** Models for the layout, the profile page and the notifications page. None carries a password hash or an entity. */
public final class AccountViews {

    private AccountViews() {
    }

    /** What the header needs. societyName and societySlug are null for platform admins. */
    public record NavView(String displayName, String roleLabel, boolean platformAdmin, boolean societyAdmin,
                          String societyName, String societySlug, String playingAs, boolean canRegisterAsPlayer,
                          long unreadNotifications, long pendingVotes) {
    }

    public record ProfileView(String username, String fullName, String email, String phone, String roleLabel,
                              String societyName, String societySlug, String playingAs,
                              boolean canRegisterAsPlayer) {
    }

    public record NotificationView(Long id, String title, String message, String link, boolean read,
                                   LocalDateTime createdAt) {
    }
}