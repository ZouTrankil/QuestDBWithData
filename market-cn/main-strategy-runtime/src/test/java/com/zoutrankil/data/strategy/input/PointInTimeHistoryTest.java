package com.zoutrankil.data.strategy.input;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PointInTimeHistoryTest {
    @TempDir Path root;
    static final LocalDate DAY=LocalDate.parse("2026-10-16");
    List<Map<String,Object>> calendar(){return List.of(Map.of("cal_date",DAY,"is_open",1),Map.of("cal_date",DAY.plusDays(3),"is_open",1));}
    List<Map<String,Object>> l2(){return List.of(Map.of("ts",DAY,"symbol","600000.SH"));}
    Map<String,Object> metadata(String name,String industry,String available){return Map.of("ts_code","600000.SH","name",name,"industry",industry,"effectiveFrom","2020-01-01","availableAt",available);}
    PointInTimeHistory history(List<Map<String,Object>> metadata,List<Map<String,Object>> context)throws Exception {
        Path evidence=Files.writeString(root.resolve("original.txt"),"测试历史原件；不代表真实来源已验收");
        byte[] bytes=new ObjectMapper().writeValueAsBytes(Map.of("schema","java.strategy.point_in_time.v1","decisionTimes",Map.of(DAY.toString(),"2026-10-16T08:00:00Z"),
                "stockMetadata",metadata,"marketContext",context,"sourceEvidence",List.of(Map.of("path",evidence.toString(),"sha256",StrategySourceCoverage.sha(Files.readAllBytes(evidence))))));
        Path file=Files.write(root.resolve("history.json"),bytes);return new PointInTimeHistory(file,StrategySourceCoverage.sha(bytes));
    }
    @Test void laterNameStAndIndustryCorrectionsCannotAffectEarlierDecision()throws Exception {
        var h=history(List.of(metadata("旧名","旧行业","2025-01-01T00:00:00Z"),metadata("ST新名","新行业","2026-10-19T01:31:00Z")),List.of());
        var row=h.metadata(l2(),calendar()).getFirst();assertEquals("旧名",row.get("name"));assertEquals("旧行业",row.get("industry"));assertEquals(DAY,row.get("trade_date"));
    }
    @Test void onlyLaterKnowledgeOrAmbiguousSameTimeFails()throws Exception {
        var late=history(List.of(metadata("ST新名","新行业","2026-10-19T01:31:00Z")),List.of());assertThrows(IllegalStateException.class,()->late.metadata(l2(),calendar()));
        var conflict=history(List.of(metadata("甲","银行","2025-01-01T00:00:00Z"),metadata("乙","证券","2025-01-01T00:00:00Z")),List.of());assertThrows(IllegalStateException.class,()->conflict.metadata(l2(),calendar()));
    }
    @Test void regimeUsesRevisionAvailableAtDecisionAndOriginalsRemainPinned()throws Exception {
        var h=history(List.of(),List.of(Map.of("trade_date",DAY.toString(),"availableAt","2026-10-16T07:10:00Z","values",Map.of("erp_latest",1)),
                Map.of("trade_date",DAY.toString(),"availableAt","2026-10-18T10:00:00Z","values",Map.of("erp_latest",9))));
        var rows=h.context(List.of(Map.of("trade_date",DAY,"erp_latest",9)),calendar());assertEquals(1,rows.getFirst().get("erp_latest"));
        Files.writeString(root.resolve("original.txt"),"被替换");assertThrows(IllegalStateException.class,h::revalidate);
    }
    @Test void coverageAssemblyRejectsUnapprovedOrIncompleteInputsBeforeAnyPublish()throws Exception {
        Path index=Files.writeString(root.resolve("index.json"),"{\"schema\":\"java.strategy.coverage_inputs.v1\"}");Path out=root.resolve("result.json");
        assertThrows(IllegalStateException.class,()->StrategyCoverageAssembler.assemble(index,"0".repeat(64),out,Map.of()));assertFalse(Files.exists(out));
        String sha=StrategySourceCoverage.sha(Files.readAllBytes(index));assertThrows(IllegalStateException.class,()->StrategyCoverageAssembler.assemble(index,sha,out,Map.of()));assertFalse(Files.exists(out));
    }
}
