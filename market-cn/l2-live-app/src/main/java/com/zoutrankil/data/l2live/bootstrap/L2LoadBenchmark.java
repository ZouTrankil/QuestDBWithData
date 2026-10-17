package com.zoutrankil.data.l2live.bootstrap;

import com.zoutrankil.data.l2live.domain.*;
import com.zoutrankil.data.l2live.storage.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;

/** 16 标的、48 topic 的合成负载；使用真实 FULL 日志和 Parquet 链路，不连接供应商。 */
public final class L2LoadBenchmark {
    private L2LoadBenchmark(){}
    public static Map<String,Object> run(RawJournal journal,int messages,int rate,int batchSize,long seconds)throws Exception {
        if(journal.status().captures()!=0)throw new IllegalArgumentException("负载测试只能使用没有采集记录的新目录");
        if(messages<48||messages>100000000||rate<1||rate>100000)throw new IllegalArgumentException("负载范围非法");
        var topics=new TreeSet<String>();for(int i=0;i<16;i++)for(String channel:List.of("market","trans","order"))topics.add(channel+"/"+(510500+i));
        var topicList=List.copyOf(topics);var produced=new AtomicInteger();var consumed=new AtomicInteger();var peak=new AtomicInteger();var finished=new AtomicBoolean();var failure=new AtomicReference<Throwable>();
        var samples=new long[10000];long started=System.nanoTime();long limit=seconds==0?Long.MAX_VALUE:Math.multiplyExact(seconds,1000000000L);
        var producer=new Thread(()->{
            try {
                for(int i=0;i<messages&&!Thread.currentThread().isInterrupted()&&System.nanoTime()-started<limit;i++) {
                    long due=started+(long)i*1000000000L/rate;
                    while(System.nanoTime()<due){LockSupport.parkNanos(Math.min(due-System.nanoTime(),10000000L));if(Thread.currentThread().isInterrupted())return;}
                    String topic=topicList.get(i%topicList.size());long before=System.nanoTime();
                    journal.append(topic,payload(topic,i+1).getBytes(StandardCharsets.UTF_8),Instant.now(),"synthetic-load",0,false,false,true);
                    samples[i%samples.length]=System.nanoTime()-before;int n=produced.incrementAndGet();peak.accumulateAndGet(Math.max(0,n-consumed.get()),Math::max);
                    if(n-consumed.get()>100000)throw new IllegalStateException("负载积压超过 100000 条，停止接收并保留日志");
                }
            }catch(Throwable e){failure.compareAndSet(null,e);}finally{finished.set(true);}
        },"l2-benchmark-producer");
        var accumulator=new L2MinuteAccumulator(Duration.ofSeconds(2),10,Clock.systemUTC());
        var processor=new L2LiveProcessor(journal,new LocalLiveSink(journal),new AliyunL2WireParser(),accumulator,batchSize,topics);
        producer.start();
        try {
            while(!finished.get()||consumed.get()<produced.get()) {
                int n=processor.processOnce();if(n==0)LockSupport.parkNanos(1000000L);else consumed.addAndGet(n);
                if(failure.get()!=null&&finished.get())break;
            }
        }catch(Throwable e){failure.compareAndSet(null,e);producer.interrupt();}
        finally{producer.join(30000);if(producer.isAlive()){producer.interrupt();throw new IllegalStateException("负载接收线程未停止");}}
        if(failure.get()!=null)throw new IllegalStateException("合成负载未完成；原始日志可重放",failure.get());
        while(processor.processOnce()>0){}
        double elapsed=(System.nanoTime()-started)/1e9;var status=journal.status();if(status.pending()!=0||status.captures()!=produced.get()||status.completed()!=produced.get())throw new IllegalStateException("负载结束后覆盖不一致");
        var report=new LinkedHashMap<String,Object>();report.put("kind","synthetic");report.put("symbols",16);report.put("topics",48);report.put("targetMessagesPerSecond",rate);report.put("captures",status.captures());report.put("verified",status.completed());report.put("elapsedSeconds",elapsed);report.put("processedPerSecond",status.completed()/elapsed);report.put("peakPending",peak.get());
        int count=Math.min(produced.get(),samples.length);long[] sorted=Arrays.copyOf(samples,count);Arrays.sort(sorted);
        for(int percentile:List.of(50,95,99))report.put("appendP"+percentile+"Millis",count==0?0:sorted[Math.min(count-1,(int)Math.ceil(count*percentile/100.0)-1)]/1e6);
        report.put("appendSampleCount",count);var storage=journal.verifyStorage();
        if(!Objects.equals(storage.get("validNormalizedEvents"),status.captures()))throw new IllegalStateException("合成报文没有全部进入有效归一化链路");
        report.put("storage",storage);var root=journal.databasePath().getParent();var expected=accumulator.snapshot();journal.close();
        long restart=System.nanoTime();try(var reopened=new RawJournal(root)){
            var restored=new L2MinuteAccumulator(Duration.ofSeconds(2),10,Clock.systemUTC());restored.restore(reopened.committedAccumulatorState());
            if(!expected.equals(restored.snapshot())||reopened.status().pending()!=0)throw new IllegalStateException("负载后的重启状态不同");
        }report.put("restartMillis",(System.nanoTime()-restart)/1e6);report.put("productionAcceptance",false);return Collections.unmodifiableMap(report);
    }
    static String payload(String topic,int sequence) {
        String symbol=topic.substring(topic.indexOf('/')+1)+".SH";
        if(topic.startsWith("trans/"))return symbol+",B,93000001,"+sequence+",15460,1000,"+sequence+","+(sequence+1);
        if(topic.startsWith("order/"))return symbol+","+java.time.LocalDate.now(RawCapture.SHANGHAI).format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)+",93000002,0,"+sequence+",D,S,98500,8400";
        var values=new ArrayList<>(List.of(symbol,"93000000","15450",Long.toString(sequence*1000L),Long.toString(sequence*15450L),"63","15450","15450","15450","15430"));
        for(int i=0;i<10;i++)values.add(""+(15460+i*10));for(int i=0;i<10;i++)values.add(""+(1000+i));for(int i=0;i<10;i++)values.add(""+(15450-i*10));for(int i=0;i<10;i++)values.add(""+(2000+i));values.addAll(List.of("15870","15150","4175384","5570300","16970","13890"));return String.join(",",values);
    }
}
