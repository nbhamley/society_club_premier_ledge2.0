package com.scpl.util;

import com.scpl.dto.ImportKind;
import com.scpl.dto.ImportRows.ReviewRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewCsvTest {

    @TempDir
    Path dir;

    @Test
    void cellsThatLookLikeFormulasAreNeutralised() {
        assertThat(ReviewCsv.safe("=1+1")).isEqualTo("'=1+1");
        assertThat(ReviewCsv.safe("+44")).isEqualTo("'+44");
        assertThat(ReviewCsv.safe("-5")).isEqualTo("'-5");
        assertThat(ReviewCsv.safe("@SUM(A1)")).isEqualTo("'@SUM(A1)");
        assertThat(ReviewCsv.safe("plain")).isEqualTo("plain");
        assertThat(ReviewCsv.safe(null)).isEmpty();
        assertThat(ReviewCsv.safe("")).isEmpty();
    }

    @Test
    void aWrittenFileReadsBackIdentically_andTheFileOnDiskIsGuarded() throws IOException {
        Map<String, String> first = new LinkedHashMap<>();
        first.put("name", "=1+1");
        first.put("email", "a@b.co");
        Map<String, String> second = new LinkedHashMap<>();
        second.put("name", "Dan");
        second.put("email", "dup@b.co");
        List<ReviewRow> rows = List.of(
                new ReviewRow(ImportKind.PLAYER, "players.csv", 2, true, first, 90, null),
                new ReviewRow(ImportKind.PLAYER, "players.csv", 3, false, second, 70, "email_exists"));
        Path file = dir.resolve("review.csv");

        ReviewCsv.write(file, rows);

        String raw = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(raw).contains("'=1+1").doesNotContain(",=1+1");
        List<ReviewRow> back = ReviewCsv.read(file, 100);
        assertThat(back).hasSize(2);
        assertThat(back.get(0).kind()).isEqualTo(ImportKind.PLAYER);
        assertThat(back.get(0).include()).isTrue();
        assertThat(back.get(0).fields()).containsEntry("name", "=1+1").containsEntry("email", "a@b.co");
        assertThat(back.get(0).sourceRow()).isEqualTo(2);
        assertThat(back.get(0).confidence()).isEqualTo(90);
        assertThat(back.get(0).conflict()).isNull();
        assertThat(back.get(1).include()).isFalse();
        assertThat(back.get(1).conflict()).isEqualTo("email_exists");
        assertThat(back.get(1).confidence()).isEqualTo(70);
    }

    @Test
    void aBadKindIsAnErrorNotASilentlyDroppedRow() throws IOException {
        Path file = dir.resolve("bad.csv");
        Files.writeString(file, "Kind,SourceFile,SourceRow,Include,name\nSpaceship,x.csv,2,Y,Asha\n");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ReviewCsv.read(file, 100))
                .isInstanceOf(com.scpl.exception.BusinessRuleException.class).hasMessageContaining("Kind");
    }
}