package com.scpl.util;

import com.scpl.dto.ImportKind;
import com.scpl.model.PlayerRole;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImportValuesTest {

    @Test
    void datesAcceptIsoAndDayFirstFormats() {
        assertThat(ImportValues.parseDate("2026-10-25")).isEqualTo(LocalDate.of(2026, 10, 25));
        assertThat(ImportValues.parseDate("25/10/2026")).isEqualTo(LocalDate.of(2026, 10, 25));
        assertThat(ImportValues.parseDate("5/1/2026")).isEqualTo(LocalDate.of(2026, 1, 5));     // day first, not month first
        assertThat(ImportValues.parseDate("25-10-2026")).isEqualTo(LocalDate.of(2026, 10, 25));
        assertThat(ImportValues.parseDate("2026-10-25T10:00:00")).isEqualTo(LocalDate.of(2026, 10, 25));
        assertThatThrownBy(() -> ImportValues.parseDate("31/02/2026")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ImportValues.parseDate("tomorrow")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dateTimesDefaultToMidnight() {
        assertThat(ImportValues.parseDateTime("25/10/2026 18:30")).isEqualTo(LocalDateTime.of(2026, 10, 25, 18, 30));
        assertThat(ImportValues.parseDateTime("2026-10-25T18:30")).isEqualTo(LocalDateTime.of(2026, 10, 25, 18, 30));
        assertThat(ImportValues.parseDateTime("2026-10-25")).isEqualTo(LocalDateTime.of(2026, 10, 25, 0, 0));
        assertThatThrownBy(() -> ImportValues.parseDateTime("2026-10-25 99:99")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void numbers() {
        assertThat(ImportValues.parseInt("7")).isEqualTo(7);
        assertThat(ImportValues.parseInt("7.0")).isEqualTo(7);
        assertThatThrownBy(() -> ImportValues.parseInt("7.5")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ImportValues.parseInt("abc")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ImportValues.parseInt(null)).isInstanceOf(IllegalArgumentException.class);

        assertThat(ImportValues.parseMoney("5,000")).isEqualTo(5000.0);
        assertThat(ImportValues.parseMoney("Rs. 5000")).isEqualTo(5000.0);
        assertThat(ImportValues.parseMoney("INR 1,200")).isEqualTo(1200.0);
        assertThat(ImportValues.parseMoney("\u20B95,00,000.50")).isEqualTo(500000.5);
        assertThatThrownBy(() -> ImportValues.parseMoney("free")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void enumsMatchByNameOrLabelIgnoringCaseAndPunctuation() {
        assertThat(ImportValues.parseEnum(PlayerRole.class, "all rounder", PlayerRole::getLabel)).isEqualTo(PlayerRole.ALL_ROUNDER);
        assertThat(ImportValues.parseEnum(PlayerRole.class, "Wicket-Keeper", PlayerRole::getLabel)).isEqualTo(PlayerRole.WICKET_KEEPER);
        assertThat(ImportValues.parseEnum(PlayerRole.class, "BATSMAN", PlayerRole::getLabel)).isEqualTo(PlayerRole.BATSMAN);
        assertThatThrownBy(() -> ImportValues.parseEnum(PlayerRole.class, "goalie", PlayerRole::getLabel))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emailsAndBlanks() {
        assertThat(ImportValues.isEmail("a@b.co")).isTrue();
        assertThat(ImportValues.isEmail("a@b")).isFalse();
        assertThat(ImportValues.isEmail("a b@c.co")).isFalse();
        assertThat(ImportValues.isEmail(null)).isFalse();
        assertThat(ImportValues.blank("  ")).isTrue();
        assertThat(ImportValues.normalize("Mobile No.")).isEqualTo("mobileno");
    }

    @Test
    void playerRowsAreChecked() {
        assertThat(ImportValues.formatProblem(ImportKind.PLAYER, Map.of("name", "Asha"))).isNull();
        assertThat(ImportValues.formatProblem(ImportKind.PLAYER, Map.of())).isEqualTo("missing_required:name");
        assertThat(ImportValues.formatProblem(ImportKind.PLAYER, Map.of("name", "A", "email", "nope"))).isEqualTo("invalid_value:email");
        assertThat(ImportValues.formatProblem(ImportKind.PLAYER, Map.of("name", "A", "dateOfBirth", "2999-01-01")))
                .isEqualTo("invalid_value:dateOfBirth");
        assertThat(ImportValues.formatProblem(ImportKind.PLAYER, Map.of("name", "A", "jerseyNumber", "1000")))
                .isEqualTo("invalid_value:jerseyNumber");
        assertThat(ImportValues.formatProblem(ImportKind.PLAYER, Map.of("name", "A", "role", "goalie"))).isEqualTo("invalid_value:role");
    }

    @Test
    void otherKindsAreChecked() {
        assertThat(ImportValues.formatProblem(ImportKind.TEAM, Map.of("name", "X", "color", "red"))).isEqualTo("invalid_value:color");
        assertThat(ImportValues.formatProblem(ImportKind.TEAM, Map.of("name", "X", "color", "#12abEF"))).isNull();
        assertThat(ImportValues.formatProblem(ImportKind.VENDOR, Map.of("name", "V", "rating", "9"))).isEqualTo("invalid_value:rating");
        assertThat(ImportValues.formatProblem(ImportKind.VENDOR, Map.of("name", "V", "website", "javascript:x"))).isEqualTo("invalid_value:website");
        assertThat(ImportValues.formatProblem(ImportKind.MATCH, Map.of("teamA", "A", "teamB", "B", "date", "nonsense"))).isEqualTo("invalid_value:date");
        assertThat(ImportValues.formatProblem(ImportKind.BILL, Map.of("vendor", "V", "title", "T"))).isEqualTo("missing_required:amount");
        assertThat(ImportValues.formatProblem(ImportKind.BILL, Map.of("vendor", "V", "title", "T", "amount", "-5"))).isEqualTo("invalid_value:amount");
        assertThat(ImportValues.formatProblem(ImportKind.AWARD, Map.of("title", "T"))).isEqualTo("missing_required:player_or_team");
        assertThat(ImportValues.formatProblem(ImportKind.AWARD, Map.of("title", "T", "player", "Asha"))).isNull();
    }

    @Test
    void headersMapToCanonicalFields() {
        assertThat(ImportSchema.canonical(ImportKind.PLAYER, "Mobile No.")).isEqualTo("phone");
        assertThat(ImportSchema.canonical(ImportKind.PLAYER, "Full Name")).isEqualTo("name");
        assertThat(ImportSchema.canonical(ImportKind.PLAYER, "Date of Birth")).isEqualTo("dateOfBirth");
        assertThat(ImportSchema.canonical(ImportKind.MATCH, "Home")).isEqualTo("teamA");
        assertThat(ImportSchema.canonical(ImportKind.PLAYER, "Shoe size")).isNull();
        assertThat(ImportSchema.hint("vendor-list.csv")).isEqualTo(ImportKind.VENDOR);
        assertThat(ImportSchema.hint("players.csv")).isEqualTo(ImportKind.PLAYER);
        assertThat(ImportSchema.hint("random.csv")).isNull();
    }

    @Test
    void kindParsingIsTolerant() {
        assertThat(ImportKind.parse("Players")).isEqualTo(ImportKind.PLAYER);
        assertThat(ImportKind.parse("player")).isEqualTo(ImportKind.PLAYER);
        assertThat(ImportKind.parse("BILLS")).isEqualTo(ImportKind.BILL);
        assertThat(ImportKind.parse("xyz")).isNull();
        assertThat(ImportKind.parse(null)).isNull();
    }
}