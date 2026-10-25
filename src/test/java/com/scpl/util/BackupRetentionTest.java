package com.scpl.util;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class BackupRetentionTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 25);

    private static List<LocalDate> nightly(int days) {
        return IntStream.range(0, days).mapToObj(TODAY::minusDays).toList();
    }

    @Test
    void aYearOfNightlyBackupsKeepsSevenDailyFourWeeklyAndThreeMonthly() {
        Set<LocalDate> keep = BackupRetention.keep(nightly(365), 7, 4, 3);
        assertThat(keep).hasSize(14);
        for (int i = 0; i < 7; i++) {
            assertThat(keep).contains(TODAY.minusDays(i));
        }
    }

    @Test
    void shortHistoriesAreKeptWhole() {
        assertThat(BackupRetention.keep(nightly(3), 7, 4, 3)).hasSize(3);
        assertThat(BackupRetention.keep(nightly(7), 7, 4, 3)).hasSize(7);
    }

    @Test
    void theNewestBackupIsAlwaysKept_evenWithZeroSettings() {
        assertThat(BackupRetention.keep(nightly(30), 0, 0, 0)).containsExactly(TODAY);
    }

    @Test
    void gapsDoNotShortenTheHistory() {
        // a server that was off for most of a month: one backup per month for five months
        List<LocalDate> sparse = List.of(TODAY, TODAY.minusMonths(1), TODAY.minusMonths(2), TODAY.minusMonths(3), TODAY.minusMonths(4));
        assertThat(BackupRetention.keep(sparse, 1, 0, 3)).containsExactlyInAnyOrder(
                TODAY, TODAY.minusMonths(1), TODAY.minusMonths(2), TODAY.minusMonths(3));
    }

    @Test
    void duplicatesAndEmptyInput() {
        assertThat(BackupRetention.keep(List.of(TODAY, TODAY, TODAY), 7, 4, 3)).containsExactly(TODAY);
        assertThat(BackupRetention.keep(List.of(), 7, 4, 3)).isEmpty();
    }

    @Test
    void weeklyPicksTheNewestBackupOfEachWeekOutsideTheDailyWindow() {
        // Daily window = the 2 newest. Of the older ones, one per ISO week survives.
        List<LocalDate> dates = List.of(LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 24),     // daily
                LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 13),                              // same ISO week
                LocalDate.of(2026, 10, 7));                                                          // an earlier week
        Set<LocalDate> keep = BackupRetention.keep(dates, 2, 2, 0);
        assertThat(keep).containsExactlyInAnyOrder(LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 24),
                LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 7));
    }
}