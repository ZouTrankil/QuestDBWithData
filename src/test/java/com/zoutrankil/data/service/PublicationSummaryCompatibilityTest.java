package com.zoutrankil.data.service;

import com.zoutrankil.data.repository.ReferencePublicationJournal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PublicationSummaryCompatibilityTest {
    @TempDir Path root;

    @FunctionalInterface private interface Check { void run() throws Exception; }
    @FunctionalInterface private interface Recovery { AutoCloseable open(String run) throws Exception; }
    private record Gate(String dataset,String message,Check check,Recovery recovery) {}

    @Test void pendingFailuresAndRecoveryExceptionsKeepTheirDatasetSpecificContracts() throws Exception {
        var path=root.resolve("pending.sqlite3");
        var jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:"+root.resolve("unused-target.sqlite3")));
        var monthly=new IndexMonthlyPublication(jdbc,path);
        var all=new MarginAllPublication(jdbc,path);
        var zrz=new MarginZrzPublication(jdbc,path);
        var moneyflow=new MoneyflowHsgtPublication(jdbc,path);
        var gates=List.of(
                new Gate("index_monthly","Unresolved D022 publication requires finish: run",
                        monthly::requireNoPendingPublication,monthly::beginStageRecoveryOperation),
                new Gate("margin_all","Unresolved D028 publication requires finish: run",
                        all::requireNoPendingPublication,all::beginOperation),
                new Gate("margin_zrz","Unresolved D031 publication requires finish: run",
                        zrz::requireNoPendingPublication,zrz::beginOperation),
                new Gate("moneyflow_hsgt","D027 publication or owning ledger run requires recovery/finalization: run",
                        moneyflow::requireNoPendingPublication,moneyflow::beginOperation));
        for(var gate:gates){
            insertPublication(path,gate.dataset(),"run","UNKNOWN_STATE");
            var failure=assertThrowsExactly(IllegalStateException.class,gate.check()::run);
            assertEquals(gate.message(),failure.getMessage());assertNull(failure.getCause());
            try(var ignored=gate.recovery().open("run")){}
        }
    }

    @Test void moneyflowCountsThe1001stRowBeforeSkippingItsRecoveryRun() throws Exception {
        var path=root.resolve("bounded.sqlite3");
        var jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:"+root.resolve("unused-target.sqlite3")));
        var publication=new MoneyflowHsgtPublication(jdbc,path);
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+path);
            var run=db.prepareStatement("INSERT INTO sync_runs VALUES(?,NULL,'job',1,'2026-10-07','target','{}')");
            var entry=db.prepareStatement("INSERT INTO sync_entries VALUES(?,'RUN',?,NULL,'VERIFIED',0,'{}','2026-10-07T00:00:00Z')");
            var intent=db.prepareStatement("INSERT INTO reference_publications VALUES(?,'moneyflow_hsgt',?,'{}','VERIFIED',0,'2026-10-07T00:00:00Z')")){
            db.setAutoCommit(false);
            for(int i=0;i<1001;i++){
                String id="run-"+i;
                run.setString(1,id);run.executeUpdate();
                entry.setString(1,id);entry.setString(2,id);entry.executeUpdate();
                intent.setString(1,id);intent.setString(2,id);intent.executeUpdate();
            }
            db.commit();
        }
        var order=new ArrayList<String>();
        new ReferencePublicationJournal(path,"moneyflow_hsgt").visitSummaries(1001,false,
                ReferencePublicationJournal.ConnectionPolicy.DRIVER_DEFAULTS,(summary,rowNumber)->order.add(summary.runId()));
        assertEquals(1001,order.size());
        var failure=assertThrowsExactly(IllegalStateException.class,()->{
            try(var ignored=publication.beginOperation(order.getLast())){}
        });
        assertEquals("D027 publication journal scan exceeds 1000 rows",failure.getMessage());
    }

    private static void insertPublication(Path path,String dataset,String run,String state) throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+path);
            var insert=db.prepareStatement("INSERT INTO reference_publications VALUES(?,?,?,'{}',?,0,'2026-10-07T00:00:00Z')")){
            insert.setString(1,dataset+"-"+run);insert.setString(2,dataset);insert.setString(3,run);insert.setString(4,state);
            insert.executeUpdate();
        }
    }
}
