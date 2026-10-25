package com.scpl.model;

public enum SharePlatform {
    INSTAGRAM("Instagram"),
    FACEBOOK("Facebook"),
    WHATSAPP("WhatsApp"),
    TWITTER_X("X"),
    LINKEDIN("LinkedIn"),
    COPY_LINK("Copy Link");

    private final String label;

    SharePlatform(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}