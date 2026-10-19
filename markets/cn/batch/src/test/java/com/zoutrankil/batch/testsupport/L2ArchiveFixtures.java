package com.zoutrankil.batch.testsupport;

import java.nio.charset.Charset;
import java.util.*;

/** Bounded DFCF CSV inputs shared by archive and runtime integration tests. */
public final class L2ArchiveFixtures {
    public static final Charset ENCODING = Charset.forName("GB18030");
    private L2ArchiveFixtures() {}
    public static String csv(String date,String fileName,String version,boolean huge) {
        List<String> header=switch(fileName) {
            case "逐笔成交.csv" -> new ArrayList<>(List.of("自然日","时间","成交编号","成交价格","成交数量","BS标志","叫买序号","叫卖序号"));
            case "逐笔委托.csv" -> new ArrayList<>(List.of("自然日","时间","交易所委托号","委托类型","委托代码","委托价格","委托数量"));
            default -> {
                var names=new ArrayList<>(List.of("自然日","时间","成交价","成交量","成交额","当日累计成交量","当日成交额","叫买总量","叫卖总量","加权平均叫买价","加权平均叫卖价"));
                for(int i=1;i<=10;i++) names.add("申买价"+i);
                for(int i=1;i<=10;i++) names.add("申卖价"+i);
                for(int i=1;i<=10;i++) names.add("申买量"+i);
                for(int i=1;i<=10;i++) names.add("申卖量"+i);
                yield names;
            }
        };
        var values=new ArrayList<>(Collections.nCopies(header.size(),"0")); values.set(0,date); values.set(1,"93000000");
        if(fileName.equals("逐笔成交.csv")) { values.set(2,version); values.set(3,"100"); values.set(4,"10"); values.set(5,"B"); values.set(6,"1"); values.set(7,"2"); }
        if(fileName.equals("逐笔委托.csv")) { values.set(2,version); values.set(3,"0"); values.set(4,"B"); values.set(5,"100"); values.set(6,"10"); }
        if(huge) { header.add("备注"); values.add("x".repeat(1_500_000)); }
        return String.join(",",header)+"\n"+String.join(",",values)+"\n";
    }
}
