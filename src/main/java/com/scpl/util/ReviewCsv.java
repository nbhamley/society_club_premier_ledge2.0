package com.scpl.util;

import com.scpl.dto.ImportKind;
import com.scpl.dto.ImportRows.ReviewRow;
import com.scpl.dto.ImportRows.Table;
import com.scpl.exception.BusinessRuleException;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * review.csv and {batchId}.failed.csv share one layout, so a failed file can be fixed and re-uploaded
 * straight back in. Columns: Kind, SourceFile, SourceRow, Include (Y/N), the field columns, Confidence,
 * Conflict. Setting Include to N skips a row without deleting it.
 *
 * <p>CSV formula injection: a cell that starts with = + - @ (or a tab or CR) is written with a leading
 * apostrophe, which spreadsheet programs treat as "text, not formula". The reader strips it again.
 */
public final class ReviewCsv {

    private static final List<String> PREFIX = List.of("Kind", "SourceFile", "SourceRow", "Include");
    private static final List<String> SUFFIX = List.of("Confidence", "Conflict");
    private static final String FORMULA_LEADS = "=+-@\t\r";
    private static final Set<String> NO = Set.of("n", "no", "false", "0", "skip", "x");

    private ReviewCsv() {
    }

    public static String safe(String v) {
        if (v == null || v.isEmpty()) {
            return "";
        }
        return FORMULA_LEADS.indexOf(v.charAt(0)) >= 0 ? "'" + v : v;
    }

    /** Written via a temporary file and a move, so a crash never leaves a half-written review file. */
    public static void write(Path path, List<ReviewRow> rows) throws IOException {
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8);
             CSVPrinter printer = new CSVPrinter(w, CSVFormat.DEFAULT)) {
            w.write('\uFEFF'); // BOM so Excel opens non-ASCII text correctly
            List<String> header = new ArrayList<>(PREFIX);
            header.addAll(ImportSchema.ALL_FIELDS);
            header.addAll(SUFFIX);
            printer.printRecord(header);
            for (ReviewRow r : rows) {
                List<String> rec = new ArrayList<>();
                rec.add(r.kind().name());
                rec.add(safe(r.sourceFile()));
                rec.add(String.valueOf(r.sourceRow()));
                rec.add(r.include() ? "Y" : "N");
                for (String f : ImportSchema.ALL_FIELDS) {
                    rec.add(safe(r.fields().get(f)));
                }
                rec.add(String.valueOf(r.confidence()));
                rec.add(r.conflict() == null ? "" : r.conflict());
                printer.printRecord(rec);
            }
        }
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
    }

    public static List<ReviewRow> read(Path path, int maxRows) throws IOException {
        List<ReviewRow> rows = fromTable(TabularReader.parseCsv(Files.readAllBytes(path), path.getFileName().toString()));
        if (rows.size() > maxRows) {
            throw new BusinessRuleException("The review file has more than " + maxRows + " rows");
        }
        return rows;
    }

    /** Converts a parsed review/failed file. A bad Kind is an error, not a silently dropped row. */
    public static List<ReviewRow> fromTable(Table t) {
        Map<String, String> meta = new HashMap<>();
        Map<String, String> fieldCols = new LinkedHashMap<>();
        for (String h : t.headers()) {
            String n = ImportValues.normalize(h);
            switch (n) {
                case "kind", "sourcefile", "sourcerow", "include", "confidence", "conflict" -> meta.put(n, h);
                default -> {
                    String f = ImportSchema.fieldByColumnName(h);
                    if (f != null) {
                        fieldCols.put(h, f);
                    }
                }
            }
        }
        if (!meta.containsKey("kind")) {
            throw new BusinessRuleException("The review file needs a Kind column");
        }
        List<ReviewRow> out = new ArrayList<>();
        int line = 1;
        for (Map<String, String> row : t.rows()) {
            line++;
            ImportKind kind = ImportKind.parse(row.get(meta.get("kind")));
            if (kind == null) {
                throw new BusinessRuleException("Row " + line + ": Kind must be Player, Team, Vendor, Match, Award or Bill");
            }
            Map<String, String> fields = new LinkedHashMap<>();
            for (var e : fieldCols.entrySet()) {
                String v = row.get(e.getKey());
                if (v != null && !v.isBlank()) {
                    fields.put(e.getValue(), com.scpl.integration.ai.RuleBasedSegregationProvider
                            .cleanValue(e.getValue(), v));
                }
            }
            String include = cell(row, meta, "include");
            String conflict = cell(row, meta, "conflict");
            String source = cell(row, meta, "sourcefile");
            out.add(new ReviewRow(kind, source.isEmpty() ? t.source() : source,
                    intOr(cell(row, meta, "sourcerow"), line), !NO.contains(include.toLowerCase()), fields,
                    Math.max(0, Math.min(100, intOr(cell(row, meta, "confidence"), 100))),
                    conflict.isEmpty() ? null : conflict));
        }
        return out;
    }

    private static String cell(Map<String, String> row, Map<String, String> meta, String key) {
        String col = meta.get(key);
        String v = col == null ? null : row.get(col);
        return v == null ? "" : v.trim();
    }

    private static int intOr(String v, int fallback) {
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}