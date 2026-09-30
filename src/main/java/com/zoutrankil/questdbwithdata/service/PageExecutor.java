package com.zoutrankil.questdbwithdata.service;

import com.fasterxml.jackson.databind.*;
import com.zoutrankil.questdbwithdata.domain.PageContract;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Processes one page at a time. A failed slice never returns a completion/checkpoint receipt. */
public final class PageExecutor {
    public record Page(List<Map<String, JsonNode>> rows, String nextCursor, boolean explicitEnd, String sourceVersion) {
        public Page { rows = rows.stream().map(Map::copyOf).toList(); }
    }
    public record Receipt(int page, long offset, String cursor, int rows, String digest, String sourceVersion) {}
    public record Completed(int pages, int rows, String sourceVersion) {}
    @FunctionalInterface public interface Fetcher { Page fetch(Map<String, Object> params) throws Exception; }
    @FunctionalInterface public interface Consumer { void accept(Page page, Receipt receipt) throws Exception; }
    @FunctionalInterface public interface Validator { void validate(Map<String, JsonNode> row) throws Exception; }
    public static class Incomplete extends Exception {
        private final int consumedPages;
        private final int consumedRows;
        Incomplete(String reason, int pages, int rows) {
            super(reason); consumedPages = pages; consumedRows = rows;
        }
        public int consumedPages() { return consumedPages; }
        public int consumedRows() { return consumedRows; }
    }
    public static final class Truncated extends Incomplete {
        Truncated(int pages, int rows) { super("Unpaged response reached source cap; completeness unknown", pages, rows); }
    }
    private static final ObjectMapper JSON = new ObjectMapper();

    public Completed execute(PageContract contract, Map<String, Object> baseParams, Fetcher fetcher,
                             Consumer consumer, Validator validator, BooleanSupplier cancelled) throws Exception {
        if (!contract.allowedParameters().containsAll(baseParams.keySet())) throw new IllegalArgumentException("Unsupported source parameter");
        if (contract.paging() != PageContract.Paging.NONE && (baseParams.containsKey(contract.limitParameter())
                || baseParams.containsKey(contract.positionParameter()))) throw new IllegalArgumentException("Executor owns paging parameters");
        int pages = 0, rows = 0;
        long offset = 0;
        String cursor = null, version = null;
        var fingerprints = new HashSet<String>();
        var cursors = new HashSet<String>();
        var keys = new HashSet<String>();
        while (true) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new Incomplete("Slice cancelled", pages, rows);
            if (pages >= contract.maxPages()) throw new Incomplete("Page budget exhausted before terminal evidence", pages, rows);
            var params = new LinkedHashMap<>(baseParams);
            if (contract.paging() != PageContract.Paging.NONE) {
                params.put(contract.limitParameter(), contract.pageSize());
                if (contract.paging() == PageContract.Paging.OFFSET) params.put(contract.positionParameter(), offset);
                else if (cursor != null) params.put(contract.positionParameter(), cursor);
            }
            Page page;
            try { page = fetcher.fetch(Collections.unmodifiableMap(params)); }
            catch (Exception failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new Incomplete("Source page failed (" + failureTypes(failure) + ")", pages, rows);
            }
            if (page == null) throw new Incomplete("Null source page", pages, rows);
            if (cancelled.getAsBoolean()) throw new Incomplete("Slice cancelled after fetch", pages, rows);
            int count = page.rows().size();
            int bound = contract.paging() == PageContract.Paging.NONE ? contract.sourceRowCap() : contract.pageSize();
            if (count > bound || (long) rows + count > contract.maxRows()) throw new Incomplete("Page or row bound exceeded", pages, rows);
            if (pages == 0) version = page.sourceVersion();
            else if (!Objects.equals(version, page.sourceVersion())) throw new Incomplete("Source version changed between pages", pages, rows);
            String digest = digest(page.rows().stream().map(TreeMap::new).toList());
            if (count > 0 && !fingerprints.add(digest)) throw new Incomplete("Repeated page", pages, rows);
            for (var row : page.rows()) {
                if (!row.keySet().containsAll(contract.fields())) throw new Incomplete("Missing declared source field", pages, rows);
                var key = new ArrayList<JsonNode>();
                for (var field : contract.businessKey()) {
                    var value = row.get(field);
                    if (value == null || value.isNull() || !value.isValueNode() || value.isTextual() && value.asText().isBlank()) {
                        throw new Incomplete("Missing or invalid business key", pages, rows);
                    }
                    key.add(value);
                }
                if (!keys.add(digest(key))) throw new Incomplete("Duplicate business key within/across pages", pages, rows);
                try { validator.validate(row); }
                catch (Exception failure) { throw new Incomplete("Row outside declared slice or invalid (" + failure.getClass().getSimpleName() + ")", pages, rows); }
            }
            if (contract.paging() == PageContract.Paging.NONE && count >= contract.sourceRowCap() && !page.explicitEnd()) {
                throw new Truncated(pages, rows); // No rows delivered from a potentially truncated window.
            }
            boolean complete = contract.completion() == PageContract.Completion.EXPLICIT_END
                    ? page.explicitEnd() : count < bound;
            if (contract.paging() == PageContract.Paging.CURSOR) {
                if (!complete && (page.nextCursor() == null || page.nextCursor().isBlank()
                        || page.nextCursor().equals(cursor) || !cursors.add(page.nextCursor()))) {
                    throw new Incomplete("Cursor absent, repeated or not advancing", pages, rows);
                }
                if (complete && page.nextCursor() != null) throw new Incomplete("Conflicting terminal cursor", pages, rows);
            }
            if (contract.paging() == PageContract.Paging.NONE && !complete) throw new Incomplete("No terminal evidence for unpaged source", pages, rows);
            if (contract.paging() == PageContract.Paging.OFFSET && !complete && count == 0) {
                throw new Incomplete("Offset cannot advance without terminal evidence", pages, rows);
            }
            try { if (count > 0) consumer.accept(page, new Receipt(pages + 1, offset, cursor, count, digest, version)); }
            catch (Exception failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new Incomplete("Page consumer failed; prior effects require reconciliation", pages, rows);
            }
            pages++;
            rows += count;
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                throw new Incomplete("Slice cancelled after consumption", pages, rows);
            }
            if (complete) return new Completed(pages, rows, version);
            offset = Math.addExact(offset, count);
            cursor = page.nextCursor();
        }
    }
    private static String failureTypes(Throwable failure) {
        var types=new ArrayList<String>();var seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        for(Throwable current=failure;current!=null&&types.size()<4&&seen.add(current);current=current.getCause()) types.add(current.getClass().getSimpleName());
        return String.join("->",types);
    }
    private static String digest(Object value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(JSON.writeValueAsString(value).getBytes(StandardCharsets.UTF_8)));
    }
}
