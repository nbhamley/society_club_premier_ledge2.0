package com.scpl.util;

import com.scpl.dto.ImportKind;
import com.scpl.model.AwardCategory;
import com.scpl.model.BattingStyle;
import com.scpl.model.BowlingStyle;
import com.scpl.model.MatchStage;
import com.scpl.model.PlayerRole;
import com.scpl.model.VendorCategory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Pure parsing and format checks for imported cells. Every parser throws only
 * IllegalArgumentException on bad input, so callers have one failure type to handle.
 * Day-first dates (dd/MM/yyyy) are supported because that is the local convention.
 */
public final class ImportValues {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final double MAX_AMOUNT = 1_000_000_000.0;

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("d-M-uuuu").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("d.M.uuuu").withResolverStyle(ResolverStyle.STRICT));

    private static final List<DateTimeFormatter> TIME_FORMATS = List.of(
            DateTimeFormatter.ofPattern("H:mm"),
            DateTimeFormatter.ofPattern("H:mm:ss"),
            DateTimeFormatter.ofPattern("H:mm:ss.SSS"),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("h:mm a")
                    .toFormatter(Locale.ENGLISH));

    private ImportValues() {
    }

    public static boolean blank(String v) {
        return v == null || v.isBlank();
    }

    /** Lower-case alphanumerics only, so "Date of Birth", "date_of_birth" and "DOB " compare alike. */
    public static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    public static boolean isEmail(String v) {
        return v != null && EMAIL.matcher(v.trim()).matches();
    }

    public static LocalDate parseDate(String raw) {
        String v = raw == null ? "" : raw.trim();
        int t = v.indexOf('T');
        if (t > 0) {
            v = v.substring(0, t);
        }
        for (DateTimeFormatter f : DATE_FORMATS) {
            try {
                return LocalDate.parse(v, f);
            } catch (DateTimeParseException ignored) {
                // try the next format
            }
        }
        throw new IllegalArgumentException("bad date");
    }

    /** A date, optionally followed by a time. A bare date means midnight. */
    public static LocalDateTime parseDateTime(String raw) {
        String v = raw == null ? "" : raw.trim().replace('T', ' ');
        String[] parts = v.split("\\s+", 2);
        LocalDate date = parseDate(parts[0]);
        if (parts.length == 1) {
            return date.atStartOfDay();
        }
        for (DateTimeFormatter f : TIME_FORMATS) {
            try {
                return date.atTime(LocalTime.parse(parts[1].trim(), f));
            } catch (DateTimeParseException ignored) {
                // try the next format
            }
        }
        throw new IllegalArgumentException("bad time");
    }

    public static int parseInt(String raw) {
        try {
            // BigDecimal tolerates "7.0", which spreadsheets sometimes produce for whole numbers.
            return new BigDecimal(raw.trim()).intValueExact();
        } catch (ArithmeticException | NullPointerException e) {
            throw new IllegalArgumentException("bad integer");
        }
    }

    /** Accepts "5,000", "Rs. 5000", "₹5,00,000.50". Rejects anything with no digits. */
    public static double parseMoney(String raw) {
        String v = raw == null ? "" : raw.replaceAll("(?i)rs\\.?|inr|[₹$,\\s]", "").replaceAll("[^0-9.\\-]", "");
        if (v.isEmpty()) {
            throw new IllegalArgumentException("bad number");
        }
        double d = Double.parseDouble(v);
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            throw new IllegalArgumentException("bad number");
        }
        return d;
    }

    /** Matches an enum by constant name or display label, ignoring case and punctuation. */
    public static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, Function<E, String> label) {
        String n = normalize(raw);
        for (E e : type.getEnumConstants()) {
            if (n.equals(normalize(e.name())) || n.equals(normalize(label.apply(e)))) {
                return e;
            }
        }
        throw new IllegalArgumentException("bad value");
    }

    private static final class Bad extends RuntimeException {
        final String field;

        Bad(String field) {
            super(field, null, false, false);
            this.field = field;
        }
    }

    private static void check(Map<String, String> f, String key, Consumer<String> parser) {
        String v = f.get(key);
        if (blank(v)) {
            return;
        }
        try {
            parser.accept(v);
        } catch (RuntimeException e) {
            throw new Bad(key);
        }
    }

    /**
     * Pure (database-free) validation of one row. Returns null when the row is well formed, or
     * "missing_required:field" / "invalid_value:field". Provider confidence and ImportRowHandler
     * both use it, so they cannot disagree.
     */
    public static String formatProblem(ImportKind kind, Map<String, String> f) {
        for (String req : ImportSchema.of(kind).required()) {
            if (blank(f.get(req))) {
                return "missing_required:" + req;
            }
        }
        try {
            switch (kind) {
                case PLAYER -> {
                    check(f, "email", v -> {
                        if (!isEmail(v)) {
                            throw new IllegalArgumentException();
                        }
                    });
                    check(f, "dateOfBirth", v -> {
                        LocalDate d = parseDate(v);
                        if (d.isAfter(LocalDate.now()) || d.getYear() < 1900) {
                            throw new IllegalArgumentException();
                        }
                    });
                    check(f, "jerseyNumber", v -> {
                        int n = parseInt(v);
                        if (n < 0 || n > 999) {
                            throw new IllegalArgumentException();
                        }
                    });
                    check(f, "basePrice", v -> {
                        double d = parseMoney(v);
                        if (d < 0 || d > MAX_AMOUNT) {
                            throw new IllegalArgumentException();
                        }
                    });
                    check(f, "role", v -> parseEnum(PlayerRole.class, v, PlayerRole::getLabel));
                    check(f, "battingStyle", v -> parseEnum(BattingStyle.class, v, BattingStyle::getLabel));
                    check(f, "bowlingStyle", v -> parseEnum(BowlingStyle.class, v, BowlingStyle::getLabel));
                }
                case TEAM -> check(f, "color", v -> {
                    if (!COLOR.matcher(v.trim()).matches()) {
                        throw new IllegalArgumentException();
                    }
                });
                case VENDOR -> {
                    check(f, "category", v -> parseEnum(VendorCategory.class, v, VendorCategory::getLabel));
                    check(f, "rating", v -> {
                        int n = parseInt(v);
                        if (n < 1 || n > 5) {
                            throw new IllegalArgumentException();
                        }
                    });
                    check(f, "email", v -> {
                        if (!isEmail(v)) {
                            throw new IllegalArgumentException();
                        }
                    });
                    check(f, "website", v -> Urls.requireWebUrl(v, "Website"));
                }
                case MATCH -> {
                    check(f, "date", ImportValues::parseDateTime);
                    check(f, "stage", v -> parseEnum(MatchStage.class, v, MatchStage::getLabel));
                }
                case AWARD -> check(f, "category", v -> parseEnum(AwardCategory.class, v, AwardCategory::getLabel));
                case BILL -> {
                    check(f, "amount", v -> {
                        double d = parseMoney(v);
                        if (d <= 0 || d > MAX_AMOUNT) {
                            throw new IllegalArgumentException();
                        }
                    });
                    check(f, "billDate", ImportValues::parseDate);
                }
            }
        } catch (Bad b) {
            return "invalid_value:" + b.field;
        }
        if (kind == ImportKind.AWARD && blank(f.get("player")) && blank(f.get("team"))) {
            return "missing_required:player_or_team";
        }
        return null;
    }
}