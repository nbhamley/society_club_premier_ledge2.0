package com.scpl.dto;

import java.util.List;

public final class ContactViews {

    private ContactViews() {
    }

    public record ContactView(Long id, String name, String roleTitle, String phone, String whatsappNumber,
                              String email, boolean hasLogin, String whatsappLink, String callLink, String mailLink) {
    }

    /** An admin login. canRemove is false for yourself: someone else must raise your removal. */
    public record AdminMemberView(Long userId, String username, String fullName, String email, String phone,
                                  boolean alsoPlayer, boolean self, boolean canRemove) {
    }

    /** vendors is empty and vendorsAvailable false when the plan has no vendor module. */
    public record ContactsPageView(List<AdminMemberView> admins, List<ContactView> contacts,
                                   List<VendorView> vendors, boolean vendorsAvailable, int adminCount) {
    }
}