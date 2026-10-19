package com.zoutrankil.data.l2live.storage;

import com.zoutrankil.data.l2live.domain.L2CleanEvent;
import com.zoutrankil.data.l2live.domain.L2MinuteState;
import java.util.List;
import java.util.Objects;

/** 原始报文、事件、分钟观察和恢复检查点以类型化 Parquet 批次原子发布。 */
public final class LocalLiveSink {
    private final RawJournal journal;
    public LocalLiveSink(RawJournal journal) { this.journal=Objects.requireNonNull(journal); }
    public synchronized void write(List<L2CleanEvent> events,List<L2MinuteState> states) throws Exception {
        var batch=journal.preparedBatch().orElseThrow(()->new IllegalStateException("Freeze the Parquet batch before committing"));
        if(!LiveValueIdentity.hash(List.of(events,states)).equals(LiveValueIdentity.hash(List.of(batch.events(),batch.states()))))
            throw new IllegalStateException("Local values differ from the durable frozen batch");
        var files=journal.parquetReceipt(batch.id());
        journal.reserveOutbox(batch.id(),files.fingerprint(),files.events(),files.minutes());
        journal.publishParquet(batch.id(),files);
        journal.markWriteVerified(batch.id());
    }
}
