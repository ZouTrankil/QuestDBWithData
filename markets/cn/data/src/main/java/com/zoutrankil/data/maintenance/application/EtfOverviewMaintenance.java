package com.zoutrankil.data.maintenance.application;

import com.zoutrankil.data.maintenance.storage.MaterializedViewCheckpoints;
import com.zoutrankil.data.maintenance.storage.MaterializedViewCheckpoints.Checkpoint;
import com.zoutrankil.data.repository.EtfMarketOverviewMaterializedViewReadGuard;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import static com.zoutrankil.data.repository.EtfMarketOverviewMaterializedViewReadGuard.*;

/** 份额变动使用原生增量刷新；JOIN 右侧数据修订时，须全量重建关联结果。 */
@Service @Lazy
public final class EtfOverviewMaintenance {
    private final JdbcTemplate jdbc;
    private final Path ledger;
    private final Duration timeout;

    @Autowired
    public EtfOverviewMaintenance(JdbcTemplate jdbc,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger,
            @Value("${app.materialized-views.etf-overview.refresh-timeout:2m}") Duration timeout) {
        this(new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource())),Path.of(ledger),timeout);
        this.jdbc.setQueryTimeout(120);
    }

    EtfOverviewMaintenance(JdbcTemplate jdbc,Path ledger,Duration timeout) {
        this.jdbc=jdbc;this.ledger=ledger.toAbsolutePath().normalize();this.timeout=timeout;
        if(timeout.isNegative()||timeout.isZero())throw new IllegalArgumentException("Positive refresh timeout required");
    }

    /** 数据库尚未使用该产品时，后台检查不会自行安装相关对象。 */
    public Map<String,Object> refreshIfInstalled() throws Exception {
        if(jdbc.queryForList("SELECT view_name FROM materialized_views() WHERE view_name='"+OUTPUT+"'").isEmpty())
            return Map.of("status","ABSENT");
        return refresh();
    }

    /**
     * 先根据两侧来源和输出身份选择刷新方式，再逐窗核验整个历史，最后保存本地证书。
     * 校验期间任何来源或输出变化，均不得把检查点推进到未经验证的版本。
     */
    public Map<String,Object> refresh() throws Exception {
        Files.createDirectories(ledger.getParent());
        try(var channel=FileChannel.open(ledger.resolveSibling(ledger.getFileName()+".etf-mv.lock"),
                StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.tryLock()) {
            if(lock==null)throw new IllegalStateException("ETF MV refresh is already running");
            install();
            var view=view(jdbc);requireDefinition(view);
            String left=snapshot(jdbc,"etf_share"),right=snapshot(jdbc,"etf_daily");
            String checkpoint=checkpointName(jdbc);
            var store=new MaterializedViewCheckpoints(ledger);var old=store.find(checkpoint);
            String output=snapshot(jdbc,OUTPUT);
            if(old.isPresent() && current(old.get(),left,right,output,view))
                return Map.of("status","CURRENT","output",OUTPUT,"mode","REUSED");
            // 右表修订不会由基表增量自动覆盖；证书缺失、定义变化或输出重建也须全量重算。
            boolean full=old.isEmpty() || !old.get().definitionHash().equals(definitionHash())
                    || !old.get().rightVersion().equals(right) || !generation(old.get().outputVersion()).equals(generation(output))
                    || !"valid".equals(view.get("view_status")) || nonempty(view.get("invalidation_reason"));
            // 原生增量若已追平基表，直接核验现有结果，避免等待一个实际不会发生的空刷新。
            boolean nativeCurrent=!full && "valid".equals(view.get("view_status")) && !nonempty(view.get("invalidation_reason"))
                    && Objects.equals(view.get("refresh_base_table_txn"),view.get("base_table_txn"))
                    && !Objects.toString(view.get("last_refresh_finish_timestamp"),"").isBlank();
            String mode=full?"FULL":nativeCurrent?"NATIVE_INCREMENTAL":"INCREMENTAL";
            String previousFinish=Objects.toString(view.get("last_refresh_finish_timestamp"),"");
            Map<String,Object> refreshed=view;
            if(!nativeCurrent) {
                jdbc.execute("REFRESH MATERIALIZED VIEW "+OUTPUT+" "+mode);
                long deadline=System.nanoTime()+timeout.toNanos();
                while(true) {
                    refreshed=view(jdbc);requireDefinition(refreshed);
                    if(!Objects.toString(refreshed.get("last_refresh_finish_timestamp"),"").isBlank()
                            && !previousFinish.equals(Objects.toString(refreshed.get("last_refresh_finish_timestamp"),""))
                            && "valid".equals(refreshed.get("view_status")) && !nonempty(refreshed.get("invalidation_reason"))
                            && Objects.equals(refreshed.get("refresh_base_table_txn"),refreshed.get("base_table_txn")))break;
                    if(System.nanoTime()>deadline)throw new IllegalStateException("ETF native refresh did not complete before timeout");
                    Thread.sleep(25);
                }
            }
            String verifiedOutput=snapshot(jdbc,OUTPUT);
            long rows=verifyHistory();
            if(!left.equals(snapshot(jdbc,"etf_share")) || !right.equals(snapshot(jdbc,"etf_daily"))
                    || !verifiedOutput.equals(snapshot(jdbc,OUTPUT)))
                throw new IllegalStateException("ETF sources/output changed during refresh verification; no checkpoint was advanced");
            store.save(checkpoint,new Checkpoint(definitionHash(),left,right,verifiedOutput,
                    Objects.toString(refreshed.get("last_refresh_finish_timestamp"))));
            versionIfActivated(jdbc,ledger);
            return Map.of("status","VERIFIED","output",OUTPUT,"mode",mode,"verifiedRows",rows,"ledger",ledger.toString());
        }
    }

    private void install() {
        var existing=jdbc.queryForList("SELECT * FROM materialized_views() WHERE view_name='"+OUTPUT+"'");
        if(existing.isEmpty()) {
            if(!jdbc.queryForList("SELECT table_name FROM tables() WHERE table_name='"+OUTPUT+"'").isEmpty())
                throw new IllegalStateException("ETF output is an ordinary table; preserve and migrate its data explicitly");
            jdbc.execute("CREATE MATERIALIZED VIEW "+OUTPUT+" WITH BASE etf_share REFRESH MANUAL DEFERRED AS ("+SQL+") PARTITION BY MONTH");
        } else requireDefinition(existing.getFirst());
        // QuestDB 维护基表增量；Java 检查器额外处理关联表的版本变化。
        if(!"immediate".equals(view(jdbc).get("refresh_type")))
            jdbc.execute("ALTER MATERIALIZED VIEW "+OUTPUT+" SET REFRESH IMMEDIATE");
    }

    private static boolean current(Checkpoint old,String left,String right,String output,Map<String,Object> view) {
        return old.definitionHash().equals(definitionHash()) && old.leftVersion().equals(left)
                && old.rightVersion().equals(right) && old.outputVersion().equals(output)
                && old.refreshFinished().equals(Objects.toString(view.get("last_refresh_finish_timestamp"),""))
                && "valid".equals(view.get("view_status")) && !nonempty(view.get("invalidation_reason"))
                && Objects.equals(view.get("refresh_base_table_txn"),view.get("base_table_txn"));
    }

    private long verifyHistory() {
        var bounds=jdbc.queryForMap("SELECT min(timestamp) AS lo,max(timestamp) AS hi FROM etf_share");
        if(bounds.get("lo")==null) {
            if(!jdbc.queryForList("SELECT trade_date FROM "+OUTPUT+" LIMIT 1").isEmpty())throw new IllegalStateException("ETF output has rows without sources");
            return 0;
        }
        LocalDate start=date(bounds.get("lo")),end=date(bounds.get("hi")).plusDays(1);
        if(java.time.temporal.ChronoUnit.DAYS.between(start,end)>36600)throw new IllegalStateException("ETF history exceeds verification budget");
        var outputBounds=jdbc.queryForMap("SELECT min(trade_date) AS lo,max(trade_date) AS hi FROM "+OUTPUT);
        if(outputBounds.get("lo")!=null && (date(outputBounds.get("lo")).isBefore(start)||!date(outputBounds.get("hi")).isBefore(end)))
            throw new IllegalStateException("ETF output contains dates outside source history");
        long count=0;
        // 双侧 JOIN 都施加日期边界，将全历史核验拆成有限窗口，避免单次扫描整个关联历史。
        for(LocalDate lo=start;lo.isBefore(end);lo=lo.plusDays(31)) {
            LocalDate hi=lo.plusDays(31).isBefore(end)?lo.plusDays(31):end;
            String direct=SQL.replace("SAMPLE BY","WHERE s.timestamp>=cast(? AS TIMESTAMP) AND s.timestamp<cast(? AS TIMESTAMP) "
                    +"AND d.timestamp>=cast(? AS TIMESTAMP) AND d.timestamp<cast(? AS TIMESTAMP) SAMPLE BY");
            var expected=jdbc.queryForList(direct+" ORDER BY trade_date",lo.toString(),hi.toString(),lo.toString(),hi.toString());
            var actual=jdbc.queryForList("SELECT trade_date,etf_count,total_share,total_size_yi FROM "+OUTPUT
                    +" WHERE trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP) ORDER BY trade_date",lo.toString(),hi.toString());
            requireParity(expected,actual);count+=actual.size();
        }
        return count;
    }

    static void requireParity(List<Map<String,Object>> expected,List<Map<String,Object>> actual) {
        if(expected.size()!=actual.size())throw new IllegalStateException("ETF MV row coverage differs from both-source aggregate");
        for(int i=0;i<expected.size();i++) {
            var a=expected.get(i);var b=actual.get(i);
            if(!date(a.get("trade_date")).equals(date(b.get("trade_date"))) || !Objects.equals(a.get("etf_count"),b.get("etf_count")))
                throw new IllegalStateException("ETF MV business keys/count differ");
            for(String field:List.of("total_share","total_size_yi")) {
                Object x=a.get(field),y=b.get(field);
                if(x==null&&y==null)continue;
                if(!(x instanceof Number xn)||!(y instanceof Number yn)||!Double.isFinite(xn.doubleValue())||!Double.isFinite(yn.doubleValue())
                        || Math.abs(xn.doubleValue()-yn.doubleValue())>1e-8+1e-9*Math.abs(xn.doubleValue()))
                    throw new IllegalStateException("ETF MV aggregate differs: "+field);
            }
        }
    }

    private static LocalDate date(Object value) {
        if(value instanceof Timestamp t)return t.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
        if(value instanceof LocalDate d)return d;
        return LocalDate.parse(value.toString().substring(0,10));
    }
    private static String generation(String snapshot) { var parts=snapshot.split(":");return String.join(":",Arrays.copyOf(parts,3)); }
    private static boolean nonempty(Object value) { return value!=null&&!value.toString().isBlank(); }
}
