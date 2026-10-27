package com.scpl.integration.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scpl.dto.ImportKind;
import com.scpl.dto.ImportRows.SegregatedRow;
import com.scpl.dto.ImportRows.SegregationInput;
import com.scpl.dto.ImportRows.SegregationResult;
import com.scpl.dto.ImportRows.TableSummary;
import com.scpl.service.DataSegregationProvider;
import com.scpl.util.ImportSchema;
import com.scpl.util.ImportValues;
import org.apache.commons.io.FilenameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Sends the uploaded file to a local model service (Ollama / LM Studio / llama.cpp behind a thin wrapper)
 * and reads back recognised rows. Active only when feature.ai.enabled=true.
 *
 * <p>Contract: POST {ai.endpoint}/api/parse, multipart/form-data with a "file" part, answering 200 with
 * {"players":[{...}],"teams":[...],"matches":[...],"awards":[...],"vendors":[...],"bills":[...]}.
 * Item keys use the same names as review.csv (aliases are accepted); an optional "confidence" is 0-100
 * (or 0-1). Everything returned is untrusted: values are sanitised, sizes capped, unknown kinds and
 * fields dropped, and the rows still go through validation, conflict checks and admin approval.
 *
 * <p>The file contains player contact details. The endpoint is operator-configured, and plain http is
 * accepted only for loopback or private-network addresses; anything else must be https.
 * Redirects are never followed, so a compromised endpoint cannot bounce the upload elsewhere.
 */
@Component
@Primary
@ConditionalOnProperty(name = "feature.ai.enabled", havingValue = "true")
public class LocalAiSegregationProvider implements DataSegregationProvider {

    public static final String NAME = "local-ai";
    private static final Logger log = LoggerFactory.getLogger(LocalAiSegregationProvider.class);
    private static final int MAX_RESPONSE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_ROWS = 5000;
    private static final int DEFAULT_CONFIDENCE = 60;

    private final RuleBasedSegregationProvider fallback;
    private final ObjectMapper mapper;
    private final URI target;
    private final Duration timeout;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public LocalAiSegregationProvider(RuleBasedSegregationProvider fallback, ObjectMapper mapper,
                                      @Value("${ai.endpoint:}") String endpoint,
                                      @Value("${ai.timeout-seconds:60}") int timeoutSeconds) {
        this.fallback = fallback;
        this.mapper = mapper;
        this.timeout = Duration.ofSeconds(Math.max(5, timeoutSeconds));
        this.target = resolveTarget(endpoint);
    }

    private static URI resolveTarget(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            log.warn("feature.ai.enabled=true but ai.endpoint is empty: the rule-based importer will be used");
            return null;
        }
        try {
            URI base = URI.create(endpoint.trim());
            String scheme = base.getScheme();
            if (base.getHost() == null || base.getUserInfo() != null
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException("not an http(s) URL");
            }
            if ("http".equalsIgnoreCase(scheme)) {
                InetAddress addr = InetAddress.getByName(base.getHost());
                if (!(addr.isLoopbackAddress() || addr.isSiteLocalAddress() || addr.isLinkLocalAddress())) {
                    throw new IllegalArgumentException("plain http is only allowed for local or private addresses");
                }
            }
            String s = base.toString();
            return URI.create((s.endsWith("/") ? s.substring(0, s.length() - 1) : s) + "/api/parse");
        } catch (IOException | RuntimeException e) {
            log.warn("ai.endpoint rejected ({}): the rule-based importer will be used", e.getMessage());
            return null;
        }
    }

    @Override
    public SegregationResult segregate(SegregationInput input) {
        if (!input.aiAllowed()) {
            return withWarning(fallback.segregate(input),
                    "AI import is not included in this society's plan; the rule-based importer was used.");
        }
        if (target == null) {
            return withWarning(fallback.segregate(input), "No usable AI endpoint is configured; the rule-based importer was used.");
        }
        try {
            SegregationResult ai = callAi(input);
            if (!ai.rows().isEmpty()) {
                return ai;
            }
            return withWarning(fallback.segregate(input), "The AI service found no rows; the rule-based importer was used.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return withWarning(fallback.segregate(input), "The AI service was interrupted; the rule-based importer was used.");
        } catch (IOException | RuntimeException e) {
            // Only the exception type is logged: messages can echo request or response content.
            log.warn("Local AI segregation failed ({}); using the rule-based importer", e.getClass().getSimpleName());
            return withWarning(fallback.segregate(input), "The AI service was unavailable; the rule-based importer was used.");
        }
    }

    private SegregationResult callAi(SegregationInput input) throws IOException, InterruptedException {
        String boundary = "scpl" + UUID.randomUUID().toString().replace("-", "");
        String ext = FilenameUtils.getExtension(input.fileName()).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        byte[] file = Files.readAllBytes(input.file());

        ByteArrayOutputStream body = new ByteArrayOutputStream(file.length + 512);
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"kinds\"\r\n\r\n"
                + "players,teams,matches,awards,vendors,bills\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"upload."
                + (ext.isEmpty() ? "bin" : ext) + "\"\r\nContent-Type: application/octet-stream\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        body.write(file);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder(target).timeout(timeout)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Accept", "application/json")
                .POST(BodyPublishers.ofByteArray(body.toByteArray())).build();
        HttpResponse<InputStream> response = client.send(request, BodyHandlers.ofInputStream());
        try (InputStream in = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode());
            }
            byte[] bytes = in.readNBytes(MAX_RESPONSE_BYTES + 1);
            if (bytes.length > MAX_RESPONSE_BYTES) {
                throw new IOException("response too large");
            }
            return parse(bytes, input);
        }
    }

    private SegregationResult parse(byte[] bytes, SegregationInput input) throws IOException {
        JsonNode root = mapper.readTree(bytes);
        List<SegregatedRow> rows = new ArrayList<>();
        List<TableSummary> summaries = new ArrayList<>();
        for (ImportKind kind : ImportKind.values()) {
            JsonNode array = root.path(kind.jsonKey());
            if (!array.isArray()) {
                continue;
            }
            int start = rows.size();
            int confidenceSum = 0;
            int index = 0;
            for (JsonNode item : array) {
                index++;
                if (rows.size() >= MAX_ROWS) {
                    break;
                }
                if (!item.isObject()) {
                    continue;
                }
                Map<String, String> fields = new LinkedHashMap<>();
                int confidence = DEFAULT_CONFIDENCE;
                for (var it = item.fields(); it.hasNext(); ) {
                    var entry = it.next();
                    JsonNode value = entry.getValue();
                    if (ImportValues.normalize(entry.getKey()).equals("confidence")) {
                        confidence = confidenceOf(value);
                        continue;
                    }
                    String field = ImportSchema.canonical(kind, entry.getKey());
                    if (field != null && value.isValueNode()) {
                        String v = RuleBasedSegregationProvider.cleanValue(field, value.isNull() ? "" : value.asText());
                        if (!v.isEmpty()) {
                            fields.putIfAbsent(field, v);
                        }
                    }
                }
                if (!fields.isEmpty()) {
                    rows.add(new SegregatedRow(kind, input.fileName(), index, fields, confidence));
                    confidenceSum += confidence;
                }
            }
            int count = rows.size() - start;
            if (count > 0) {
                summaries.add(new TableSummary("AI: " + input.fileName(), kind, confidenceSum / count, count, List.of()));
            }
        }
        return new SegregationResult(NAME, rows, summaries, List.of());
    }

    /** Accepts 0-100, or a 0-1 fraction. Anything unreadable falls back to a middling default. */
    private static int confidenceOf(JsonNode n) {
        if (!n.isNumber()) {
            return DEFAULT_CONFIDENCE;
        }
        double v = n.asDouble();
        if (v >= 0 && v <= 1) {
            v *= 100;
        }
        return (int) Math.max(0, Math.min(100, Math.round(v)));
    }

    private static SegregationResult withWarning(SegregationResult r, String message) {
        List<String> warnings = new ArrayList<>(r.warnings());
        warnings.add(message);
        return new SegregationResult(r.provider(), r.rows(), r.tables(), warnings);
    }
}