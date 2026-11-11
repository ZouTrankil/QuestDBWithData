package com.zoutrankil.batch.l2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.batch.l2.archive.DfcfCsvParser;
import com.zoutrankil.batch.l2.archive.NewSourceCsvParser;
import com.zoutrankil.batch.l2.L2BatchState.Job;
import com.zoutrankil.batch.l2.L2BatchState.SourceFingerprint;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** 新来源适配器；交易所映射来自显式候选清单。 */
final class L2NewSourceFileSource implements L2DailyFeatureSource {
 private final Path candidates;
 private final String candidateHash;
 private final Map<String,String> symbols;
 private final String date;
 L2NewSourceFileSource(Path mapping) throws Exception {
  candidates=mapping.toRealPath(); candidateHash=L2FeatureFiles.sha256(candidates);
  JsonNode root=new ObjectMapper().readTree(Files.readAllBytes(candidates)); date=root.path("date").asText();
  if(!date.matches("\\d{8}")||!root.path("candidates").isObject())throw new IOException("候选清单缺少日期或映射");
  var result=new TreeMap<String,String>();
  var fields=root.path("candidates").fields();
  while(fields.hasNext()){var e=fields.next();var k=e.getKey();var values=e.getValue();
   if(!k.matches("\\d{6}")||!values.isArray()||values.size()!=1)throw new IOException("交易所映射不唯一: "+k);
   var v=values.get(0).asText();if(!v.equals(k+".SH")&&!v.equals(k+".SZ"))throw new IOException("交易所映射无效: "+k);result.put(k,v);
  }
  if(result.isEmpty())throw new IOException("交易所映射为空"); symbols=Map.copyOf(result); checkMapping();
 }
 private void checkMapping() throws IOException {if(!candidateHash.equals(L2FeatureFiles.sha256(candidates)))throw new IOException("交易所候选清单发生变化");}
 @Override public String parserVersion(){return "new_source_csv_cn_en_side0_buy_sh_processed_v1";}
 @Override public List<Job> discover(Path source,List<String> ignored) throws IOException {
  checkMapping(); if(!source.getFileName().toString().equals(date))throw new IOException("候选映射日期不匹配");
  var found=new TreeSet<String>();try(var entries=Files.list(source)){for(var p:entries.toList()){String name=p.getFileName().toString();if(!name.matches("\\d{6}")||!Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)){ignored.add(name);continue;}found.add(name);}}
  if(!found.equals(new TreeSet<>(symbols.keySet())))throw new IOException("来源目录与映射不一致");
  return found.stream().map(code->new Job(symbols.get(code),List.of(source.resolve(code)))).toList();
 }
 @Override public SourceFingerprint fingerprint(Path source,long maxBytes) throws IOException {
  try {checkMapping(); for(var n:List.of("行情快照.csv","逐笔成交.csv","逐笔委托.csv","逐笔委托原始.csv"))if(Files.size(source.resolve(n))>maxBytes)throw new IOException("来源文件超出限制");
   var hashes=NewSourceCsvParser.fingerprint(source);MessageDigest d=L2FeatureFiles.digest();var files=new LinkedHashMap<String,Object>();
   L2FeatureFiles.update(d,"exchangeMappingSha256\n"+candidateHash+"\n");files.put("exchangeMappingSha256",candidateHash);
   for(var e:hashes.entrySet()){long bytes=Files.size(source.resolve(e.getKey()));L2FeatureFiles.update(d,e.getKey()+"\n"+bytes+"\n"+e.getValue()+"\n");files.put(e.getKey(),Map.of("present",true,"bytes",bytes,"sha256",e.getValue()));}
   checkMapping();return new SourceFingerprint(L2FeatureFiles.hex(d),Map.copyOf(files));
  }catch(IOException e){throw e;}catch(Exception e){throw new IOException("来源指纹失败",e);}
 }
 @Override public DfcfCsvParser.ProductionDay parse(Path source,String symbol,LocalDate day,long maxBytes) throws IOException {
  try {checkMapping();String code=source.getFileName().toString();if(!Objects.equals(symbols.get(code),symbol)||!day.format(DateTimeFormatter.BASIC_ISO_DATE).equals(date))throw new IOException("日期或证券身份不一致");
   for(var n:List.of("行情快照.csv","逐笔成交.csv","逐笔委托.csv","逐笔委托原始.csv"))if(Files.size(source.resolve(n))>maxBytes)throw new IOException("来源文件超出限制");
   var result=NewSourceCsvParser.load(source.getParent(),code,symbol.substring(7),date);checkMapping();return result;
  }catch(IOException e){throw e;}catch(Exception e){throw new IOException("新来源解析失败",e);}
 }
}
