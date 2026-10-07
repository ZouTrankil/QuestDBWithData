package com.zoutrankil.data.stock.port;

import com.zoutrankil.data.domain.table.StockDetailInfoRow;
import com.zoutrankil.data.stock.domain.StockDetailState.Identity;
import java.util.List;

/** Bounded name reference reads with physical-generation identity checks. */
public interface StockDetailNameReadPort {
    String targetId();
    String identify(Identity identity);
    Session openSession();
    interface Session {
        Identity preflight();
        List<StockDetailInfoRow> readKeys(List<String> codes);
    }
}
