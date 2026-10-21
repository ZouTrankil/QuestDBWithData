package com.zoutrankil.data.financial.storage;

import com.zoutrankil.data.domain.SyncJobDefinition.FrozenRequest;
import com.zoutrankil.data.domain.SyncRequestIdentity;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.repository.SyncRunLedger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Set;

/** 只读核对原运行及人工恢复后继，不修改账本、来源和锁。 */
public final class FinancialQueuedRunInspection {
    private FinancialQueuedRunInspection() {}

    public static boolean verified(
            Path ledger, String expectedTargetId, FrozenRequest request, String runId)
            throws Exception {
        if (!Files.isRegularFile(ledger)) return false;
        var saved = SyncRunLedger.openReadOnly(ledger);
        String frozen = SyncRequestIdentity.snapshotJson(request);
        try (var connection =
                        DriverManager.getConnection("jdbc:sqlite:" + ledger.toUri() + "?mode=ro");
                var query =
                        connection.prepareStatement(
                                "WITH RECURSIVE chain(id,depth) AS (SELECT ?,0 UNION ALL "
                                        + "SELECT r.id,c.depth+1 FROM sync_runs r JOIN chain c ON r.parent_run_id=c.id WHERE c.depth<100) "
                                        + "SELECT id,NOT EXISTS(SELECT 1 FROM sync_runs child WHERE child.parent_run_id=chain.id) AS leaf FROM chain ORDER BY depth DESC LIMIT 102")) {
            query.setString(1, runId);
            try (var rows = query.executeQuery()) {
                int count = 0;
                boolean verified = true;
                while (rows.next()) {
                    if (++count > 100) throw new IllegalStateException("财务恢复链过长");
                    String id = rows.getString(1);
                    SyncRunLedger.Run run;
                    try {
                        run = saved.getRun(id);
                    } catch (IllegalArgumentException missing) {
                        return false;
                    }
                    if (!expectedTargetId.equals(run.targetId())
                            || !frozen.equals(run.frozenJson()))
                        throw new IllegalStateException("财务分片恢复请求或物理目标已改变");
                    if (rows.getBoolean("leaf")
                            && !Set.of(SyncRunState.VERIFIED, SyncRunState.VERIFIED_EMPTY)
                                    .contains(saved.get(id).state())) verified = false;
                }
                return count > 0 && verified;
            }
        }
    }
}
