package com.zoutrankil.market.us.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.marketdata.VerifiedMarketDataStore;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;

class UsYahooAliasOwnershipTest {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 16);

    private static Connection db() throws Exception {
        var db = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (var s = db.createStatement()) {
            s.execute(
                    "CREATE TABLE broad_tasks(id TEXT PRIMARY KEY,kind TEXT,symbol TEXT,yahoo TEXT,phase TEXT,proof TEXT,priority INTEGER,bundle_id TEXT,raw TEXT,sha TEXT,observed TEXT,updated_at TEXT)");
        }
        return db;
    }

    private static String add(
            Connection db,
            String symbol,
            String yahoo,
            String phase,
            int priority,
            String bundle,
            LocalDate day)
            throws Exception {
        String id =
                VerifiedMarketDataStore.sha(
                        ("v1|DAILY|" + symbol + "|" + day + "|").getBytes(StandardCharsets.UTF_8));
        try (var s =
                db.prepareStatement(
                        "INSERT INTO broad_tasks VALUES(?,'DAILY',?,?,?,'old source proof',?,?, 'raw','sha','observed','old')")) {
            s.setString(1, id);
            s.setString(2, symbol);
            s.setString(3, yahoo);
            s.setString(4, phase);
            s.setInt(5, priority);
            s.setString(6, bundle);
            s.executeUpdate();
        }
        return id;
    }

    private static String field(Connection db, String id, String name) throws Exception {
        try (var s = db.prepareStatement("SELECT " + name + " FROM broad_tasks WHERE id=?")) {
            s.setString(1, id);
            try (var r = s.executeQuery()) {
                assertTrue(r.next());
                return r.getString(1);
            }
        }
    }

    @Test
    void holdsOnlyTheUnboundAliasAndKeepsRawAndPriorProof() throws Exception {
        try (var db = db()) {
            String owner = add(db, "HVT.A", "HVT-A", "VERIFIED", 10, "bundle", DAY),
                    alias = add(db, "HVT-A", "HVT-A", "FETCHED", 20, null, DAY);
            var result = UsYahooAliasOwnership.hold(db, DAY);
            assertEquals(1, result.size());
            assertEquals(owner, result.getFirst().ownerTask());
            assertEquals(alias, result.getFirst().task());
            assertEquals("SCOPE_REVIEW_REQUIRED", field(db, alias, "phase"));
            assertEquals("VERIFIED", field(db, owner, "phase"));
            for (String column : List.of("raw", "sha", "observed"))
                assertEquals(column, field(db, alias, column));
            var proof = new ObjectMapper().readTree(field(db, alias, "proof"));
            assertEquals("old source proof", proof.path("priorProof").asText());
            assertEquals("HVT.A", proof.path("ownership").path("ownerSymbol").asText());
            assertTrue(UsYahooAliasOwnership.hold(db, DAY).isEmpty());
        }
    }

    @Test
    void keepsAnotherCutoffAndSameLookingButDifferentYahooCodeIndependent() throws Exception {
        try (var db = db()) {
            add(db, "A.B", "A-B", "VERIFIED", 10, null, DAY);
            String otherDate = add(db, "A-B", "A-B", "FETCHED", 20, null, DAY.plusDays(1)),
                    otherSource = add(db, "AB", "AB", "FETCHED", 20, null, DAY);
            assertTrue(UsYahooAliasOwnership.hold(db, DAY).isEmpty());
            assertEquals("FETCHED", field(db, otherDate, "phase"));
            assertEquals("FETCHED", field(db, otherSource, "phase"));
        }
    }

    @Test
    void prefersExistingSuccessAndNeverRewritesFrozenOrPublishingAliases() throws Exception {
        try (var db = db()) {
            String success = add(db, "A.B", "A-B", "VERIFIED", 20, null, DAY),
                    frozen = add(db, "A-B", "A-B", "FETCHED", 0, "frozen", DAY),
                    pending = add(db, "AB", "A-B", "PUBLISHING", 0, null, DAY),
                    failed = add(db, "ABX", "A-B", "FAILED_SOURCE", 0, null, DAY);
            var held = UsYahooAliasOwnership.hold(db, DAY);
            assertEquals(1, held.size());
            assertEquals(success, held.getFirst().ownerTask());
            assertEquals(failed, held.getFirst().task());
            assertEquals("FETCHED", field(db, frozen, "phase"));
            assertEquals("PUBLISHING", field(db, pending, "phase"));
        }
    }

    @Test
    void choosesRegisteredPriorityWithoutPromotingAnExistingReviewOrChangingItsReason()
            throws Exception {
        try (var db = db()) {
            String original = add(db, "A.B", "A-B", "PLANNED", 10, null, DAY),
                    alias = add(db, "A-B", "A-B", "FETCHED", 20, null, DAY),
                    review = add(db, "ABX", "A-B", "SCOPE_REVIEW_REQUIRED", 30, null, DAY);
            var held = UsYahooAliasOwnership.hold(db, DAY);
            assertEquals(1, held.size());
            assertEquals(original, held.getFirst().ownerTask());
            assertEquals(alias, held.getFirst().task());
            assertEquals("old source proof", field(db, review, "proof"));
        }
    }

    @Test
    void leavesCommitToAnExistingTransaction() throws Exception {
        try (var db = db()) {
            add(db, "A.B", "A-B", "VERIFIED", 10, null, DAY);
            String alias = add(db, "A-B", "A-B", "FETCHED", 20, null, DAY);
            db.setAutoCommit(false);
            assertEquals(1, UsYahooAliasOwnership.hold(db, DAY).size());
            assertFalse(db.getAutoCommit());
            db.rollback();
            assertEquals("FETCHED", field(db, alias, "phase"));
        }
    }

    @Test
    void rollsBackEveryReviewWhenOneCompareAndSetFails() throws Exception {
        try (var db = db()) {
            add(db, "A.B", "A-B", "VERIFIED", 10, null, DAY);
            String first = add(db, "A-B", "A-B", "FETCHED", 20, null, DAY),
                    second = add(db, "ABX", "A-B", "FETCHED", 20, null, DAY);
            try (var s = db.createStatement()) {
                s.execute(
                        "CREATE TRIGGER ignore_second BEFORE UPDATE ON broad_tasks WHEN OLD.symbol='ABX' BEGIN SELECT RAISE(IGNORE); END");
            }
            assertThrows(IllegalStateException.class, () -> UsYahooAliasOwnership.hold(db, DAY));
            assertTrue(db.getAutoCommit());
            assertEquals("FETCHED", field(db, first, "phase"));
            assertEquals("FETCHED", field(db, second, "phase"));
            assertEquals("old source proof", field(db, first, "proof"));
        }
    }
}
