package com.zoutrankil.data.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReferencePublicationJournalSummaryTest {
    @TempDir Path root;

    private ReferencePublicationJournal journal(Path path) throws Exception {
        new SyncRunLedger(path);
        return new ReferencePublicationJournal(path,"index_monthly");
    }

    @Test void scanPreservesDatasetFilteringRawStatesAndTheExistingQueryOrder() throws Exception {
        var path=root.resolve("summary.sqlite3");var journal=journal(path);
        insert(path,"index_monthly",40);
        insert(path,"margin_all",4);
        var expected=new ArrayList<ReferencePublicationJournal.Summary>();
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+path);var query=db.createStatement();
            var rows=query.executeQuery("SELECT run_id,state FROM reference_publications WHERE dataset='index_monthly' AND state<>'VERIFIED' LIMIT 32")){
            while(rows.next())expected.add(new ReferencePublicationJournal.Summary(rows.getString(1),rows.getString(2)));
        }
        for(var policy:ReferencePublicationJournal.ConnectionPolicy.values()){
            var actual=new ArrayList<ReferencePublicationJournal.Summary>();
            journal.visitSummaries(32,true,policy,(summary,rowNumber)->{
                assertEquals(actual.size()+1,rowNumber);actual.add(summary);
            });
            assertEquals(expected,actual);
            assertTrue(actual.stream().anyMatch(row->row.state().equals("UNKNOWN_STATE")));
            assertTrue(actual.stream().noneMatch(row->row.state().equals("VERIFIED")));
        }
    }

    @Test void visitorFailureStopsImmediatelyAndPropagatesTheSameException() throws Exception {
        var path=root.resolve("failure.sqlite3");var journal=journal(path);insert(path,"index_monthly",3);
        var failure=new IOException("visitor failure");var visited=new ArrayList<String>();
        var actual=assertThrowsExactly(IOException.class,()->journal.visitSummaries(32,false,
                ReferencePublicationJournal.ConnectionPolicy.DRIVER_DEFAULTS,(summary,rowNumber)->{
                    visited.add(summary.runId());throw failure;
                }));
        assertSame(failure,actual);assertEquals(1,visited.size());
        var after=new ArrayList<String>();
        journal.visitSummaries(32,false,ReferencePublicationJournal.ConnectionPolicy.DRIVER_DEFAULTS,
                (summary,rowNumber)->after.add(summary.runId()));
        assertEquals(3,after.size());
    }

    @Test void scansRespectBothBoundsAndRejectUnboundedRequests() throws Exception {
        var path=root.resolve("bounded.sqlite3");var journal=journal(path);insert(path,"index_monthly",1005);
        for(int limit:List.of(32,1001)){
            var rows=new ArrayList<String>();
            journal.visitSummaries(limit,false,ReferencePublicationJournal.ConnectionPolicy.DRIVER_DEFAULTS,
                    (summary,rowNumber)->rows.add(summary.runId()));
            assertEquals(limit,rows.size());
        }
        for(int limit:new int[]{0,-1,1002})
            assertThrowsExactly(IllegalArgumentException.class,()->journal.visitSummaries(limit,false,
                    ReferencePublicationJournal.ConnectionPolicy.DRIVER_DEFAULTS,(summary,rowNumber)->fail("No row expected")));
    }

    private static void insert(Path path,String dataset,int count) throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+path);
            var insert=db.prepareStatement("INSERT INTO reference_publications VALUES(?,?,?,?,?,0,'2026-10-07T00:00:00Z')")){
            db.setAutoCommit(false);
            for(int i=0;i<count;i++){
                String run="run-"+i;
                insert.setString(1,dataset+"-"+run);insert.setString(2,dataset);insert.setString(3,run);
                insert.setString(4,"invalid intent JSON is not decoded by a summary scan");
                insert.setString(5,i%3==0?"VERIFIED":i%3==1?"PREPARED":"UNKNOWN_STATE");insert.executeUpdate();
            }
            db.commit();
        }
    }
}
