package com.scpl.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.scpl.dto.ImportRows.Table;
import com.scpl.exception.BusinessRuleException;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.io.FilenameUtils;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/**
 * Reads CSV, JSON, XLSX and ZIPs of those into plain tables. ZIP entries are only ever read in memory
 * (their paths are never used on disk, so path traversal is impossible) and unpacking is capped on
 * entry count, per-entry size and total size against zip bombs. XLSX formulas are never evaluated:
 * the cached value is used, so an uploaded workbook cannot trigger external lookups.
 */
public final class TabularReader {

    public record Limits(int maxEntries, long maxEntryBytes, long maxTotalBytes, int maxRows) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_COLUMNS = 100;
    private static final Set<String> SUPPORTED = Set.of("csv", "json", "xlsx");
    private static final String FORMULA_LEADS = "=+-@\t\r";

    static {
        ZipSecureFile.setMaxEntrySize(64L * 1024 * 1024);
    }

    private TabularReader() {
    }

    public static List<Table> read(Path file, String originalName, Limits lim) throws IOException {
        String ext = FilenameUtils.getExtension(originalName).toLowerCase(Locale.ROOT);
        String source = FilenameUtils.getName(originalName);
        List<Table> tables = new ArrayList<>();
        if ("zip".equals(ext)) {
            readZip(file, lim, tables);
        } else {
            byte[] data;
            try (InputStream in = Files.newInputStream(file)) {
                data = readUpTo(in, lim.maxEntryBytes(), "The file is too large");
            }
            tables.addAll(parse(data, ext, source));
        }
        int rows = tables.stream().mapToInt(t -> t.rows().size()).sum();
        if (rows > lim.maxRows()) {
            throw new BusinessRuleException("The file has " + rows + " rows; the limit is " + lim.maxRows()
                    + " per upload. Split it into smaller files.");
        }
        if (tables.isEmpty()) {
            throw new BusinessRuleException("No data rows were found in the file");
        }
        return tables;
    }

    // ------------------------------------------------------------------ zip

    private static void readZip(Path file, Limits lim, List<Table> out) throws IOException {
        long total = 0;
        int entries = 0;
        try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String full = e.getName().replace("\u0000", "");
                String name = FilenameUtils.getName(full);
                String ext = FilenameUtils.getExtension(name).toLowerCase(Locale.ROOT);
                if (e.isDirectory() || name.isEmpty() || name.startsWith(".") || full.startsWith("__MACOSX/")
                        || !SUPPORTED.contains(ext)) {
                    continue;
                }
                if (++entries > lim.maxEntries()) {
                    throw new BusinessRuleException("The ZIP has more than " + lim.maxEntries() + " supported files");
                }
                long allowed = Math.min(lim.maxEntryBytes(), lim.maxTotalBytes() - total);
                byte[] data = readUpTo(zin, allowed, "The ZIP unpacks to more data than is allowed");
                total += data.length;
                out.addAll(parse(data, ext, name));
            }
        } catch (ZipException ze) {
            throw new BusinessRuleException("The ZIP file is damaged");
        }
    }

    private static byte[] readUpTo(InputStream in, long max, String tooBigMessage) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long n = 0;
        int r;
        while ((r = in.read(buf)) != -1) {
            n += r;
            if (n > max) {
                throw new BusinessRuleException(tooBigMessage);
            }
            out.write(buf, 0, r);
        }
        return out.toByteArray();
    }

    private static List<Table> parse(byte[] data, String ext, String source) {
        return switch (ext) {
            case "csv" -> {
                Table t = parseCsv(data, source);
                yield t.rows().isEmpty() ? List.of() : List.of(t);
            }
            case "json" -> parseJson(data, source);
            case "xlsx" -> parseXlsx(data, source);
            default -> throw new BusinessRuleException("Unsupported file type: ." + ext);
        };
    }

    // ------------------------------------------------------------------ csv

    /** Also used to read an edited review.csv. */
    public static Table parseCsv(byte[] data, String source) {
        String text = decode(data);
        CSVFormat fmt = CSVFormat.DEFAULT.builder().setDelimiter(sniff(text)).setIgnoreEmptyLines(true)
                .setTrim(true).build();
        List<CSVRecord> records;
        try (CSVParser parser = CSVParser.parse(text, fmt)) {
            records = parser.getRecords();
        } catch (IOException | IllegalStateException | IllegalArgumentException e) {
            throw new BusinessRuleException("'" + source + "' is not a valid CSV file");
        }
        if (records.isEmpty()) {
            return new Table(source, List.of(), List.of());
        }
        List<String> raw = new ArrayList<>();
        records.get(0).forEach(raw::add);
        if (raw.size() > MAX_COLUMNS) {
            throw new BusinessRuleException("'" + source + "' has more than " + MAX_COLUMNS + " columns");
        }
        List<String> headers = uniqueHeaders(raw);
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 1; i < records.size(); i++) {
            CSVRecord rec = records.get(i);
            Map<String, String> row = new LinkedHashMap<>();
            boolean any = false;
            for (int c = 0; c < headers.size(); c++) {
                String v = c < rec.size() ? unguard(rec.get(c).trim()) : "";
                if (!v.isEmpty()) {
                    any = true;
                }
                row.put(headers.get(c), v);
            }
            if (any) {
                rows.add(row);
            }
        }
        return new Table(source, headers, rows);
    }

    /** UTF-8 (BOM tolerated) first, Windows-1252 as the fallback for older Excel exports. */
    private static String decode(byte[] bytes) {
        int off = bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF ? 3 : 0;
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, off, bytes.length - off)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, off, bytes.length - off, Charset.forName("windows-1252"));
        }
    }

    private static char sniff(String text) {
        int end = text.indexOf('\n');
        String line = end < 0 ? text : text.substring(0, end);
        long comma = line.chars().filter(c -> c == ',').count();
        long semi = line.chars().filter(c -> c == ';').count();
        long tab = line.chars().filter(c -> c == '\t').count();
        if (tab > comma && tab > semi) {
            return '\t';
        }
        return semi > comma ? ';' : ',';
    }

    /**
     * Review files write a leading apostrophe before cells that start with = + - or @ (so a spreadsheet
     * program cannot run them as formulas). This undoes it on the way back in.
     */
    static String unguard(String v) {
        return v.length() > 1 && v.charAt(0) == '\'' && FORMULA_LEADS.indexOf(v.charAt(1)) >= 0 ? v.substring(1) : v;
    }

    // ------------------------------------------------------------------ json

    private static List<Table> parseJson(byte[] data, String source) {
        JsonNode root;
        try {
            root = MAPPER.readTree(data);
        } catch (IOException e) {
            throw new BusinessRuleException("'" + source + "' is not valid JSON");
        }
        List<Table> out = new ArrayList<>();
        if (root == null) {
            throw new BusinessRuleException("'" + source + "' is empty");
        }
        if (root.isArray()) {
            addJsonTable(out, source, root);
        } else if (root.isObject()) {
            boolean grouped = false;
            for (var it = root.fields(); it.hasNext(); ) {
                var entry = it.next();
                if (entry.getValue().isArray()) {
                    grouped = true;
                    addJsonTable(out, source + " / " + entry.getKey(), entry.getValue());
                }
            }
            if (!grouped) {
                ArrayNode single = MAPPER.createArrayNode();
                single.add(root);
                addJsonTable(out, source, single);
            }
        } else {
            throw new BusinessRuleException("'" + source + "' must contain a JSON array or object");
        }
        return out;
    }

    private static void addJsonTable(List<Table> out, String source, JsonNode array) {
        Set<String> headers = new LinkedHashSet<>();
        List<Map<String, String>> rows = new ArrayList<>();
        for (JsonNode item : array) {
            if (!item.isObject()) {
                continue;
            }
            Map<String, String> row = new LinkedHashMap<>();
            for (var it = item.fields(); it.hasNext(); ) {
                var field = it.next();
                JsonNode v = field.getValue();
                if (v.isValueNode()) {
                    headers.add(field.getKey());
                    row.put(field.getKey(), v.isNull() ? "" : unguard(v.asText().trim()));
                }
            }
            if (!row.isEmpty()) {
                rows.add(row);
            }
        }
        if (!rows.isEmpty()) {
            if (headers.size() > MAX_COLUMNS) {
                throw new BusinessRuleException("'" + source + "' has more than " + MAX_COLUMNS + " fields");
            }
            out.add(new Table(source, new ArrayList<>(headers), rows));
        }
    }

    // ------------------------------------------------------------------ xlsx

    private static List<Table> parseXlsx(byte[] data, String source) {
        List<Table> out = new ArrayList<>();
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(data))) {
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                if (wb.isSheetHidden(i) || wb.isSheetVeryHidden(i)) {
                    continue;
                }
                Sheet sheet = wb.getSheetAt(i);
                List<String> headers = null;
                List<Map<String, String>> rows = new ArrayList<>();
                for (Row row : sheet) {
                    if (headers == null) {
                        List<String> raw = new ArrayList<>();
                        int last = Math.min(row.getLastCellNum(), MAX_COLUMNS);
                        for (int c = 0; c < last; c++) {
                            raw.add(text(row.getCell(c)).trim());
                        }
                        if (raw.stream().allMatch(String::isBlank)) {
                            continue;
                        }
                        headers = uniqueHeaders(raw);
                    } else {
                        Map<String, String> map = new LinkedHashMap<>();
                        boolean any = false;
                        for (int c = 0; c < headers.size(); c++) {
                            String v = unguard(text(row.getCell(c)).trim());
                            if (!v.isEmpty()) {
                                any = true;
                            }
                            map.put(headers.get(c), v);
                        }
                        if (any) {
                            rows.add(map);
                        }
                    }
                }
                if (headers != null && !rows.isEmpty()) {
                    out.add(new Table(source + " / " + sheet.getSheetName(), headers, rows));
                }
            }
        } catch (BusinessRuleException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new BusinessRuleException("'" + source + "' is not a valid Excel (.xlsx) workbook");
        }
        return out;
    }

    private static String text(Cell c) {
        if (c == null) {
            return "";
        }
        CellType type = c.getCellType() == CellType.FORMULA ? c.getCachedFormulaResultType() : c.getCellType();
        return switch (type) {
            case STRING -> c.getStringCellValue();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(c)) {
                    LocalDateTime dt = c.getLocalDateTimeCellValue();
                    yield dt.toLocalTime().equals(java.time.LocalTime.MIDNIGHT) ? dt.toLocalDate().toString() : dt.toString();
                }
                yield BigDecimal.valueOf(c.getNumericCellValue()).stripTrailingZeros().toPlainString();
            }
            case BOOLEAN -> String.valueOf(c.getBooleanCellValue());
            default -> "";
        };
    }

    // ------------------------------------------------------------------ shared

    private static List<String> uniqueHeaders(List<String> raw) {
        List<String> out = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();
        for (int i = 0; i < raw.size(); i++) {
            String h = raw.get(i).isBlank() ? "column_" + (i + 1) : raw.get(i);
            int n = seen.merge(h, 1, Integer::sum);
            out.add(n == 1 ? h : h + "_" + n);
        }
        return out;
    }
}