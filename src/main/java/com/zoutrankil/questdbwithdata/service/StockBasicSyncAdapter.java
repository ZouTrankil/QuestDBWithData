package com.zoutrankil.questdbwithdata.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.questdbwithdata.client.TushareClient;
import com.zoutrankil.questdbwithdata.client.dto.TushareStockBasicDto;
import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.mapper.StockBasicMapper;
import com.zoutrankil.questdbwithdata.domain.temporal.TemporalValues;
import com.zoutrankil.questdbwithdata.repository.StockBasicWritePort;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import static com.zoutrankil.questdbwithdata.domain.SyncJobDefinition.*;

/** Existing sample's bounded current-directory source. No historical incremental capability is claimed. */
public final class StockBasicSyncAdapter implements SyncJobRunner.Adapter<StockBasicSnapshot,StockBasicSnapshotKey> {
    private final TusharePageService pages;
    private final StockBasicMapper mapper;
    private final StockBasicWritePort port;
    private final Path evidenceRoot;
    public StockBasicSyncAdapter(TusharePageService pages, StockBasicMapper mapper,
                                StockBasicWritePort port, Path evidenceRoot) {
        this.pages = Objects.requireNonNull(pages); this.mapper = Objects.requireNonNull(mapper);
        this.port = Objects.requireNonNull(port); this.evidenceRoot = evidenceRoot.toAbsolutePath().normalize();
    }
    public static SyncJobDefinition definition(boolean enabled) {
        return new SyncJobDefinition("data.stock_basic", 2, "stock_basic_snapshot", 1, "StockBasicSyncService",
                Set.of(Mode.SNAPSHOT), Mode.SNAPSHOT,
                Map.of("codes", new Parameter(ParameterType.STRING_LIST, true, 12, 100, Set.of())),
                "tushare.shared", "stock_basic.snapshot", "questdb.full_key_values",
                new RetryPolicy(3, Duration.ofSeconds(1), Duration.ofMinutes(2)), Duration.ofMinutes(20),
                new Budget(1, 100, 100, 100, 1024*1024), 0, List.of(), Frequency.MANUAL,
                ZoneId.of("Asia/Shanghai"), enabled, false);
    }
    @Override public void preflight(FrozenRequest request) {
        if (!request.definition().datasetId().equals("stock_basic_snapshot")
                || request.definition().datasetVersion() != 1 || request.definition().version() != 2
                || request.mode() != Mode.SNAPSHOT || request.from() != null || request.to() != null)
            throw new IllegalArgumentException("Bounded current snapshot contract required");
        var codes = codes(request);
        if (codes.isEmpty() || codes.size() > 100 || codes.size() > request.definition().budget().maxSlices()
                || new HashSet<>(codes).size() != codes.size())
            throw new IllegalArgumentException("Explicit bounded codes required");
        for (var code : codes) {
            if (!code.matches("[0-9]{6}\\.(SZ|SH|BJ)")) throw new IllegalArgumentException("Unsupported stock code");
        }
        port.preflight();
    }
    @SuppressWarnings("unchecked") private static List<String> codes(FrozenRequest request) {
        Object codes = request.parameters().get("codes");
        if (!(codes instanceof List<?> list) || list.stream().anyMatch(v -> !(v instanceof String)))
            throw new IllegalArgumentException("Typed stock code list required");
        return (List<String>) list;
    }
    @Override public SyncJobRunner.SourceCompletion fetch(FrozenRequest request,
            SyncJobRunner.PageConsumer<StockBasicSnapshot> consumer, BooleanSupplier cancelled) throws Exception {
        var contract = new PageContract("stock_basic", TushareClient.STOCK_FIELDS, List.of("ts_code"),
                Set.of("ts_code", "list_status"), PageContract.Paging.NONE, PageContract.Completion.SHORT_PAGE,
                null, null, 2, 2, 1, 2, "Current listed stock snapshot queried by exact code");
        int[] totals = {0,0};
        var evidenceFiles = new ArrayList<String>();
        var json = new ObjectMapper().configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true);
        Files.createDirectories(evidenceRoot);
        for (String code : codes(request)) {
            if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException("Source cancelled");
            Map<String,Object> params = Map.of("ts_code", code, "list_status", "L");
            var completed = pages.execute(contract, params, (page, receipt) -> {
                var typed = new ArrayList<StockBasicSnapshot>();
                for (var row : page.rows()) {
                    var dto = new TushareStockBasicDto(text(row,"ts_code"), text(row,"symbol"), text(row,"name"),
                            text(row,"area"), text(row,"industry"), text(row,"list_date"));
                    typed.add(new StockBasicSnapshot(new TemporalValues.CalendarTimestamp(request.logicalDate()).storageCarrier(), mapper.toDomain(dto)));
                }
                byte[] body = json.writeValueAsBytes(Map.of("endpoint","stock_basic", "parameters", params,
                        "logicalDate", request.logicalDate().toString(), "rows", page.rows()));
                String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
                Path evidence = evidenceRoot.resolve("source-"+UUID.randomUUID()+".json");
                Files.write(evidence, body, StandardOpenOption.CREATE_NEW);
                consumer.accept(new SyncJobRunner.Page<>(typed, fingerprint, evidence.toString(), null));
                evidenceFiles.add(evidence.toString()); totals[0]++; totals[1] += typed.size();
            }, row -> {
                if (!code.equals(text(row,"ts_code"))) throw new IllegalArgumentException("Source returned another code");
            }, cancelled);
            if (completed.rows() == 0) {
                // F006 deliberately does not invoke its page consumer for an empty terminal response.
                byte[] body = json.writeValueAsBytes(Map.of("endpoint","stock_basic", "parameters",params,
                        "logicalDate",request.logicalDate().toString(), "rows",List.of(), "complete",true));
                Path evidence = evidenceRoot.resolve("empty-"+UUID.randomUUID()+".json");
                Files.write(evidence,body,StandardOpenOption.CREATE_NEW);
                consumer.accept(new SyncJobRunner.Page<>(List.of(),
                        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)), evidence.toString(), null));
                evidenceFiles.add(evidence.toString()); totals[0]++;
            }
        }
        Path complete = evidenceRoot.resolve("complete-"+UUID.randomUUID()+".json");
        Files.write(complete, json.writeValueAsBytes(Map.of("pages",totals[0], "rows",totals[1], "complete",true,
                "responseEvidence",evidenceFiles)), StandardOpenOption.CREATE_NEW);
        return new SyncJobRunner.SourceCompletion(totals[0],totals[1],true,complete.toString());
    }
    private static String text(Map<String,com.fasterxml.jackson.databind.JsonNode> row, String field) {
        var node = row.get(field); return node == null || node.isNull() ? null : node.asText();
    }
    @Override public VerifiedBatchExecutor.Codec<StockBasicSnapshot,StockBasicSnapshotKey> codec() { return StockBasicWritePort.CODEC; }
    @Override public VerifiedBatchExecutor.Port<StockBasicSnapshot,StockBasicSnapshotKey> port() { return port; }
}
