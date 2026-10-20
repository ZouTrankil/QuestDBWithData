package com.zoutrankil.data.l2live.storage;

import com.zoutrankil.data.l2live.domain.RawCapture;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** 有界接收队列；一次 FULL 提交保存多条原文，完成通知只在提交之后发出。 */
public final class RawCaptureBatchWriter implements AutoCloseable {
    private record Pending(RawCapture capture,long bytes,CompletableFuture<Void> saved) {}
    private final RawJournal journal;
    private final BlockingQueue<Pending> queue=new LinkedBlockingQueue<>(4096);
    private final Object lifecycle=new Object();
    private final Thread worker;
    private long queuedBytes,inFlight;
    private boolean closing;
    private volatile RuntimeException failure;

    public RawCaptureBatchWriter(RawJournal journal) {
        this.journal=Objects.requireNonNull(journal);
        worker=new Thread(this::run,"l2-raw-full-commit");
        // 正常关闭必须排空。不能用 daemon 随主线程退出丢掉尚未落盘的队列。
        worker.start();
    }

    public CompletionStage<Void> submit(String topic,byte[] payload,Instant received,String session,
            int qos,boolean retained,boolean duplicate,boolean authorized) {
        var capture=new RawCapture("cap_"+UUID.randomUUID().toString().replace("-",""),0,session,topic,
                Base64.getEncoder().encodeToString(payload),received,received.atZone(RawCapture.SHANGHAI).toLocalDate(),qos,retained,duplicate,authorized);
        var pending=new Pending(capture,payload.length,new CompletableFuture<>());
        synchronized(lifecycle) {
            if(failure!=null)throw failure;
            if(closing)throw new IllegalStateException("RAW_WRITER_CLOSED");
            if(queuedBytes+payload.length>16L*1024*1024||!queue.offer(pending))
                throw new IllegalStateException("RAW_RECEIVE_QUEUE_FULL");
            queuedBytes+=payload.length;inFlight++;
        }
        return pending.saved();
    }

    public Map<String,Object> status() {
        synchronized(lifecycle) {return Map.of("pendingDurability",inFlight,"pendingBytes",queuedBytes,
                "failed",failure!=null,"maxRows",4096,"maxBytes",16L*1024*1024,
                "closing",closing,"workerAlive",worker.isAlive(),
                "draining",closing&&worker.isAlive()&&failure==null);}
    }

    private void run() {
        var batch=new ArrayList<Pending>();
        try {
            while(true) {
                synchronized(lifecycle){if(closing&&queue.isEmpty())return;}
                var first=queue.poll(25,TimeUnit.MILLISECONDS);if(first==null)continue;
                batch.add(first);long bytes=first.bytes(),deadline=System.nanoTime()+25_000_000;
                // 最多等待 25ms 凑批；200 条、4MiB 上限同时保留。
                while(batch.size()<RawJournal.MAX_BATCH_ROWS&&bytes<=3L*1024*1024) {
                    long remaining=deadline-System.nanoTime();if(remaining<=0)break;
                    var next=queue.poll(remaining,TimeUnit.NANOSECONDS);if(next==null)break;
                    batch.add(next);bytes+=next.bytes();
                }
                journal.appendBatch(batch.stream().map(Pending::capture).toList());
                for(var pending:batch) {
                    synchronized(lifecycle){queuedBytes-=pending.bytes();inFlight--;}
                    pending.saved().complete(null);
                }
                batch.clear();
            }
        } catch(Exception error) {
            failure=new IllegalStateException("RAW_BATCH_COMMIT_FAILED",error);
            synchronized(lifecycle){closing=true;queue.drainTo(batch);}
            for(var pending:batch)pending.saved().completeExceptionally(failure);
        }
    }

    @Override public void close() {
        synchronized(lifecycle){closing=true;}
        // 慢盘可能超过启动器的等待时限，但 close 返回后外层就会关闭 journal。
        // 因此必须等工作线程结束；中断只记录，不能让已接收队列失去写入连接。
        boolean interrupted=false;
        try {
            while(worker.isAlive()) {
                try {worker.join(1_000);}
                catch(InterruptedException error){interrupted=true;}
            }
        } finally {if(interrupted)Thread.currentThread().interrupt();}
        if(failure!=null)throw failure;
        synchronized(lifecycle) {
            if(inFlight!=0||queuedBytes!=0||!queue.isEmpty())
                throw new IllegalStateException("RAW_WRITER_DRAIN_INCOMPLETE");
        }
    }
}
