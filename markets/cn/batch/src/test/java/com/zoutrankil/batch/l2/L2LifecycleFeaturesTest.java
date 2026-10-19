package com.zoutrankil.batch.l2;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class L2LifecycleFeaturesTest {
    @Test void cancellationDealsNeverBecomeFillsAndPartialOrderSummariesMatchThePythonContract() {
        var orders=List.of(
                new L2FeatureData.Order(100,10_000,100,1,"11",false),
                new L2FeatureData.Order(200,5_000,200,11,"21",false),
                new L2FeatureData.Order(1_100,10_000,60,-1,"11",false),
                new L2FeatureData.Order(1_200,10,100,1,"31",false),
                new L2FeatureData.Order(1_400,10,100,-1,"31",false));
        var deals=List.of(new L2FeatureData.Deal(1_000,10_000,40,0,"1","11","21"),
                new L2FeatureData.Deal(1_100,0,60,-1,"2","11","0"));
        var data=new L2FeatureData("000001.SZ",LocalDate.of(2026,9,24),deals,orders,List.of());
        var metrics=L2LifecycleFeatures.compute(data);
        assertEquals(1.0/3,(double)metrics.get("cancel_without_fill_ratio"));
        assertEquals(1.0/3,(double)metrics.get("partial_fill_ratio"));
        assertEquals(1.0/3,(double)metrics.get("partial_cancel_ratio"));
        assertEquals(600.0,(double)metrics.get("median_cancel_time_ms"));
        assertEquals(850.0,(double)metrics.get("median_first_fill_time_ms"));
        assertEquals(0.5,(double)metrics.get("passive_fill_ratio"));
        assertEquals(80.0/300,(double)metrics.get("large_order_fill_rate"));
        assertEquals(0.0,(double)metrics.get("replace_like_ratio"));
    }

    @Test void anAllCancellationDealFeedProducesFiniteZeroFillFeatures() {
        var orders=List.of(new L2FeatureData.Order(100,10,100,1,"11",false),
                new L2FeatureData.Order(200,10,100,-1,"11",false));
        var deals=List.of(new L2FeatureData.Deal(200,0,100,-1,"2","11","0"));
        var data=new L2FeatureData("000001.SZ",LocalDate.of(2026,9,24),deals,orders,List.of());
        var metrics=L2LifecycleFeatures.compute(data);
        assertEquals(1.0,(double)metrics.get("cancel_without_fill_ratio"));
        assertEquals(100.0,(double)metrics.get("median_cancel_time_ms"));
        assertEquals(0.0,(double)metrics.get("median_first_fill_time_ms"));
        assertEquals(0.0,(double)metrics.get("passive_fill_ratio"));
        assertEquals(0.0,(double)metrics.get("large_order_fill_rate"));
        assertEquals(0.0,(double)metrics.get("partial_fill_ratio"));
    }
}
