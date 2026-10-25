package com.scpl.util;

import com.scpl.exception.BusinessRuleException;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;

/**
 * URL handling for values that end up inside href/src attributes. Thymeleaf escapes attribute
 * text, but it does not stop a "javascript:" scheme, so stored URLs are validated on the way in.
 */
public final class Urls {

    private static final int MAX_LENGTH = 500;

    private Urls() {
    }

    /** Returns a cleaned http(s) URL or site-relative path, null for blank, or throws. */
    public static String requireWebUrl(String raw, String fieldLabel) {
        String v = Text.clean(raw);
        if (v == null) {
            return null;
        }
        if (v.length() > MAX_LENGTH) {
            throw new BusinessRuleException(fieldLabel + " is too long");
        }
        boolean hasControl = v.chars().anyMatch(Character::isISOControl);
        // "//host" is protocol-relative and would escape the site, so only a single leading slash is allowed.
        if (!hasControl && v.startsWith("/") && !v.startsWith("//") && v.indexOf('\\') < 0) {
            return v;
        }
        try {
            URI uri = new URI(v);
            String scheme = uri.getScheme();
            if (("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) && uri.getHost() != null) {
                return v;
            }
        } catch (URISyntaxException ignored) {
            // falls through to the exception below
        }
        throw new BusinessRuleException(fieldLabel + " must be a web address starting with http:// or https://");
    }

    /** Percent-encoding with %20 for spaces: wa.me and the X intent URL both read it correctly. */
    public static String encode(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * wa.me link. The service needs digits only with the country code, so a bare 10-digit
     * number gets the configured default prefix. Returns null when the number is unusable.
     */
    public static String whatsapp(String number, String defaultCountryCode, String message) {
        if (number == null) {
            return null;
        }
        String digits = number.replaceAll("\\D", "");
        if (digits.length() == 10) {
            digits = defaultCountryCode + digits;
        } else if (digits.length() == 11 && digits.startsWith("0")) {
            digits = defaultCountryCode + digits.substring(1);
        }
        if (digits.length() < 8 || digits.length() > 15) {
            return null;
        }
        return "https://wa.me/" + digits + "?text=" + encode(message);
    }

    public static String tel(String number) {
        if (number == null) {
            return null;
        }
        String cleaned = number.replaceAll("[^+\\d]", "");
        return cleaned.isEmpty() ? null : "tel:" + cleaned;
    }
}