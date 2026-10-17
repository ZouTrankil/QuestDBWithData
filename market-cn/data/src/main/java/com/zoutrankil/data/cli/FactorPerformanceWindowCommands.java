package com.zoutrankil.data.cli;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.table.FactorDailyMetricRow;
import com.zoutrankil.data.factor.application.FactorDefinitionCatalog;
import com.zoutrankil.data.factor.engine.FactorPerformanceWindows;
import com.zoutrankil.data.repository.FileEvidenceStore;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;

/** Offline projection of frozen daily metrics; never publishes a page or certifies source completeness. */
@Component
@ConditionalOnNotWebApplication
public final class FactorPerformanceWindowCommands implements CliCommandFamily {
    @Override public Set<String> commands() {return Set.of("compute-factor-performance-windows");}
    @Override public void execute(String command,Map<String,String> options)throws Exception {
        if(!commands().contains(command)||!options.keySet().equals(Set.of("--input","--as-of","--output")))
            throw new IllegalArgumentException("Expected --input JSON_WITH_ROWS --as-of DATE --output NEW_PATH");
        var bytes=FileEvidenceStore.readBounded(Path.of(options.get("--input")),64*1024*1024,
                ()->new IllegalArgumentException("Daily metric input exceeds limit"));
        var json=JobDefinitionJson.mapper();var input=json.readTree(bytes);
        if(!input.path("rows").isArray())throw new IllegalArgumentException("Daily metric rows array required");
        var rows=new ArrayList<FactorDailyMetricRow>();
        for(var row:input.path("rows"))rows.add(json.treeToValue(row,FactorDailyMetricRow.class));
        var catalog=FactorDefinitionCatalog.load();var directions=new HashMap<FactorPerformanceWindows.Definition,String>();
        for(var definition:catalog.definitions())directions.put(new FactorPerformanceWindows.Definition(
                definition.factorId(),definition.version()),definition.direction());
        var windows=FactorPerformanceWindows.compute(rows,LocalDate.parse(options.get("--as-of")),directions);
        var output=Map.of("schema","java.factor_performance_windows.v1","inputSha256",FileEvidenceStore.sha256(bytes),
                "catalogSha256",catalog.sourceConfigSha256(),"windows",windows,"sourceComplete",false,"pageSnapshotRefreshed",false);
        Path path=Path.of(options.get("--output")).toAbsolutePath().normalize();
        FileEvidenceStore.writeNewDurable(path,json.writeValueAsBytes(output));
        CliOutput.printJson(Map.of("output",path.toString(),"inputRows",rows.size(),"windowRows",windows.size()),CliOutput.Profile.PLAIN,false);
    }
}
