package com.scpl.util;

import java.text.Normalizer;
import java.util.Locale;

public final class Text {

    private Text() {
    }

    /** Trims, and turns blank into null so empty form fields never reach the database as "". */
    public static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public static String slugify(String source) {
        String base = Normalizer.normalize(source == null ? "" : source, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        return base.isEmpty() ? "society" : base;
    }
}