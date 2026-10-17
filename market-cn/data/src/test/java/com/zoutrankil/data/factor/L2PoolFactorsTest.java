package com.zoutrankil.data.factor;

import com.zoutrankil.data.factor.application.*;
import com.zoutrankil.data.factor.engine.*;
import com.zoutrankil.data.factor.storage.QuestDbStockMembership;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class L2PoolFactorsTest {
    @Test void poolUsesPreparedDeltaAndFullMarketMembershipBeforeObservedKeyIntersection() throws Exception {
        var date=LocalDate.parse("2026-09-30");
        var history=new ArrayList<DailyFactorPanel.Security>();var features=new ArrayList<DailyFactorPanel.Features>();
        for(int i=0;i<20;i++) {
            String symbol=String.format("%06d.SH",600000+i);
            var dates=new LocalDate[70];for(int j=0;j<70;j++)dates[j]=date.minusDays(69-j);
            history.add(new DailyFactorPanel.Security(symbol,dates,Map.of(),date.minusYears(2)));
            features.add(new DailyFactorPanel.Features(symbol,new LocalDate[]{date},Map.of(
                    "circ_mv",new double[]{1000-i},"total_mv",new double[]{1000-i},
                    "is_st",new double[]{0},"is_suspended",new double[]{0},"amount",new double[]{10})));
        }
        String id="l2_deal_records_delta3";
        var l2=List.of(new L2FactorPanel.Section("600000.SH",new LocalDate[]{date},Map.of(id,new double[]{11})),
                new L2FactorPanel.Section("600001.SH",new LocalDate[]{date},Map.of(id,new double[]{22})));
        var membership=new QuestDbStockMembership.Snapshot(new PointInTimeStockPools.Index(List.of()),
                new PointInTimeStockExposures.Index(List.of()),List.of(),0,0);
        var result=NativePoolFactors.computeL2(history,features,membership,List.of("all_market"),List.of(id),FactorDefinitionCatalog.load(),l2);
        assertEquals(1,result.sections().size());
        assertEquals(List.of("600000.SH","600001.SH"),result.sections().getFirst().symbols());
        assertArrayEquals(new double[]{11,22},result.sections().getFirst().values().raw());
        @SuppressWarnings("unchecked") var members=(List<PointInTimeStockPools.Member>)result.coverage().getFirst().get("members");
        assertEquals(18,members.stream().filter(PointInTimeStockPools.Member::included).count());
        var receipt=new L2SourceScope.Receipt(date,"unrecorded","",List.of());
        var decisions=List.of(L2SourceScope.assess(receipt,List.of()));
        var published=L2GlobalObservations.fromPools(result.sections(),decisions,List.of(id),FactorDefinitionCatalog.load(),date.plusDays(1).atStartOfDay());
        assertEquals(2,published.size());assertEquals(11,published.getFirst().factorValue());
        assertEquals("GLOBAL",published.getFirst().scope());assertEquals("complete",published.getFirst().qualityStatus());
        assertNull(published.getFirst().l2SourceLineageDigest());
        var blocked=List.of(new L2SourceScope.Decision(receipt,"blocked",false,List.of(),List.of()));
        assertThrows(IllegalArgumentException.class,()->L2GlobalObservations.fromPools(result.sections(),blocked,List.of(id),FactorDefinitionCatalog.load(),date.atStartOfDay()));
        assertThrows(IllegalArgumentException.class,()->L2GlobalObservations.fromPools(List.of(),decisions,List.of(id),FactorDefinitionCatalog.load(),date.atStartOfDay()));
    }
}
