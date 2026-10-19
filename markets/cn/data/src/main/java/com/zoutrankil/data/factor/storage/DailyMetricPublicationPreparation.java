package com.zoutrankil.data.factor.storage;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.table.FactorDailyMetricRow;
import com.zoutrankil.data.factor.application.*;
import com.zoutrankil.data.repository.FileEvidenceStore;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reconcile frozen calculation inputs with local formal observations before admitting publication. */
public final class DailyMetricPublicationPreparation {
    private DailyMetricPublicationPreparation() {}
    public record Prepared(Path evidence,DailyMetricInputGuard guard,int rows) {}

    public static Prepared prepare(JdbcTemplate jdbc,Path computation,Path outputDirectory)throws Exception {
        computation=computation.toAbsolutePath().normalize();
        byte[] bytes=FileEvidenceStore.readBounded(computation,32*1024*1024,
                ()->new IllegalArgumentException("Calculation evidence exceeds budget"));
        var json=JobDefinitionJson.mapper();var root=json.readTree(bytes);
        if(!"java.factor_daily_metric_computation.v1".equals(root.path("schema").asText())
                || !root.path("sourceComplete").isBoolean() || root.path("sourceComplete").booleanValue()
                || !root.path("missingKeys").isArray() || root.path("missingKeys").size()>10000
                || !root.path("rows").isArray() || root.path("rows").isEmpty() || root.path("rows").size()>10000
                || !DailyMetricInputGuard.code().equals(root.path("calculationCodeSha256").asText()))
            throw new IllegalArgumentException("Current bounded calculation with explicit missing keys required");
        var catalog=FactorDefinitionCatalog.load();
        if(!catalog.sourceConfigSha256().equals(root.path("catalogSha256").asText()))
            throw new IllegalStateException("Factor catalog changed");
        LocalDate signal=LocalDate.parse(root.path("signalDate").asText());
        var rows=new ArrayList<FactorDailyMetricRow>();
        for(var node:root.path("rows"))rows.add(json.treeToValue(node,FactorDailyMetricRow.class));
        Path pools=Path.of(root.path("poolEvidencePath").asText()).toAbsolutePath().normalize();
        var plan=DailyMetricPoolRequests.read(pools,signal,rows.getFirst().labelDefinition(),catalog);
        if(!plan.poolEvidenceSha256().equals(root.path("poolEvidenceSha256").asText()))
            throw new IllegalStateException("Pool evidence changed");
        var missing=new HashSet<>(plan.expected());
        for(var row:rows)missing.remove(new DailyMetricPublication.Key(row.universeId(),row.factorId()));
        var declaredMissing=new HashSet<DailyMetricPublication.Key>();
        for(var node:root.path("missingKeys"))
            if(!declaredMissing.add(json.treeToValue(node,DailyMetricPublication.Key.class)))
                throw new IllegalArgumentException("Duplicate missing metric key");
        var declaredExpected=new HashSet<DailyMetricPublication.Key>();
        if(!root.path("expected").isArray())throw new IllegalArgumentException("Expected metric matrix required");
        for(var node:root.path("expected"))
            if(!declaredExpected.add(json.treeToValue(node,DailyMetricPublication.Key.class)))
                throw new IllegalArgumentException("Duplicate expected metric key");
        if(!missing.equals(declaredMissing) || !declaredExpected.equals(plan.expected()))
            throw new IllegalStateException("Declared metric matrix or missing keys differ from pool plan");
        DailyMetricPublication.inspect(signal,rows,plan.expected(),true,true);
        for(var row:rows) {
            var identity=plan.availableRequests().stream().map(DailyFactorEvaluation.Request::identity)
                    .filter(i->i.universeId().equals(row.universeId()) && i.factorId().equals(row.factorId()))
                    .findFirst().orElseThrow();
            if(!identity.universeVersion().equals(row.universeVersion())
                    || !identity.definitionVersion().equals(row.definitionVersion())
                    || !identity.labelDefinition().equals(row.labelDefinition())
                    || !identity.membershipAsOf().atStartOfDay().toInstant(java.time.ZoneOffset.UTC).equals(row.membershipAsOf()))
                throw new IllegalStateException("Metric identity differs from declared pool");
        }
        var versions=new ArrayList<DailyMetricInputGuard.Version>();
        for(String group:List.of("priceSourceState","exposureSourceState"))for(var v:root.path(group))
            versions.add(new DailyMetricInputGuard.Version(v.path("table_name").asText(),v.path("id").asLong(),
                    v.path("directoryName").asText(),v.path("table_txn").asLong(),v.path("wal_txn").asLong(),v.path("table_suspended").asBoolean()));
        var state=jdbc.queryForMap("SELECT id,directoryName,table_txn,wal_txn,table_suspended FROM tables() WHERE table_name='factor_observation'");
        versions.add(new DailyMetricInputGuard.Version("factor_observation",((Number)state.get("id")).longValue(),
                (String)state.get("directoryName"),((Number)state.get("table_txn")).longValue(),
                ((Number)state.get("wal_txn")).longValue(),Boolean.TRUE.equals(state.get("table_suspended"))));
        var files=new ArrayList<>(List.of(computation,pools));
        var observations=root.path("observationEvidence");
        if(!observations.isObject() || observations.isEmpty() || observations.size()>32)
            throw new IllegalArgumentException("Bounded observation evidence required");
        var reader=new QuestDbGlobalObservations(jdbc);
        var consumedFactors=new HashSet<String>();
        for(var request:plan.availableRequests())consumedFactors.add(request.identity().factorId());
        for(var entry:observations.properties()) {
            Path path=Path.of(entry.getKey());var frozen=FactorObservationEvidence.read(path);files.add(path);
            if(!frozen.fileSha256().equals(entry.getValue().asText()))throw new IllegalStateException("Observation evidence changed");
            // Compare exact consumed keys, preserving unrelated or newer target sections.
            var consumed=frozen.rows().stream().filter(row->row.signalDate().equals(signal)
                    && row.entityType().equals("stock") && consumedFactors.contains(row.factorId())).toList();
            var sections=new LinkedHashMap<GlobalObservationIncrementalRead.Section,List<com.zoutrankil.data.factor.domain.GlobalFactorObservation>>();
            for(var row:consumed)sections.computeIfAbsent(new GlobalObservationIncrementalRead.Section(
                    row.signalDate(),row.entityType(),row.factorId(),row.definitionVersion()),ignored->new ArrayList<>()).add(row);
            for(var section:sections.entrySet()) {
                var part=section.getValue();var keys=part.stream().map(GlobalObservationCoverage::key).toList();
                var selectedKeys=new HashSet<>(keys);
                if(selectedKeys.size()!=keys.size())throw new IllegalStateException("Duplicate consumed observation key");
                var id=section.getKey();
                var physical=part.size()<250?reader.readKeys(keys):reader.read(id.date(),id.entityType(),id.factorId(),id.version(),100_000)
                        .stream().filter(row->selectedKeys.contains(GlobalObservationCoverage.key(row))).toList();
                var delta=GlobalObservationCoverage.compare(part,physical);
                if(delta.matching()!=part.size() || !delta.writes().isEmpty() || delta.unexpected()!=0)
                    throw new IllegalStateException("Formal observations differ from calculation inputs");
            }
        }
        if(!Arrays.equals(bytes,FileEvidenceStore.readBounded(computation,32*1024*1024,
                ()->new IllegalArgumentException("Calculation evidence exceeds budget"))))
            throw new IllegalStateException("Calculation changed during preparation");
        var guard=new DailyMetricInputGuard(jdbc,versions,files,pools);
        Path evidence=outputDirectory.toAbsolutePath().normalize().resolve("daily-metric-"+guard.fingerprint()+".json");
        if(!Files.exists(evidence))DailyMetricEvidence.writeNew(evidence,signal,guard.fingerprint(),false,rows,plan.expected());
        var frozen=DailyMetricEvidence.read(evidence);
        if(!frozen.rows().equals(rows) || !frozen.expected().equals(plan.expected()) || frozen.sourceComplete()
                || !frozen.inputFingerprint().equals(guard.fingerprint()))throw new IllegalStateException("Prepared evidence differs");
        return new Prepared(evidence,guard,rows.size());
    }
}
