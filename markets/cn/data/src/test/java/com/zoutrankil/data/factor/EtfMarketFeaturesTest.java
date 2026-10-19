package com.zoutrankil.data.factor;

import com.zoutrankil.data.factor.engine.EtfMarketFeatures;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EtfMarketFeaturesTest {
    @Test void constantSeriesHonorsWarmupAndDoesNotMutateInputs() {
        double[] close=new double[40],amount=new double[40];Arrays.fill(close,10);Arrays.fill(amount,100);
        var values=EtfMarketFeatures.compute(close,amount);
        assertTrue(Double.isNaN(values.get("ret20")[19]));assertEquals(0,values.get("ret20")[20]);
        assertTrue(Double.isNaN(values.get("amount20")[8]));assertEquals(100,values.get("amount20")[9]);
        assertEquals(1,values.get("amount_ratio_5d_20d")[20]);assertEquals(0,values.get("vol20")[20]);
        assertTrue(Double.isNaN(values.get("recent_drawdown20")[12]));assertEquals(0,values.get("recent_drawdown20")[13]);
        assertEquals(10,close[39]);assertEquals(100,amount[39]);
    }
    @Test void missingPricesAreNotForwardFilledAndSmallPricesRemainValid() {
        var values=EtfMarketFeatures.compute(new double[]{1e-15,2e-15,Double.NaN,4e-15,8e-15},new double[5]);
        assertEquals(1,values.get("ret1")[1]);assertTrue(Double.isNaN(values.get("ret1")[2]));
        assertTrue(Double.isNaN(values.get("ret1")[3]));assertEquals(1,values.get("ret1")[4]);
    }
}
