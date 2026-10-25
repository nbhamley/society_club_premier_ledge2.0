package com.scpl.web.form;

import com.scpl.dto.NewAdminRequest;

import java.util.List;

/** Shared conversion for every form that collects founding admins (subscribe, platform create, restore). */
public final class AdminForms {

    private AdminForms() {
    }

    public static List<NewAdminRequest> toRequests(List<SubscribeForm.AdminForm> admins) {
        return admins.stream().map(a -> new NewAdminRequest(a.getUsername().trim(), a.getEmail().trim(),
                a.getFullName(), a.getPhone(), a.getPassword())).toList();
    }
}