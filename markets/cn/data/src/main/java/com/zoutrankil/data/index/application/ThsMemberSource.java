package com.zoutrankil.data.index.application;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.repository.FileEvidenceStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.client.dto.TushareThsMemberDto;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.index.mapper.ThsMemberMapper;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.BooleanSupplier;

/** One board per request. The endpoint does not document a date or paging parameter. */
public final class ThsMemberSource {
    public static final List<String> FIELDS = List.of("ts_code", "con_code", "con_name", "weight",
            "in_date", "out_date", "is_new");
    public static final PageContract CONTRACT = new PageContract("ths_member", FIELDS,
            List.of("ts_code", "con_code"), Set.of("ts_code", "con_code"), PageContract.Paging.NONE,
            PageContract.Completion.SHORT_PAGE, null, null, 10000, 10000, 1, 10000,
            "https://tushare.pro/document/2?doc_id=261: board/constituent filters; no documented paging/date; cap hit incomplete");
    private final TusharePageService pages;
    private final Path evidence;

    public ThsMemberSource(TusharePageService pages, Path evidence) {
        this.pages = Objects.requireNonNull(pages);
        this.evidence = Objects.requireNonNull(evidence);
    }

    public SyncJobRunner.Page<ThsMember> fetchBoard(String boardCode, Instant observedAt,
                                                     BooleanSupplier cancelled) throws Exception {
        if (!ThsIndex.validCode(boardCode)) throw new IllegalArgumentException("Exact THS board code required");
        Objects.requireNonNull(observedAt);
        if (observedAt.getNano() % 1000 != 0) throw new IllegalArgumentException("Microsecond observation required");
        var mapper = new ThsMemberMapper();
        var raw = new ArrayList<Map<String, JsonNode>>();
        var completion = pages.execute(CONTRACT, Map.of("ts_code", boardCode),
                (page, receipt) -> raw.addAll(page.rows()), row -> {
                    var item = mapper.fromSource(decode(row), observedAt);
                    if (!boardCode.equals(item.boardCode()))
                        throw new IllegalArgumentException("THS member outside frozen board scope");
                }, cancelled);
        if (completion.pages() != 1 || completion.rows() != raw.size() || raw.size() >= 10000)
            throw new IllegalStateException("THS board response has no terminal evidence");
        var typed = raw.stream().map(row -> mapper.fromSource(decode(row), observedAt)).toList();
        var body = new LinkedHashMap<String, Object>();
        body.put("endpoint", "ths_member");
        body.put("parameters", Map.of("ts_code", boardCode));
        body.put("fields", FIELDS);
        body.put("observedAt", observedAt);
        body.put("rows", raw);
        body.put("completion", completion);
        byte[] bytes = JobDefinitionJson.mapper().writeValueAsBytes(body);
        if (bytes.length > 16 * 1024 * 1024) throw new IllegalArgumentException("THS board evidence exceeds byte bound");
        Files.createDirectories(evidence);
        Path receipt = evidence.resolve("source-" + UUID.randomUUID() + ".json");
        FileEvidenceStore.writeNew(receipt, bytes);
        return new SyncJobRunner.Page<>(typed,
                FileEvidenceStore.sha256(bytes),
                receipt.toString(), null);
    }

    /** Reconstruct a frozen source page for stopped-writer recovery without a new provider request. */
    public static SyncJobRunner.Page<ThsMember> reopen(Path receipt, String expectedHash,
                                                       String boardCode) throws Exception {
        if (!ThsIndex.validCode(boardCode) || !expectedHash.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Frozen board and source hash required");
        if (Files.size(receipt) > 16L * 1024 * 1024) throw new IllegalStateException("THS source receipt exceeds bound");
        byte[] bytes = FileEvidenceStore.readBounded(receipt, 16 * 1024 * 1024,
                () -> new IllegalStateException("THS source receipt exceeds bound"));
        String hash = FileEvidenceStore.sha256(bytes);
        if (!hash.equals(expectedHash)) throw new IllegalStateException("THS source receipt hash changed");
        var json = JobDefinitionJson.mapper();
        var proof = json.readTree(bytes);
        if (!"ths_member".equals(proof.path("endpoint").asText())
                || !boardCode.equals(proof.path("parameters").path("ts_code").asText())
                || proof.path("parameters").size() != 1
                || !json.valueToTree(FIELDS).equals(proof.path("fields"))
                || proof.path("completion").path("pages").asInt() != 1)
            throw new IllegalStateException("THS source scope or completion changed");
        var raw = proof.path("rows");
        if (!raw.isArray() || raw.size() >= 10000
                || raw.size() != proof.path("completion").path("rows").asInt())
            throw new IllegalStateException("THS source rows incomplete");
        var observed = Instant.parse(proof.path("observedAt").asText());
        var mapper = new ThsMemberMapper();
        var typed = new ArrayList<ThsMember>();
        var keys = new HashSet<ThsMember.Key>();
        for (var item : raw) {
            var row = json.convertValue(item, new com.fasterxml.jackson.core.type.TypeReference<Map<String, JsonNode>>() {});
            if (!row.keySet().containsAll(FIELDS)) throw new IllegalStateException("THS source field missing");
            var member = mapper.fromSource(decode(row), observed);
            if (!member.boardCode().equals(boardCode) || !keys.add(member.key()))
                throw new IllegalStateException("THS source board or business key changed");
            typed.add(member);
        }
        return new SyncJobRunner.Page<>(typed, hash, receipt.toString(), null);
    }

    private static TushareThsMemberDto decode(Map<String, JsonNode> row) {
        var weight = row.get("weight");
        Double numeric = null;
        if (weight != null && !weight.isNull()) {
            if (!weight.isNumber()) throw new IllegalArgumentException("Numeric THS member weight required");
            numeric = weight.doubleValue();
        }
        return new TushareThsMemberDto(text(row, "ts_code"), text(row, "con_code"),
                text(row, "con_name"), numeric, text(row, "in_date"), text(row, "out_date"),
                text(row, "is_new"));
    }

    private static String text(Map<String, JsonNode> row, String field) {
        var value = row.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw new IllegalArgumentException("Text THS member field required: " + field);
        return value.textValue();
    }
}
