package com.zoutrankil.data.repository;

import com.zoutrankil.data.stock.storage.StockStDailyPublicationStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class StockStDailyPublicationStoreTest {
    @TempDir Path root;
    record Stored(String json, String state, long revision) {}

    private Path ledger() throws Exception {
        Path path = root.resolve("ledger.sqlite");
        var ledger = new SyncRunLedger(path);
        for (String id : new String[]{"one", "two"})
            ledger.createRun(new SyncRunLedger.Run(id, null, "data.stk_st_daily", 1, "2026-09-29", "target", "{}"));
        return path;
    }

    @Test void intentAndOwnershipSurviveReopenAndCasRejectsStaleState() throws Exception {
        Path path = ledger();
        var store = new StockStDailyPublicationStore(path);
        String json = "{\"text\":\"原始\\n字节\",\"value\":1.00}";
        assertTrue(store.findForRun("missing", Stored::new).isEmpty());
        store.reserve("one", "pub-one", () -> json);
        var reopened = new StockStDailyPublicationStore(path);
        assertEquals(new Stored(json, "PREPARED", 0), reopened.findForRun("one", Stored::new).orElseThrow());
        reopened.requireMutex("one", "pub-one");
        assertEquals("D012 table publication is unresolved for run one",
                assertThrows(IllegalStateException.class, reopened::requireNoPendingPublication).getMessage());
        reopened.advance("one", "PREPARED", 0, "OLD_MOVED");
        assertEquals(new Stored(json, "OLD_MOVED", 1), store.findForRun("one", Stored::new).orElseThrow());
        assertEquals("D012 publication phase changed concurrently", assertThrows(IllegalStateException.class,
                () -> store.advance("one", "PREPARED", 0, "OLD_MOVED")).getMessage());
    }

    @Test void serializationFailureAndForeignKeyFailureReleaseUncommittedMutex() throws Exception {
        Path path = ledger();
        var store = new StockStDailyPublicationStore(path);
        var failure = new IOException("encoding failed");
        assertSame(failure, assertThrows(IOException.class, () -> store.reserve("one", "pub-one", () -> { throw failure; })));
        store.requireNoPendingPublication();
        assertTrue(store.findForRun("one", Stored::new).isEmpty());
        var sqlFailure = assertThrows(IllegalStateException.class, () -> store.reserve("missing", "pub-missing", () -> "{}"));
        assertEquals("Cannot reserve durable D012 publication slot", sqlFailure.getMessage());
        assertInstanceOf(java.sql.SQLException.class, sqlFailure.getCause());
        store.requireNoPendingPublication();
        store.reserve("one", "pub-one", () -> "{}");
        assertThrows(IllegalStateException.class, () -> store.reserve("two", "pub-two", () -> fail("encoder must run after mutex CAS")));
        store.requireMutex("one", "pub-one");
    }

    @Test void competingReservationsHaveOneDurableWinner() throws Exception {
        Path path = ledger();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var wins = new AtomicInteger();
        var stores = new StockStDailyPublicationStore[]{new StockStDailyPublicationStore(path), new StockStDailyPublicationStore(path)};
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 2; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    try {
                        assertTrue(start.await(10, TimeUnit.SECONDS));
                        stores[index].reserve(index == 0 ? "one" : "two", "publication-" + index, () -> "{}");
                        wins.incrementAndGet();
                    } catch (IllegalStateException occupied) {
                        assertEquals("Another D012 publication holds the table lock", occupied.getMessage());
                    } catch (Exception failure) { throw new RuntimeException(failure); }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
            for (var future : futures) future.get(15, TimeUnit.SECONDS);
        }
        assertEquals(1, wins.get());
        var reopened = new StockStDailyPublicationStore(path);
        assertEquals(1, (reopened.findForRun("one", Stored::new).isPresent() ? 1 : 0)
                + (reopened.findForRun("two", Stored::new).isPresent() ? 1 : 0));
    }

    @Test void releaseChecksBothIdentifiersAndIdempotentReleaseDoesNotClearAnotherOwner() throws Exception {
        var store = new StockStDailyPublicationStore(ledger());
        store.reserve("one", "pub-one", () -> "{}");
        assertThrows(IllegalStateException.class, () -> store.requireMutex("one", "wrong"));
        assertThrows(IllegalStateException.class, () -> store.release("two", "pub-one"));
        assertThrows(IllegalStateException.class, () -> store.releaseIfOwned("one", "wrong"));
        store.requireMutex("one", "pub-one");
        store.releaseIfOwned("one", "pub-one");
        store.releaseIfOwned("one", "pub-one");
        store.requireNoPendingPublication();
        store.reserve("two", "pub-two", () -> "{}");
        assertThrows(IllegalStateException.class, () -> store.releaseIfOwned("one", "pub-one"));
        store.requireMutex("two", "pub-two");
    }

    @Test void missingLedgerIsNotCreatedAndMissingMutexRemainsAnError() throws Exception {
        Path absent = root.resolve("absent.sqlite");
        assertEquals("Existing D012 run ledger required", assertThrows(IllegalArgumentException.class,
                () -> new StockStDailyPublicationStore(absent)).getMessage());
        assertFalse(Files.exists(absent));
        Path path = ledger();
        var store = new StockStDailyPublicationStore(path);
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + path); var sql = db.createStatement()) {
            sql.executeUpdate("DELETE FROM stk_st_daily_publication_mutex");
        }
        assertEquals("D012 publication mutex row disappeared", assertThrows(IllegalStateException.class,
                () -> store.releaseIfOwned("one", "pub-one")).getMessage());
    }
}
