package com.scpl.model;

public enum VendorCategory {
    VENUE("Venue"),
    TSHIRT_PRINTING("T-Shirt Printing"),
    LIGHTING("Lighting"),
    DECORATION("Decoration"),
    EVENT_ORGANISER("Event Organiser"),
    FOOD_BEVERAGE("Food & Beverage"),
    ELECTRICIAN("Electrician"),
    MEDICAL_STAFF("Medical Staff"),
    UMPIRE("Umpire"),
    TROPHIES("Trophies"),
    GIFTS("Gifts"),
    CAMERAMAN("Cameraman"),
    OTHER("Other");

    private final String label;

    VendorCategory(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}