package com.scpl.model;

public enum AuctionMode {
    ONLINE("Online bidding"),
    OFFLINE("Offline (hall) auction");

    private final String label;

    AuctionMode(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}