package com.scpl.util;

import com.scpl.dto.ImportRows.Table;
import com.scpl.exception.BusinessRuleException;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TabularReaderTest {

    private static final TabularReader.Limits LIMITS = new TabularReader.Limits(5, 1_000_000, 3_000_000, 100);

    @TempDir
    Path dir;

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private Path zip(String name, Map<String, byte[]> entries) throws IOException {
        Path file = dir.resolve(name);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
            for (var e : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        }
        return file;
    }

    // ------------------------------------------------------------------ csv

    @Test
    void csvBasics() {
        Table t = TabularReader.parseCsv(utf8("Name,Email\nAsha,a@b.co\n,\nRavi,r@b.co\n"), "x.csv");
        assertThat(t.headers()).containsExactly("Name", "Email");
        assertThat(t.rows()).hasSize(2);                     // the blank row is skipped
        assertThat(t.rows().get(0)).containsEntry("Name", "Asha").containsEntry("Email", "a@b.co");
    }

    @Test
    void semicolonAndQuotedCommasAndDuplicateHeaders() {
        assertThat(TabularReader.parseCsv(utf8("Name;Email\nAsha;a@b.co"), "x.csv").headers()).containsExactly("Name", "Email");
        assertThat(TabularReader.parseCsv(utf8("Name,Notes\nAsha,\"likes, cricket\""), "x.csv").rows().get(0))
                .containsEntry("Notes", "likes, cricket");
        Table dup = TabularReader.parseCsv(utf8("a,a\n1,2"), "x.csv");
        assertThat(dup.headers()).containsExactly("a", "a_2");
        assertThat(dup.rows().get(0)).containsEntry("a", "1").containsEntry("a_2", "2");
    }

    @Test
    void encodingsAndBom() {
        byte[] latin1 = "Name\nCaf\u00e9".getBytes(StandardCharsets.ISO_8859_1);
        assertThat(TabularReader.parseCsv(latin1, "x.csv").rows().get(0)).containsEntry("Name", "Caf\u00e9");
        byte[] withBom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'N', 'a', 'm', 'e', '\n', 'A'};
        assertThat(TabularReader.parseCsv(withBom, "x.csv").headers()).containsExactly("Name");
    }

    @Test
    void theFormulaGuardApostropheIsRemoved_butOnlyBeforeFormulaCharacters() {
        assertThat(TabularReader.parseCsv(utf8("Name\n'=1+1"), "x.csv").rows().get(0)).containsEntry("Name", "=1+1");
        assertThat(TabularReader.parseCsv(utf8("Name\n'hello"), "x.csv").rows().get(0)).containsEntry("Name", "'hello");
        assertThat(TabularReader.unguard("'@x")).isEqualTo("@x");
    }

    // ------------------------------------------------------------------ files

    @Test
    void tooManyRowsAndEmptyFilesAreRefused() throws IOException {
        StringBuilder many = new StringBuilder("Name\n");
        for (int i = 0; i < 101; i++) {
            many.append("p").append(i).append('\n');
        }
        Path big = Files.write(dir.resolve("big.csv"), utf8(many.toString()));
        assertThatThrownBy(() -> TabularReader.read(big, "big.csv", LIMITS)).isInstanceOf(BusinessRuleException.class).hasMessageContaining("rows");

        Path headerOnly = Files.write(dir.resolve("empty.csv"), utf8("Name\n"));
        assertThatThrownBy(() -> TabularReader.read(headerOnly, "empty.csv", LIMITS)).isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("No data rows");
    }

    @Test
    void jsonArraysAndGroupedObjects() throws IOException {
        Path array = Files.write(dir.resolve("data.json"), utf8("[{\"name\":\"A\",\"runs\":5,\"nested\":{\"x\":1}},{\"name\":\"B\"}]"));
        List<Table> tables = TabularReader.read(array, "data.json", LIMITS);
        assertThat(tables).hasSize(1);
        assertThat(tables.get(0).rows()).hasSize(2);
        assertThat(tables.get(0).rows().get(0)).containsEntry("name", "A").containsEntry("runs", "5").doesNotContainKey("nested");

        Path grouped = Files.write(dir.resolve("g.json"), utf8("{\"players\":[{\"name\":\"A\"}],\"teams\":[{\"name\":\"T\"}]}"));
        assertThat(TabularReader.read(grouped, "g.json", LIMITS)).extracting(Table::source)
                .containsExactly("g.json / players", "g.json / teams");

        Path invalid = Files.write(dir.resolve("bad.json"), utf8("{nope"));
        assertThatThrownBy(() -> TabularReader.read(invalid, "bad.json", LIMITS)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void xlsxSheetsAreReadAndHiddenSheetsIgnored() throws IOException {
        Path file = dir.resolve("book.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(file)) {
            Sheet s = wb.createSheet("Players");
            Row h = s.createRow(0);
            h.createCell(0).setCellValue("Name");
            h.createCell(1).setCellValue("Runs");
            Row r = s.createRow(1);
            r.createCell(0).setCellValue("Asha");
            r.createCell(1).setCellValue(42);
            Sheet secret = wb.createSheet("Secret");
            secret.createRow(0).createCell(0).setCellValue("Name");
            secret.createRow(1).createCell(0).setCellValue("Hidden Person");
            wb.setSheetHidden(1, true);
            wb.write(out);
        }
        List<Table> tables = TabularReader.read(file, "book.xlsx", LIMITS);
        assertThat(tables).hasSize(1);
        assertThat(tables.get(0).source()).isEqualTo("book.xlsx / Players");
        assertThat(tables.get(0).rows().get(0)).containsEntry("Name", "Asha").containsEntry("Runs", "42");
    }

    // ------------------------------------------------------------------ zip safety

    @Test
    void zipsAreReadInMemory_unsupportedEntriesAndJunkIgnored_pathsNeverUsed() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("players.csv", utf8("Name\nAsha"));
        entries.put("notes.txt", utf8("ignore me"));
        entries.put("__MACOSX/._players.csv", utf8("junk"));
        entries.put("../../evil.csv", utf8("Name\nEve"));
        Path file = zip("ok.zip", entries);
        List<Table> tables = TabularReader.read(file, "ok.zip", LIMITS);
        assertThat(tables).extracting(Table::source).containsExactly("players.csv", "evil.csv");   // traversal path reduced to a bare name
    }

    @Test
    void zipBombsTooManyEntriesAndDamagedZipsAreRefused() throws IOException {
        Path bomb = zip("bomb.zip", Map.of("big.csv", new byte[3_000_000]));      // compresses to a few KB, inflates past the cap
        assertThatThrownBy(() -> TabularReader.read(bomb, "bomb.zip", LIMITS)).isInstanceOf(BusinessRuleException.class);

        Map<String, byte[]> many = new LinkedHashMap<>();
        for (int i = 0; i < 6; i++) {
            many.put("f" + i + ".csv", utf8("Name\nA"));
        }
        Path crowded = zip("many.zip", many);
        assertThatThrownBy(() -> TabularReader.read(crowded, "many.zip", LIMITS)).isInstanceOf(BusinessRuleException.class);

        Path damaged = Files.write(dir.resolve("damaged.zip"), new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        assertThatThrownBy(() -> TabularReader.read(damaged, "damaged.zip", LIMITS)).isInstanceOf(BusinessRuleException.class);
    }
}