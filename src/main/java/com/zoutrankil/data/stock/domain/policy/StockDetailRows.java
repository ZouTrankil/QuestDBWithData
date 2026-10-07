package com.zoutrankil.data.stock.domain.policy;
import com.zoutrankil.data.domain.StockDetailInfo;
import com.zoutrankil.data.domain.table.StockDetailInfoRow;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import java.time.LocalDate;
/** Legacy physical reference values decoded without changing their business meaning. */
public final class StockDetailRows {
    private StockDetailRows() {}
    public static StockDetailInfo fromStorage(StockDetailInfoRow r) {
        return new StockDetailInfo(r.tsCode(),r.updateTime(),r.symbol(),r.name(),r.market(),r.exchange(),r.listStatus(),
                legacyDate(r.listDate()),r.fullname(),r.enname(),r.cnspell(),r.area(),r.industry(),r.currType(),
                legacyDate(r.delistDate()),r.isHs(),r.actName(),r.actEntType());
    }
    private static LocalDate legacyDate(String value) {
        return "None".equals(value)||value==null||value.isEmpty()?null:TemporalValues.businessDate(value,TemporalValues.DateFormat.BASIC);
    }
}
