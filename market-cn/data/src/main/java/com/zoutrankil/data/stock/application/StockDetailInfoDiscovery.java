package com.zoutrankil.data.stock.application;
import com.zoutrankil.data.stock.domain.policy.StockDetailInfoMerge;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.repository.FileEvidenceStore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zoutrankil.data.client.dto.TushareStockDetailDto;
import com.zoutrankil.data.domain.PageContract;
import com.zoutrankil.data.domain.StockDetailInfo;
import com.zoutrankil.data.stock.mapper.StockDetailInfoMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Bounded L/D/P and exchange discovery for new stock identities. Does not publish data. */
public final class StockDetailInfoDiscovery {
    public record Result(List<StockDetailInfo> rows, List<Path> receipts, Map<String,Integer> sliceCounts) {
        public Result {
            rows = List.copyOf(rows);
            receipts = List.copyOf(receipts);
            sliceCounts = Map.copyOf(sliceCounts);
        }
    }
    private static final List<String> STATUSES = List.of("L", "D", "P");
    private static final List<String> EXCHANGES = List.of("SSE", "SZSE", "BSE");
    private static final int SOURCE_CAP = 6_000;
    private static final int TOTAL_CAP = StockDetailInfoMerge.MAX_ROWS;
    private final TusharePageService pages;
    private final Path evidence;
    private final StockDetailInfoMapper mapper = new StockDetailInfoMapper();

    public StockDetailInfoDiscovery(TusharePageService pages, Path evidence) {
        this.pages = Objects.requireNonNull(pages);
        this.evidence = Objects.requireNonNull(evidence).toAbsolutePath().normalize();
    }

    public Result fetch(Instant observedAt, BooleanSupplier cancelled) throws Exception {
        Objects.requireNonNull(observedAt);
        if (observedAt.getNano() % 1_000 != 0) throw new IllegalArgumentException("Microsecond observation required");
        Objects.requireNonNull(cancelled);
        var contract = new PageContract("stock_basic", StockDetailInfoSource.FIELDS, List.of("ts_code"),
                Set.of("list_status", "exchange"), PageContract.Paging.NONE, PageContract.Completion.SHORT_PAGE,
                null, null, SOURCE_CAP, SOURCE_CAP, 1, SOURCE_CAP,
                "Official stock_basic maximum 6000; status and exchange filters; cap-sized response is incomplete");
        var all = new LinkedHashMap<String,StockDetailInfo>();
        var receipts = new ArrayList<Path>();
        var counts = new LinkedHashMap<String,Integer>();
        var json = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        for (var status : STATUSES) for (var exchange : EXCHANGES) {
            if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException("Discovery cancelled");
            var params = Map.<String,Object>of("list_status", status, "exchange", exchange);
            var raw = new ArrayList<Map<String,JsonNode>>();
            var complete = pages.execute(contract, params, (page, receipt) -> raw.addAll(page.rows()), row -> {
                if (!status.equals(text(row, "list_status")) || !exchange.equals(text(row, "exchange")))
                    throw new IllegalArgumentException("Stock outside requested status/exchange");
            }, cancelled);
            if (complete.pages() != 1 || complete.rows() != raw.size() || raw.size() >= SOURCE_CAP)
                throw new IllegalStateException("Discovery slice lacks complete source receipt");
            for (var row : raw) {
                var value = mapper.fromSource(new TushareStockDetailDto(text(row,"ts_code"),text(row,"symbol"),
                        text(row,"name"),text(row,"area"),text(row,"industry"),text(row,"fullname"),
                        text(row,"enname"),text(row,"cnspell"),text(row,"market"),text(row,"exchange"),
                        text(row,"curr_type"),text(row,"list_status"),text(row,"list_date"),
                        text(row,"delist_date"),text(row,"is_hs"),text(row,"act_name"),text(row,"act_ent_type")),
                        observedAt);
                if (all.putIfAbsent(value.key(),value) != null)
                    throw new IllegalStateException("Stock appears in multiple discovery slices: " + value.key());
                if (all.size() > TOTAL_CAP) throw new IllegalStateException("Discovery exceeds bounded target budget");
            }
            byte[] body = json.writeValueAsBytes(Map.of("endpoint","stock_basic","parameters",params,
                    "rows",raw,"sourceRowCap",SOURCE_CAP,"complete",true));
            Files.createDirectories(evidence);
            var path = evidence.resolve("discovery-"+status+"-"+exchange+"-"+UUID.randomUUID()+".json");
            FileEvidenceStore.writeNew(path,body);
            receipts.add(path);
            counts.put(status+":"+exchange,raw.size());
        }
        if (all.isEmpty()) throw new IllegalStateException("All stock discovery slices empty; no snapshot admission");
        return new Result(new ArrayList<>(all.values()),receipts,counts);
    }

    private static String text(Map<String,JsonNode> row, String field) {
        var value = row.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw new IllegalArgumentException("Text stock field required: " + field);
        return value.textValue();
    }
}
