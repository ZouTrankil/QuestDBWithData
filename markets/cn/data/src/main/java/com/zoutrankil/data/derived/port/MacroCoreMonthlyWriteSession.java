package com.zoutrankil.data.derived.port;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.derived.domain.MacroCoreMonthlyRows;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import java.time.YearMonth;
import java.util.List;

/** One independently owned sender lifecycle and its exact physical readback. */
public interface MacroCoreMonthlyWriteSession extends VerifiedWriteSession<MacroCoreMonthly,YearMonth> {
    VerifiedBatchExecutor.Codec<MacroCoreMonthly,YearMonth> CODEC=new VerifiedBatchExecutor.Codec<>() {
        public YearMonth key(MacroCoreMonthly row){return MacroCoreMonthlyRows.key(row);}
        public byte[] canonicalBytes(MacroCoreMonthly row){return MacroCoreMonthlyRows.canonicalBytes(row);}
        public int estimatedTransportBytes(MacroCoreMonthly row,byte[] canonical){return MacroCoreMonthlyRows.estimatedTransportBytes(row,canonical);}
    };
    @Override default VerifiedBatchExecutor.Codec<MacroCoreMonthly,YearMonth> codec(){return CODEC;}
    String table();
    String targetId();
    boolean unresolved();
    void createIsolatedTarget();
    MacroCoreMonthlyTargetSnapshot targetSnapshot();
    List<MacroCoreMonthly> readActualRange(YearMonth from,YearMonth toInclusive);
}
