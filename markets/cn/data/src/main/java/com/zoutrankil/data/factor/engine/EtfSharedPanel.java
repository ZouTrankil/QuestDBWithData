package com.zoutrankil.data.factor.engine;

import java.time.LocalDate;
import java.util.*;

/** One owned market panel shared by temporal features and date-local cross-sections. */
public final class EtfSharedPanel {
    private EtfSharedPanel() {}
    public record Key(String symbol,LocalDate date) {}
    /** Positions address the shared arrays; pool selection never recalculates temporal features. */
    public record Result(List<EtfMarketPanel.Output> securities,EtfDateIndex index) {
        public List<EtfDateIndex.Position> select(LocalDate date,Set<String> symbols) {
            return index.dates().getOrDefault(date,List.of()).stream()
                    .filter(p->symbols.contains(securities.get(p.security()).symbol())).toList();
        }
    }
    /** Share keys are canonical source rows, before a left join onto observed market dates. */
    public static Result compute(List<EtfMarketPanel.Input> inputs,Map<String,String> categories,
            Map<Key,Double> reportedShares,Map<Key,Double> componentBreadth) {
        var market=EtfMarketPanel.compute(inputs,categories);
        var panel=new ArrayList<EtfMarketPanel.Output>();
        for(int s=0;s<market.size();s++) {
            var security=market.get(s);int n=security.dates().length;
            var columns=new LinkedHashMap<>(security.columns());
            if(!reportedShares.isEmpty()) {
                double[] shares=new double[n];
                for(int row=0;row<n;row++)shares[row]=reportedShares.getOrDefault(
                        new Key(security.symbol(),security.dates()[row]),Double.NaN);
                columns.putAll(EtfShareFeatures.compute(inputs.get(s).close(),shares));
            }
            double[] breadth=new double[n],recovery=new double[n];Arrays.fill(recovery,Double.NaN);
            for(int row=0;row<n;row++)breadth[row]=componentBreadth.getOrDefault(
                    new Key(security.symbol(),security.dates()[row]),Double.NaN);
            columns.put("component_up_breadth_1d",breadth);columns.put("recovery_quality_5d",recovery);
            panel.add(new EtfMarketPanel.Output(security.symbol(),security.dates(),Collections.unmodifiableMap(columns)));
        }
        var index=new EtfDateIndex(panel);
        var names=List.of("ret5","amount_ratio_5d_20d","vol5","vol20","recent_drawdown20","component_up_breadth_1d");
        for(var positions:index.dates().values()) {
            double[][] values=new double[names.size()][positions.size()];
            for(int i=0;i<positions.size();i++) {
                var position=positions.get(i);var columns=panel.get(position.security()).columns();
                for(int c=0;c<names.size();c++)values[c][i]=columns.get(names.get(c))[position.row()];
            }
            var scores=EtfRecoveryQuality.compute(values[0],values[1],values[2],values[3],values[4],values[5]);
            for(int i=0;i<positions.size();i++) {
                var position=positions.get(i);
                panel.get(position.security()).columns().get("recovery_quality_5d")[position.row()]=scores[i];
            }
        }
        return new Result(List.copyOf(panel),index);
    }
}
