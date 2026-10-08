package com.zoutrankil.batch;

import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;

/** Durable intent and unknown-outcome fence for non-transactional Baidu share transfers. */
public final class BaiduTransferIntentStore {
    public enum State { INTENT, UNKNOWN, VERIFIED }
    public record Intent(String date,String remotePath,long sourceFsId,long sourceSizeBytes,State state) {}
    private final JdbcTemplate jdbc;
    public BaiduTransferIntentStore(JdbcTemplate jdbc){this.jdbc=Objects.requireNonNull(jdbc);}

    public Intent reserve(String date,String remotePath,long fsId,long size) {
        jdbc.update("INSERT INTO baidu_transfer_intent(logical_date,remote_path,source_fs_id,source_size_bytes,state) VALUES(?,?,?,?, 'INTENT') ON CONFLICT(logical_date) DO NOTHING",date,remotePath,fsId,size);
        Intent existing=get(date).orElseThrow();
        if(!existing.remotePath().equals(remotePath)||existing.sourceFsId()!=fsId||existing.sourceSizeBytes()!=size)
            throw new IllegalStateException("Baidu transfer input changed; explicit archive revision and reconciliation required");
        return existing;
    }
    public void unknown(String date){int n=jdbc.update("UPDATE baidu_transfer_intent SET state='UNKNOWN',updated_at=current_timestamp WHERE logical_date=? AND state='INTENT'",date);if(n!=1)throw new IllegalStateException("Baidu transfer intent is not sendable");}
    public void verified(String date){int n=jdbc.update("UPDATE baidu_transfer_intent SET state='VERIFIED',updated_at=current_timestamp WHERE logical_date=? AND state IN ('INTENT','UNKNOWN')",date);if(n!=1&&get(date).filter(i->i.state()==State.VERIFIED).isEmpty())throw new IllegalStateException("Baidu transfer intent is not verifiable");}
    public Optional<Intent> get(String date){return jdbc.query("SELECT logical_date,remote_path,source_fs_id,source_size_bytes,state FROM baidu_transfer_intent WHERE logical_date=?",(rs,n)->new Intent(rs.getString(1),rs.getString(2),rs.getLong(3),rs.getLong(4),State.valueOf(rs.getString(5))),date).stream().findFirst();}
}
