package com.scpl.dto;

/** View models for the share flow (§13). */
public final class ShareViews {

    private ShareViews() {
    }

    /** Shown before the user confirms; nothing is stored yet. */
    public record Preview(String confirmation, String caption, String imageUrl) {
    }

    /**
     * Returned after the share row is stored. modal is true for Instagram and Facebook: neither
     * accepts prefilled text, so the UI offers copy-caption / download-image / open-app instead.
     * outboundUrl is the platform share endpoint (or the app's home page for modal platforms).
     */
    public record Result(String token, String shareUrl, String caption, String imageUrl, String platformLabel,
                         String outboundUrl, boolean modal) {
    }

    /** Public /share/{token} landing page. Holds no PII: the targets are stats-only public pages. */
    public record Landing(String title, String caption, String imageUrl, String shareUrl, String targetPath,
                          String societyName) {
    }
}