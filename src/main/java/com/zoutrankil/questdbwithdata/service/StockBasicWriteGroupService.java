package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.repository.StockBasicWritePort;
import io.questdb.client.QuestDB;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.nio.file.Path;
import java.time.*;
import java.util.*;

/** Application entry for the existing registered writable sample; other owners must be admitted explicitly. */
@Service
public class StockBasicWriteGroupService {
    private final DatasetRegistry datasets;
    private final StockBasicJobService target;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;
    private final Path ledger;
    public StockBasicWriteGroupService(DatasetRegistry datasets, StockBasicJobService target, JdbcTemplate jdbc,
            @Lazy QuestDB questdb, @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger) {
        this.datasets=datasets; this.target=target; this.jdbc=jdbc; this.questdb=questdb;
        this.ledger=Path.of(ledger).toAbsolutePath().normalize();
    }
    public SyncGroupRunner.Result run(Path file, String priorRun) throws Exception {
        var request = new WriteGroupJson(datasets).read(file);
        if (request.members().stream().anyMatch(m -> !m.datasetId().equals(StockBasicDataset.DEFINITION.datasetId())))
            throw new IllegalArgumentException("No admitted write owner for requested dataset");
        var frozenSnapshot = request.logicalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        for (var member : request.members()) for (var row : member.rows())
            if (!frozenSnapshot.equals(row.get("snapshot_ts", Instant.class)))
                throw new IllegalArgumentException("Stock-basic snapshot differs from frozen logical date");
        String targetId = target.targetId();
        var plan = WriteGroupPlan.prepare(request, datasets, Map.of(StockBasicDataset.DEFINITION.datasetId(), targetId));
        String run = "write-group-" + UUID.randomUUID();
        Path evidence = ledger.getParent().resolve("write-evidence");
        var port = new StockBasicWritePort(StockBasicDataset.DEFINITION.objectName(), jdbc, questdb);
        var member = plan.members().getFirst();
        var adapter = new PreparedWriteAdapter<>(plan, member.memberId(), StockBasicWriteGroupService::decode,
                StockBasicWriteGroupService::encode, StockBasicWritePort.CODEC, port, () -> {
                    try { return target.targetId(); }
                    catch (Exception failure) { throw new IllegalStateException("Cannot resolve current write target", failure); }
                }, evidence.resolve(run));
        return new PersistentWriteGroupRunner(ledger, evidence, datasets)
                .run(run, plan, Map.of(member.memberId(), adapter), priorRun);
    }
    static StockBasicSnapshot decode(DatasetValues row) {
        return new StockBasicSnapshot(row.get("snapshot_ts", Instant.class), new StockBasic(row.get("ts_code", String.class),
                row.get("symbol", String.class), row.get("name", String.class), row.get("area", String.class),
                row.get("industry", String.class), row.get("list_date", LocalDate.class)));
    }
    static DatasetValues encode(StockBasicSnapshot row) {
        var values = new LinkedHashMap<String,Object>(); var stock = row.stock();
        values.put("snapshot_ts", row.snapshotTimestamp()); values.put("ts_code", stock.tsCode());
        values.put("symbol", stock.symbol()); values.put("name", stock.name()); values.put("area", stock.area());
        values.put("industry", stock.industry()); values.put("list_date", stock.listDate());
        return new DatasetValues(values);
    }
}
