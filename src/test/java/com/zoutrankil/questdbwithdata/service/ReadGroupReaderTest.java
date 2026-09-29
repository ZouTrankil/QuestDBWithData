package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.repository.QuestDbBoundedReader;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

class ReadGroupReaderTest {
    record NameRow(String code, String name) {}
    static class Reader extends QuestDbBoundedReader {
        List<DatasetReadQuery> queries = new ArrayList<>();
        Runnable afterRead = () -> {};
        Reader() { super(null); }
        @Override public <T> DatasetReadPage<T> read(DatasetDefinition d, DatasetReadQuery q, String version,
                Function<DatasetValues,T> mapper) {
            queries.add(q); afterRead.run();
            if (q.equalities().containsKey("fail")) throw new IllegalStateException("SQL secret must not escape");
            var rows = q.equalities().containsKey("empty") ? List.<T>of()
                    : List.of(mapper.apply(new DatasetValues(Map.of("ts_code", "000001.SZ", "name", "sample"))));
            return new DatasetReadPage<>(d.datasetId(), d.schemaVersion(), version, Instant.now(), rows,
                    rows.isEmpty() ? null : new DatasetReadCursor("cursor-" + d.datasetId(), List.of("000001.SZ"), version));
        }
    }
    private ReadGroupReader reader(Reader read) {
        return new ReadGroupReader(new DatasetRegistry(List.of(() -> StockBasicDataset.DEFINITION,
                () -> StockBasicDataset.LATEST)), read, List.of(
                new ReadGroupReader.Binding<>(StockBasicDataset.DEFINITION, String.class,
                        row -> row.get("ts_code", String.class), () -> "source-A"),
                new ReadGroupReader.Binding<>(StockBasicDataset.LATEST, NameRow.class,
                        row -> new NameRow(row.get("ts_code", String.class), row.get("name", String.class)), () -> null)));
    }
    private DatasetReadQuery query(int size, Map<String,Object> filters) {
        return new DatasetReadQuery(List.of("ts_code", "name"), filters, null, null, null, size, null);
    }
    private ReadGroupRequest.Member member(String id, String dataset, DatasetReadQuery query) {
        return new ReadGroupRequest.Member(id, dataset, 1, query);
    }
    @Test void heterogeneousRowsKeepTheirOwnQueriesVersionsAndCursors() {
        var backend = new Reader(); var reader = reader(backend);
        var a = query(1, Map.of("ts_code", "000001.SZ")); var b = query(3, Map.of());
        var result = reader.read(new ReadGroupRequest(List.of(member("snap", "stock_basic_snapshot", a),
                member("latest", "stock_basic_latest", b)), Duration.ofSeconds(30)), () -> false);
        assertTrue(result.complete()); assertFalse(result.atomicSnapshot());
        assertEquals(List.of(a, b), backend.queries);
        var first = result.require("snap").typedPage(String.class);
        var second = result.require("latest").typedPage(NameRow.class);
        assertEquals(List.of("000001.SZ"), first.rows());
        assertEquals(List.of(new NameRow("000001.SZ", "sample")), second.rows());
        assertEquals("source-A", first.sourceVersion()); assertNull(second.sourceVersion());
        assertNotEquals(first.nextCursor(), second.nextCursor());
        assertThrows(IllegalArgumentException.class, () -> result.require("snap").typedPage(NameRow.class));
        var next = b.after(second.nextCursor());
        reader.read(new ReadGroupRequest(List.of(member("latest", "stock_basic_latest", next)),
                Duration.ofSeconds(30)), () -> false);
        assertSame(second.nextCursor(), backend.queries.getLast().cursor());
    }
    @Test void failuresRemainDistinctFromEmptyPagesAndDoNotHideOtherMembers() {
        var result = reader(new Reader()).read(new ReadGroupRequest(List.of(
                member("bad", "stock_basic_snapshot", query(1, Map.of("fail", true))),
                member("empty", "stock_basic_latest", query(1, Map.of("empty", true))),
                member("unknown", "missing", query(1, Map.of())),
                member("good", "stock_basic_latest", query(1, Map.of()))), Duration.ofSeconds(30)), () -> false);
        assertFalse(result.complete());
        assertEquals(ReadGroupReader.Status.FAILED, result.require("bad").status());
        assertEquals("IllegalStateException", result.require("bad").errorCode());
        assertNull(result.require("bad").page());
        assertTrue(result.require("empty").typedPage(NameRow.class).rows().isEmpty());
        assertEquals(ReadGroupReader.Status.FAILED, result.require("unknown").status());
        assertEquals(1, result.require("good").typedPage(NameRow.class).rows().size());
    }
    @Test void cancellationPreservesCompletedPageAndDoesNotReadLaterMembers() {
        var backend = new Reader(); boolean[] cancelled = {false}; backend.afterRead = () -> cancelled[0] = true;
        var result = reader(backend).read(new ReadGroupRequest(List.of(
                member("first", "stock_basic_snapshot", query(1, Map.of())),
                member("second", "stock_basic_latest", query(1, Map.of()))), Duration.ofSeconds(30)), () -> cancelled[0]);
        assertEquals(1, backend.queries.size());
        assertEquals(ReadGroupReader.Status.READ, result.require("first").status());
        assertEquals(ReadGroupReader.Status.CANCELLED, result.require("second").status());
        assertNull(result.require("second").page());
    }
    @Test void duplicateMembersAndUnboundedAggregateRequestsAreRejected() {
        var member = member("same", "stock_basic_latest", query(1, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ReadGroupRequest(List.of(member, member), Duration.ofSeconds(30)));
        var members = new ArrayList<ReadGroupRequest.Member>();
        for (int i = 0; i < 11; i++) members.add(member("m" + i, "stock_basic_latest", query(10000, Map.of())));
        assertThrows(IllegalArgumentException.class, () -> new ReadGroupRequest(members, Duration.ofSeconds(30)));
    }
}
