package com.zoutrankil.data.cli;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.factor.application.FactorDefinitionCatalog;
import com.zoutrankil.data.factor.engine.DailyFactorFeatures;
import com.zoutrankil.data.repository.FileEvidenceStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.stereotype.Component;

/** Finite native-Java computation entry. Feature artifacts are not formal observations. */
@Component
@ConditionalOnNotWebApplication
public final class FactorCommands implements CliCommandFamily {
    private final com.zoutrankil.data.factor.storage.QuestDbFactorInputs inputs;
    private final com.zoutrankil.data.factor.storage.QuestDbGlobalObservations observations;
    public FactorCommands(com.zoutrankil.data.factor.storage.QuestDbFactorInputs inputs,
            com.zoutrankil.data.factor.storage.QuestDbGlobalObservations observations) {
        this.inputs=inputs; this.observations=observations;
    }
    @Override public Set<String> commands() {
        return Set.of("inspect-factor-observations","show-factor-definitions","compute-daily-factor-features","compute-questdb-factor-features");
    }

    @Override public void execute(String command,Map<String,String> options) throws Exception {
        if(command.equals("inspect-factor-observations")) {
            if(!options.keySet().equals(Set.of("--date","--entity-type","--factor","--version")))
                throw new IllegalArgumentException("Expected --date DATE --entity-type TYPE --factor ID --version VERSION");
            var rows=observations.read(LocalDate.parse(options.get("--date")),options.get("--entity-type"),
                    options.get("--factor"),options.get("--version"),100_000);
            // Also checks uniqueness of the complete business key.
            var coverage=com.zoutrankil.data.factor.application.GlobalObservationCoverage.compare(rows,rows);
            var encoded=rows.stream().map(row -> java.util.Arrays.asList(
                    row.signalDate().toString(),row.entityType(),row.entityId(),row.factorId(),
                    row.definitionVersion(),row.factorValue(),row.availableAt().toString(),
                    row.qualityStatus(),row.scope(),row.l2SourceLineageDigest())).toList();
            byte[] bytes=JobDefinitionJson.mapper().writeValueAsBytes(encoded);
            CliOutput.printJson(Map.of("rows",rows.size(),"matchingKeys",coverage.matching(),
                    "sha256",FileEvidenceStore.sha256(bytes),"readOnly",true),CliOutput.Profile.JOB_DEFINITION,true);
            return;
        }
        if(command.equals("show-factor-definitions")) {
            if(!options.isEmpty()) throw new IllegalArgumentException("No options expected");
            var catalog=FactorDefinitionCatalog.load();
            CliOutput.printJson(Map.of("sourceConfigSha256",catalog.sourceConfigSha256(),
                    "definitions",catalog.definitions(),"publicationReady",false),CliOutput.Profile.JOB_DEFINITION,true);
            return;
        }
        if(command.equals("compute-questdb-factor-features")) {
            if(!options.keySet().equals(Set.of("--symbol","--from","--to","--output")))
                throw new IllegalArgumentException("Expected --symbol CODE --from DATE --to DATE --output PATH");
            var snapshot=inputs.read(options.get("--symbol"),LocalDate.parse(options.get("--from")),LocalDate.parse(options.get("--to")));
            byte[] bytes=JobDefinitionJson.mapper().writeValueAsBytes(snapshot);
            writeFeatures(bytes,Path.of(options.get("--output")).toAbsolutePath());
            return;
        }
        if(!command.equals("compute-daily-factor-features") || !options.keySet().equals(Set.of("--input","--output")))
            throw new IllegalArgumentException("Expected --input PATH --output PATH");
        Path input=Path.of(options.get("--input")), output=Path.of(options.get("--output")).toAbsolutePath();
        byte[] bytes=FileEvidenceStore.readBounded(input,16*1024*1024,
                () -> new IllegalArgumentException("Input exceeds 16 MiB"));
        writeFeatures(bytes,output);
    }

    private static void writeFeatures(byte[] bytes,Path output) throws Exception {
        var mapper=JobDefinitionJson.mapper();
        var root=mapper.readTree(bytes);
        String symbol=root.path("symbol").asText();
        if(!symbol.matches("[0-9]{6}\\.(SH|SZ|BJ)")) throw new IllegalArgumentException("One mainland security required");
        var dateNodes=root.path("dates");
        if(!dateNodes.isArray() || dateNodes.isEmpty() || dateNodes.size()>5000)
            throw new IllegalArgumentException("Expected 1..5000 daily observations");
        LocalDate[] dates=new LocalDate[dateNodes.size()];
        for(int i=0;i<dates.length;i++) dates[i]=LocalDate.parse(dateNodes.get(i).asText());
        var columnNodes=root.path("columns");
        if(!columnNodes.isObject()) throw new IllegalArgumentException("Input columns required");
        var columns=new LinkedHashMap<String,double[]>();
        var names=columnNodes.fieldNames();
        while(names.hasNext()) {
            String name=names.next();var column=columnNodes.get(name);
            if(!column.isArray() || column.size()!=dates.length) throw new IllegalArgumentException("Misaligned input: " + name);
            double[] values=new double[dates.length];
            for(int i=0;i<values.length;i++) {
                var value=column.get(i);
                if(!value.isNull() && !value.isNumber()) throw new IllegalArgumentException("Numeric or null input required");
                values[i]=value.isNull()?Double.NaN:value.doubleValue();
            }
            columns.put(name,values);
        }
        long started=System.nanoTime();
        var features=DailyFactorFeatures.compute(dates,columns);
        long computeNanos=System.nanoTime()-started;
        var encoded=new LinkedHashMap<String,Object>();
        for(var entry:features.entrySet()) {
            var values=new ArrayList<Double>(dates.length);
            for(double value:entry.getValue()) values.add(Double.isFinite(value)?value:null);
            encoded.put(entry.getKey(),values);
        }
        var artifact=new LinkedHashMap<String,Object>();
        artifact.put("producer","java.daily_factor_features.v1");
        artifact.put("symbol",symbol);artifact.put("dates",dates);artifact.put("columns",encoded);
        artifact.put("inputSha256",FileEvidenceStore.sha256(bytes));
        // Retain the complete source snapshot and its identity for parity/replay.
        artifact.put("input",root);
        artifact.put("includesWarmup",root.has("warmupFrom"));
        artifact.put("computeNanos",computeNanos);artifact.put("publicationReady",false);
        Files.createDirectories(output.getParent());
        FileEvidenceStore.writeNew(output,mapper.writeValueAsBytes(artifact));
        CliOutput.printJson(Map.of("symbol",symbol,"rows",dates.length,"featureCount",features.size(),
                "artifact",output.toString(),"publicationReady",false),CliOutput.Profile.JOB_DEFINITION,true);
    }
}
