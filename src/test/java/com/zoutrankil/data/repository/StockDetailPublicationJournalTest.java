package com.zoutrankil.data.repository;

import com.zoutrankil.data.service.DatasetIntervalLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static com.zoutrankil.data.repository.StockDetailPublicationJournal.State;

class StockDetailPublicationJournalTest {
    @TempDir Path root;
    @Test void reopenedJournalRetainsIntentRejectsStalePhaseAndChecksActualLeaseState() throws Exception {
        var path=root.resolve("ledger.sqlite");var ledger=new SyncRunLedger(path);
        ledger.createRun(new SyncRunLedger.Run("run",null,"job",1,"2026-09-29","target","{}"));
        var locks=new DatasetIntervalLock(path);var lease=locks.acquire("run",DatasetIntervalLock.Scope.allDates("stock_detail_info"));
        var journal=new StockDetailPublicationJournal(path);journal.requireLease(lease,false);
        var intent=new StockDetailPublicationJournal.Intent("publication","run","target","backup","stage",1,2,"old","new");
        var entry=journal.create(intent);
        assertThrows(IllegalArgumentException.class,()->journal.advance(entry,State.VERIFIED));
        var moved=journal.advance(entry,State.OLD_RENAMED);
        assertThrows(IllegalStateException.class,()->journal.advance(entry,State.OLD_RENAMED));
        var reopened=new StockDetailPublicationJournal(path);
        assertEquals(moved,reopened.get("publication"));
        var uncertain=reopened.advance(moved,State.IN_DOUBT);locks.retainInDoubt(lease);
        assertEquals(State.IN_DOUBT,new StockDetailPublicationJournal(path).get("publication").state());
        assertThrows(IllegalStateException.class,()->reopened.requireLease(lease,false));
        assertDoesNotThrow(()->reopened.requireLease(lease,true));
        assertThrows(IllegalArgumentException.class,()->journal.advance(uncertain,State.NEW_RENAMED));
    }
}
