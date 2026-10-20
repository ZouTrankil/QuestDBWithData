package com.zoutrankil.data.financial.port;

import com.zoutrankil.data.financial.domain.FinancialContract.Key;
import com.zoutrankil.data.financial.domain.FinancialContract.Row;
import com.zoutrankil.data.service.VerifiedBatchExecutor;

import java.util.List;

public interface FinancialTarget extends VerifiedBatchExecutor.Port<Row, Key> {
    String targetId() throws Exception;

    default List<Row> preserveLocalColumns(List<Row> rows) throws Exception {
        return rows;
    }
}
