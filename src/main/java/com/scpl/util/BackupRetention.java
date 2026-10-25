package com.scpl.util;

import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Grandfather-father-son rotation for the nightly dump (§10): the newest {daily} backups, then the newest backup of each
 * of the {weekly} most recent ISO weeks and of the {monthly} most recent months among the ones that remain. Pure and
 * date-only, so it is unit-tested without touching a disk. Periods with no backup are skipped, so a gap in the nightly
 * job does not shorten the history. The newest date is always kept.
 */
public final class BackupRetention {

    private BackupRetention() {
    }

    public static Set<LocalDate> keep(Collection<LocalDate> dates, int daily, int weekly, int monthly) {
        List<LocalDate> sorted = dates.stream().distinct().sorted(Comparator.reverseOrder()).toList();
        Set<LocalDate> keep = new HashSet<>();
        List<LocalDate> rest = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            if (i < Math.max(1, daily)) {
                keep.add(sorted.get(i));
            } else {
                rest.add(sorted.get(i));
            }
        }
        rest = newestPerPeriod(rest, Math.max(0, weekly), BackupRetention::weekKey, keep);
        newestPerPeriod(rest, Math.max(0, monthly), d -> d.getYear() * 100 + d.getMonthValue(), keep);
        return keep;
    }

    /** Input is newest first, so the first date seen for a period is that period's newest. Returns the dates not kept. */
    private static List<LocalDate> newestPerPeriod(List<LocalDate> newestFirst, int periods,
                                                   Function<LocalDate, Integer> key, Set<LocalDate> keep) {
        Set<Integer> chosen = new LinkedHashSet<>();
        List<LocalDate> remaining = new ArrayList<>();
        for (LocalDate d : newestFirst) {
            int k = key.apply(d);
            if (!chosen.contains(k) && chosen.size() < periods) {
                chosen.add(k);
                keep.add(d);
            } else {
                remaining.add(d);
            }
        }
        return remaining;
    }

    private static int weekKey(LocalDate d) {
        return d.get(IsoFields.WEEK_BASED_YEAR) * 100 + d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
    }
}