package com.zoutrankil.data.financial.port;
import com.zoutrankil.data.financial.domain.FinancialContract.*;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
public interface FinancialTarget extends VerifiedBatchExecutor.Port<Row,Key> {
    String targetId() throws Exception;
    default java.util.List<Row> preserveLocalColumns(java.util.List<Row> rows)throws Exception{return rows;}
}
