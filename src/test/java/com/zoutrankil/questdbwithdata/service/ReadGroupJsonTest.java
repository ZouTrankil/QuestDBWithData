package com.zoutrankil.questdbwithdata.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.repository.QuestDbBoundedReader;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReadGroupJsonTest {
    private final QuestDbBoundedReader backend = new QuestDbBoundedReader(null);
    private final ReadGroupJson parser = new ReadGroupJson(
            new DatasetRegistry(List.of(() -> StockBasicDataset.DEFINITION)), backend);
    private static final String REQUEST = """
            {"timeoutMillis":30000,"members":[{"memberId":"stocks","datasetId":"stock_basic_snapshot",
            "definitionVersion":1,"query":{"columns":["snapshot_ts","ts_code","name"],
            "equalities":{"list_date":"1991-04-03"},"rangeColumn":"snapshot_ts",
            "fromInclusive":"2026-09-29T00:00:00Z","toExclusive":"2026-09-30T00:00:00Z","pageSize":2}}]}
            """;
    private ReadGroupRequest parse(String json) throws Exception { return parser.parse(json.getBytes(StandardCharsets.UTF_8)); }

    @Test void datesAndCursorRoundTripWithoutTypeOrPrecisionLoss() throws Exception {
        var first = parse(REQUEST).members().getFirst().query();
        assertEquals(LocalDate.of(1991,4,3), first.equalities().get("list_date"));
        assertEquals(Instant.parse("2026-09-29T00:00:00Z"), first.fromInclusive());
        var cursor = new DatasetReadCursor(backend.prepare(StockBasicDataset.DEFINITION, first, null).fingerprint(),
                List.of(Instant.parse("2026-09-29T00:00:00.123456Z"), "000001.SZ"), null);
        var json = JobDefinitionJson.mapper(); var node = json.readTree(REQUEST);
        ((ObjectNode) node.path("members").get(0).path("query")).set("cursor", json.valueToTree(cursor));
        var restored = parse(json.writeValueAsString(node)).members().getFirst().query().cursor();
        assertEquals(cursor, restored);
        assertTrue(restored.keyValues().getFirst() instanceof Instant);
        ((ObjectNode) node.path("members").get(0).path("query")).put("pageSize", 3);
        // Page size is allowed to change by the bounded reader's keyset contract.
        assertNotNull(parse(json.writeValueAsString(node)).members().getFirst().query().cursor());
        ((ObjectNode) node.path("members").get(0).path("query").path("equalities")).put("list_date", "1991-04-04");
        assertThrows(IllegalArgumentException.class, () -> parse(json.writeValueAsString(node)));
    }
    @Test void rejectsAmbiguousDatesDuplicateKeysUnknownPropertiesAndOverflow() {
        assertThrows(Exception.class, () -> parse(REQUEST.replace("1991-04-03", "19910403")));
        assertThrows(Exception.class, () -> parse(REQUEST.replace("2026-09-29T00:00:00Z", "2026-09-29T00:00:00")));
        assertThrows(Exception.class, () -> parse(REQUEST.replace("\"timeoutMillis\":30000", "\"timeoutMillis\":30000,\"timeoutMillis\":1")));
        assertThrows(Exception.class, () -> parse(REQUEST.replace("\"pageSize\":2", "\"pageSize\":2147483648")));
        assertThrows(Exception.class, () -> parse(REQUEST.replace("\"pageSize\":2", "\"pageSize\":2,\"typo\":true")));
        assertThrows(Exception.class, () -> parse(REQUEST + " {}"));
        assertThrows(Exception.class, () -> parse(REQUEST.replace("2026-09-29T00:00:00Z", "2026-09-29T00:00:00.123456789Z")));
    }
    @Test void projectionJsonPreservesNullsAndDoesNotExposeMutableBinaryValues() throws Exception {
        var data = new LinkedHashMap<String,Object>(); data.put("missing", null); data.put("bytes", new byte[]{1});
        var values = new DatasetValues(data);
        ((byte[]) values.asMap().get("bytes"))[0] = 9;
        assertEquals(1, values.get("bytes", byte[].class)[0]);
        var json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(values));
        assertTrue(json.has("missing")); assertTrue(json.get("missing").isNull());
    }
}
