package com.scpl.integration.ai;

import com.scpl.dto.ImportKind;
import com.scpl.dto.ImportRows.ReviewRow;
import com.scpl.dto.ImportRows.SegregatedRow;
import com.scpl.dto.ImportRows.SegregationInput;
import com.scpl.dto.ImportRows.SegregationResult;
import com.scpl.dto.ImportRows.Table;
import com.scpl.dto.ImportRows.TableSummary;
import com.scpl.service.DataSegregationProvider;
import com.scpl.util.ImportSchema;
import com.scpl.util.ImportValues;
import com.scpl.util.ReviewCsv;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Header-name heuristics (the default in dev). Each table is scored against every kind: one point per
 * recognised column, a bonus when all required columns are present, and a small bonus when the file or
 * sheet name hints at the kind. The best eligible kind wins. Confidence reflects how much of the table
 * was understood, and is reduced when the runner-up scored almost as high.
 */
@Component
public class RuleBasedSegregationProvider implements DataSegregationProvider {

    public static final String NAME = "rule-based";
    private static final int MAX_CELL = 500;
    private static final int MAX_LONG_CELL = 2000;
    private static final Set<String> LONG_FIELDS = Set.of("notes", "description", "address");

    private record Detection(ImportKind kind, int confidence, Map<String, String> headerMap, List<String> unmapped) {
    }

    @Override
    public SegregationResult segregate(SegregationInput input) {
        List<SegregatedRow> rows = new ArrayList<>();
        List<TableSummary> summaries = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (Table t : input.tables()) {
            if (isReviewLayout(t)) {
                addReviewLayout(t, rows, summaries);
                continue;
            }
            Detection d = detect(t);
            if (d == null) {
                warnings.add("Could not recognise '" + t.source() + "': no matching column headings");
                continue;
            }
            int before = rows.size();
            for (int i = 0; i < t.rows().size(); i++) {
                Map<String, String> src = t.rows().get(i);
                Map<String, String> fields = new LinkedHashMap<>();
                for (var e : d.headerMap().entrySet()) {
                    String v = cleanValue(e.getValue(), src.get(e.getKey()));
                    if (!v.isEmpty()) {
                        // Two source columns can map to one field; the first non-blank value wins.
                        fields.putIfAbsent(e.getValue(), v);
                    }
                }
                if (fields.isEmpty()) {
                    continue;
                }
                int confidence = d.confidence();
                if (ImportValues.formatProblem(d.kind(), fields) != null) {
                    confidence = Math.max(5, confidence - 30);
                }
                rows.add(new SegregatedRow(d.kind(), t.source(), i + 2, fields, confidence)); // +2: header is line 1
            }
            summaries.add(new TableSummary(t.source(), d.kind(), d.confidence(), rows.size() - before, d.unmapped()));
            if (!d.unmapped().isEmpty()) {
                warnings.add("'" + t.source() + "': columns ignored: " + String.join(", ", d.unmapped()));
            }
        }
        return new SegregationResult(NAME, rows, summaries, warnings);
    }

    private Detection detect(Table t) {
        List<String> named = t.headers().stream().filter(h -> !ImportValues.normalize(h).isEmpty()).toList();
        if (named.isEmpty()) {
            return null;
        }
        ImportKind hint = ImportSchema.hint(t.source());
        ImportKind best = null;
        Map<String, String> bestMap = null;
        int bestScore = -1;
        int second = -1;
        for (ImportKind k : ImportKind.values()) {
            Map<String, String> map = new LinkedHashMap<>();
            Set<String> matched = new HashSet<>();
            for (String h : t.headers()) {
                String canonical = ImportSchema.canonical(k, h);
                if (canonical != null) {
                    map.put(h, canonical);
                    matched.add(canonical);
                }
            }
            if (!matched.containsAll(ImportSchema.of(k).required())) {
                continue;
            }
            int score = matched.size() * 10 + 20 + (k == hint ? 15 : 0);
            if (score > bestScore) {
                second = bestScore;
                best = k;
                bestScore = score;
                bestMap = map;
            } else if (score > second) {
                second = score;
            }
        }
        if (best == null) {
            return null;
        }
        double coverage = (double) bestMap.size() / named.size();
        int confidence = (int) Math.round(40 + 60 * coverage);
        if (second >= 0 && bestScore - second < 10) {
            confidence -= 25; // another kind fits almost as well: say so
        }
        confidence = Math.max(10, Math.min(100, confidence));
        List<String> unmapped = new ArrayList<>(named);
        unmapped.removeAll(bestMap.keySet());
        return new Detection(best, confidence, bestMap, unmapped);
    }

    // ------------------------------------------------------------------ review / failed files re-uploaded as a new batch

    private static boolean isReviewLayout(Table t) {
        Set<String> n = new HashSet<>();
        t.headers().forEach(h -> n.add(ImportValues.normalize(h)));
        return n.contains("kind") && n.contains("sourcerow");
    }

    private static void addReviewLayout(Table t, List<SegregatedRow> rows, List<TableSummary> summaries) {
        Map<ImportKind, Integer> counts = new EnumMap<>(ImportKind.class);
        for (ReviewRow r : ReviewCsv.fromTable(t)) {
            if (r.include()) {
                rows.add(new SegregatedRow(r.kind(), r.sourceFile(), r.sourceRow(), r.fields(), r.confidence()));
                counts.merge(r.kind(), 1, Integer::sum);
            }
        }
        counts.forEach((kind, n) -> summaries.add(new TableSummary(t.source(), kind, 100, n, List.of())));
    }

    /** Shared with the AI provider, so both sanitise identically: control characters out, length capped. */
    public static String cleanValue(String field, String raw) {
        if (raw == null) {
            return "";
        }
        String v = raw.replaceAll("\\p{Cntrl}", " ").trim();
        int max = LONG_FIELDS.contains(field) ? MAX_LONG_CELL : MAX_CELL;
        return v.length() > max ? v.substring(0, max) : v;
    }
}