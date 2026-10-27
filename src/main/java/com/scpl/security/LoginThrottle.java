package com.scpl.security;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Brute-force brake on the login form: after MAX_FAILURES wrong passwords, that IP and username pair
 * is refused for LOCK_WINDOW. Keying on the pair (not the username alone) stops a stranger from locking
 * a real admin out. State is in memory, so this protects a single node; behind several nodes use a proxy
 * rate limit or a shared store.
 */
@Component
public class LoginThrottle {

    static final int MAX_FAILURES = 5;
    static final Duration LOCK_WINDOW = Duration.ofMinutes(15);
    private static final int CLEANUP_THRESHOLD = 10_000;

    private record Entry(int failures, Instant first) {
        boolean expired(Instant now) {
            return now.isAfter(first.plus(LOCK_WINDOW));
        }
    }

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    public static String key(String ip, String username) {
        return (ip == null ? "?" : ip) + "|" + (username == null ? "" : username.trim().toLowerCase(Locale.ROOT));
    }

    public boolean isBlocked(String key) {
        Entry e = entries.get(key);
        return e != null && !e.expired(Instant.now()) && e.failures() >= MAX_FAILURES;
    }

    public void failure(String key) {
        Instant now = Instant.now();
        entries.merge(key, new Entry(1, now),
                (old, ignored) -> old.expired(now) ? new Entry(1, now) : new Entry(old.failures() + 1, old.first()));
        if (entries.size() > CLEANUP_THRESHOLD) {
            entries.entrySet().removeIf(en -> en.getValue().expired(now));
        }
    }

    public void success(String key) {
        entries.remove(key);
    }
}