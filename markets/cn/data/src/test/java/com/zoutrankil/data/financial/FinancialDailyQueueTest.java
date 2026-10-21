package com.zoutrankil.data.financial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.zoutrankil.data.financial.application.FinancialDailyQueue;
import com.zoutrankil.data.financial.application.FinancialDailyQueue.Slice;
import com.zoutrankil.data.financial.config.FinancialQueueFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

class FinancialDailyQueueTest {
    @TempDir Path root;
    private static final LocalDate DAY = LocalDate.parse("2026-10-20");
    private static final String SHA = "a".repeat(64);
    private static final List<Slice> SLICES =
            List.of(
                    new Slice("income", "600000.SH", LocalDate.parse("2026-06-30")),
                    new Slice("cashflow", "600000.SH", LocalDate.parse("2026-06-30")));

    static class Worker implements FinancialDailyQueue.Executor {
        final List<Slice> calls = new ArrayList<>();
        final List<LocalDate> days = new ArrayList<>();
        final Set<String> verified = new HashSet<>();
        String lastId;
        boolean crash;
        String target = "original-table";

        public String targetFingerprint() {
            return target;
        }

        public void run(Slice slice, LocalDate date, String id, Duration remaining) {
            calls.add(slice);
            days.add(date);
            lastId = id;
            if (crash) throw new IllegalStateException("模拟写入中断");
            verified.add(id);
        }

        public boolean verified(Slice slice, LocalDate date, String id) {
            return verified.contains(id);
        }
    }

    @Test
    void restartKeepsDailyBudgetAndNextUnstartedSliceFreezesItsOwnExecutionDate() throws Exception {
        var worker = new Worker();
        var queue = FinancialQueueFactory.at(root);
        var first = queue.run(SHA, DAY, SLICES, 3, 3, Duration.ofHours(1), worker);
        assertEquals(1, first.completed());
        assertEquals(2, first.total());
        assertEquals("PENDING", queue.status().get("status"));
        assertEquals(1, queue.status().get("completed"));
        FinancialQueueFactory.at(root).run(SHA, DAY, SLICES, 3, 3, Duration.ofHours(1), worker);
        assertEquals(1, worker.calls.size());
        // 次日提议的范围不能替换正在执行的原轮次。
        var next =
                FinancialQueueFactory.at(root)
                        .run(
                                SHA,
                                DAY.plusDays(1),
                                List.of(SLICES.getFirst()),
                                3,
                                3,
                                Duration.ofHours(1),
                                worker);
        assertEquals(2, next.completed());
        assertEquals(SLICES, worker.calls);
        assertEquals(List.of(DAY, DAY.plusDays(1)), worker.days);
        queue.run(SHA, DAY.plusDays(1), SLICES, 3, 3, Duration.ofHours(1), worker);
        assertEquals(2, worker.calls.size());
    }

    @Test
    void interruptedClaimBlocksResendUntilOriginalVerificationExists() throws Exception {
        var worker = new Worker();
        worker.crash = true;
        var queue = FinancialQueueFactory.at(root);
        assertThrows(
                IllegalStateException.class,
                () -> queue.run(SHA, DAY, SLICES, 9, 3, Duration.ofHours(1), worker));
        assertEquals("RUN_REQUIRES_VERIFICATION", queue.status().get("status"));
        worker.crash = false;
        assertThrows(
                IllegalStateException.class,
                () -> queue.run(SHA, DAY.plusDays(1), SLICES, 9, 3, Duration.ofHours(1), worker));
        assertEquals(1, worker.calls.size());
        worker.verified.add(worker.lastId);
        var result = queue.run(SHA, DAY.plusDays(1), SLICES, 9, 3, Duration.ofHours(1), worker);
        assertEquals(2, result.completed());
        assertEquals("CYCLE_VERIFIED", queue.status().get("status"));
        assertEquals(2, worker.calls.size());
    }

    @Test
    void verifiedOwnerSurvivesCrashBeforeQueueReceiptWithoutResending() throws Exception {
        var worker =
                new Worker() {
                    @Override
                    public void run(Slice slice, LocalDate date, String id, Duration remaining) {
                        super.run(slice, date, id, remaining);
                        throw new IllegalStateException("模拟 owner 已验收但队列尚未提交");
                    }
                };
        var queue = FinancialQueueFactory.at(root);
        assertThrows(
                IllegalStateException.class,
                () ->
                        queue.run(
                                SHA,
                                DAY,
                                List.of(SLICES.getFirst()),
                                3,
                                3,
                                Duration.ofHours(1),
                                worker));
        var result =
                FinancialQueueFactory.at(root)
                        .run(
                                SHA,
                                DAY,
                                List.of(SLICES.getFirst()),
                                3,
                                3,
                                Duration.ofHours(1),
                                worker);
        assertEquals(1, result.completed());
        assertEquals(1, worker.calls.size());
    }

    @Test
    void recreatedPhysicalTableCannotReuseCompletedSlices() throws Exception {
        var worker = new Worker();
        var queue = FinancialQueueFactory.at(root);
        queue.run(SHA, DAY, SLICES, 3, 3, Duration.ofHours(1), worker);
        worker.target = "recreated-table";
        assertThrows(
                IllegalStateException.class,
                () -> queue.run(SHA, DAY.plusDays(1), SLICES, 3, 3, Duration.ofHours(1), worker));
        assertEquals(1, worker.calls.size());
    }

    @Test
    void changedApprovalAndBackdatedBudgetCannotReplacePendingCycle() throws Exception {
        var worker = new Worker();
        var queue = FinancialQueueFactory.at(root);
        queue.run(SHA, DAY, SLICES, 3, 3, Duration.ofHours(1), worker);
        assertThrows(
                IllegalStateException.class,
                () ->
                        queue.run(
                                "b".repeat(64),
                                DAY.plusDays(1),
                                SLICES,
                                3,
                                3,
                                Duration.ofHours(1),
                                worker));
        assertThrows(
                IllegalStateException.class,
                () -> queue.run(SHA, DAY.minusDays(1), SLICES, 3, 3, Duration.ofHours(1), worker));
        assertEquals(1, worker.calls.size());
    }
}
