package com.zoutrankil.data.index.domain;

import com.zoutrankil.data.domain.ThsMember;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.table.ThsMemberRow;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Immutable board and unaffected-board evidence used by both layers. */
public final class ThsMemberState {
    private ThsMemberState() {}
    public static final int MAX_BOARD_ROWS=10000,MAX_TOTAL_ROWS=1000000;
    public record Identity(long id, String directory, long writerTxn) {}
    public record Snapshot(Identity identity, String board, List<ThsMemberRow> boardRows,
                           long otherRows, String otherFingerprint) {
        public Snapshot { boardRows = List.copyOf(boardRows); }
        public long totalRows() { return otherRows + boardRows.size(); }
        public String contentFingerprint() throws Exception {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(otherFingerprint.getBytes(StandardCharsets.UTF_8));
            digest.update(JobDefinitionJson.mapper().writeValueAsBytes(boardRows));
            return HexFormat.of().formatHex(digest.digest());
        }
    }
    public record Prepared(String target, String board, ThsMemberState.Snapshot before,
                           List<ThsMember> source) {
        public Prepared { source = List.copyOf(source); }
    }
    public record Verified(String stage, ThsMemberState.Snapshot snapshot, int batches, String receipt) {}
}
