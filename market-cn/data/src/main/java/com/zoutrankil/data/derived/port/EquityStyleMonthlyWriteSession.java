package com.zoutrankil.data.derived.port;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.derived.domain.EquityStyleMonthlyRows;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import java.time.YearMonth;
import java.util.List;

/** One independently owned sender lifecycle and its exact physical readback. */
public interface EquityStyleMonthlyWriteSession extends VerifiedWriteSession<EquityStyleMonthly,YearMonth> {
    VerifiedBatchExecutor.Codec<EquityStyleMonthly,YearMonth> CODEC=new VerifiedBatchExecutor.Codec<>() {
        public YearMonth key(EquityStyleMonthly row){return EquityStyleMonthlyRows.key(row);}
        public byte[] canonicalBytes(EquityStyleMonthly row){return EquityStyleMonthlyRows.canonicalBytes(row);}
        public int estimatedTransportBytes(EquityStyleMonthly row,byte[] canonical){return EquityStyleMonthlyRows.estimatedTransportBytes(row,canonical);}
    };
    @Override default VerifiedBatchExecutor.Codec<EquityStyleMonthly,YearMonth> codec(){return CODEC;}
    String table();
    String targetId();
    boolean unresolved();
    void createIsolatedTarget();
    EquityStyleMonthlyTargetSnapshot targetSnapshot();
    List<EquityStyleMonthly> readActualRange(YearMonth from,YearMonth toInclusive);
}
