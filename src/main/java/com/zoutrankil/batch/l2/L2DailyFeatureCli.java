package com.zoutrankil.batch.l2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.batch.DfcfCsvParser;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

/** Bounded native daily feature generation from extracted DFCF archive members. */
public final class L2DailyFeatureCli {
    private L2DailyFeatureCli(){}
    public static void main(String[] args) throws Exception {
        var options=new HashMap<String,String>();
        for(int i=0;i<args.length;i+=2){if(i+1>=args.length||!args[i].startsWith("--"))throw new IllegalArgumentException("Expected --option value pairs");if(options.put(args[i],args[i+1])!=null)throw new IllegalArgumentException("Duplicate option: "+args[i]);}
        if(!Set.of("--source-root","--date","--symbols","--output","--max-bytes-per-file").containsAll(options.keySet()))throw new IllegalArgumentException("Unknown compute option");
        Path root=Path.of(required(options,"--source-root")).toAbsolutePath().normalize();
        String dateText=required(options,"--date");LocalDate date=LocalDate.parse(dateText.length()==8?dateText.substring(0,4)+"-"+dateText.substring(4,6)+"-"+dateText.substring(6,8):dateText);
        List<String> symbols=Arrays.stream(required(options,"--symbols").split(",",-1)).map(String::strip).toList();
        if(symbols.isEmpty()||symbols.size()>1000||symbols.stream().anyMatch(String::isBlank)||new HashSet<>(symbols).size()!=symbols.size())throw new IllegalArgumentException("Explicit unique bounded symbol list required");
        var canonicalSymbols=new HashSet<String>();
        for(String symbol:symbols){
            if(!symbol.matches("(?i)\\d{6}\\.(SH|SZ|BJ)"))throw new IllegalArgumentException("Invalid source symbol: "+symbol);
            if(!canonicalSymbols.add(DfcfCsvParser.normalizeSymbol(symbol)))throw new IllegalArgumentException("Duplicate canonical source symbol: "+symbol);
        }
        long maxBytes=Long.parseLong(options.getOrDefault("--max-bytes-per-file","536870912"));
        if(maxBytes<1)throw new IllegalArgumentException("Positive source byte bound required");
        String day=date.toString().replace("-","");Path dateDirectory=day.equals(String.valueOf(root.getFileName()))?root:root.resolve(day);
        Path output=Path.of(required(options,"--output")).toAbsolutePath().normalize();
        if(output.getParent()!=null)Files.createDirectories(output.getParent());
        Path temp=output.resolveSibling(output.getFileName()+".tmp-"+UUID.randomUUID());
        var json=new ObjectMapper();int complete=0;
        try{
            try(BufferedWriter writer=Files.newBufferedWriter(temp,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW)){
                for(String symbol:symbols){
                    Path source=dateDirectory.resolve(symbol.toUpperCase(Locale.ROOT));
                    if(!Files.isDirectory(source))throw new IllegalArgumentException("Missing extracted source directory: "+source);
                    long started=System.nanoTime();
                    var parsed=DfcfCsvParser.readProductionDay(source,symbol,date,maxBytes);
                    if(parsed.rawDealRows()==0&&parsed.rawOrderRows()==0&&parsed.rawQuoteRows()==0)throw new IllegalArgumentException("Empty source directory: "+source);
                    var row=L2DailyFeaturePipeline.compute(parsed);
                    writer.write(json.writeValueAsString(L2DailyFeaturePipeline.output(row)));writer.newLine();complete++;
                    System.out.printf(Locale.ROOT,"%s date=%s normalized=%s deals=%d orders=%d quotes=%d fields=%d elapsedSeconds=%.3f%n",symbol,day,row.symbol(),parsed.deals().size(),parsed.orders().size(),parsed.quotes().size(),row.features().size()+2,(System.nanoTime()-started)/1e9);
                }
            }
            Files.move(temp,output,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(temp);}
        System.out.println("generated_rows="+complete+" output="+output);
    }
    private static String required(Map<String,String> options,String key){String value=options.get(key);if(value==null||value.isBlank())throw new IllegalArgumentException("Required option: "+key);return value;}
}
