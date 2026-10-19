package com.zoutrankil.market.us.catalog;

import com.zoutrankil.data.catalog.domain.DataObjectDescription;
import com.zoutrankil.data.catalog.domain.DataObjectDescription.SourceType;
import java.util.List;
import java.util.Set;
import static com.zoutrankil.data.catalog.domain.CatalogTag.*;

/** 维护业务说明与多个标签；对象是否存在、行数和日期始终读取实库。 */
public final class UsDataDescriptions {
    private UsDataDescriptions() {}
    public static List<DataObjectDescription> all() {
        return List.of(
            new DataObjectDescription("us_tbr", "美国短期国债利率", "美国短期国债利率", Set.of(DataObjectDescription.Market.US),
                    "美国国债/利率", SourceType.COLLECTED, "Tushare 同名美国利率接口", List.of(),
                    "来源采集/文件导入", "", "项目外部源合同及数据模型；实库 tables()", Set.of(COLLECTED, DAILY, MACRO, RATES, US)),
            new DataObjectDescription("us_tltr", "美国长期国债利率", "美国长期国债利率", Set.of(DataObjectDescription.Market.US),
                    "美国国债/利率", SourceType.COLLECTED, "Tushare 同名美国利率接口", List.of(),
                    "来源采集/文件导入", "", "项目外部源合同及数据模型；实库 tables()", Set.of(COLLECTED, DAILY, MACRO, RATES, US)),
            new DataObjectDescription("us_trltr", "美国实际长期利率平均值", "美国实际长期利率平均值", Set.of(DataObjectDescription.Market.US),
                    "美国国债/利率", SourceType.COLLECTED, "Tushare 同名美国利率接口", List.of(),
                    "来源采集/文件导入", "", "项目外部源合同及数据模型；实库 tables()", Set.of(COLLECTED, DAILY, MACRO, RATES, US)),
            new DataObjectDescription("us_trycr", "美国实际国债收益率曲线", "美国实际国债收益率曲线", Set.of(DataObjectDescription.Market.US),
                    "美国国债/利率", SourceType.COLLECTED, "Tushare 同名美国利率接口", List.of(),
                    "来源采集/文件导入", "", "项目外部源合同及数据模型；实库 tables()", Set.of(COLLECTED, DAILY, MACRO, RATES, US)),
            new DataObjectDescription("us_tycr", "美国国债收益率曲线", "美国国债收益率曲线", Set.of(DataObjectDescription.Market.US),
                    "美国国债/利率", SourceType.COLLECTED, "Tushare 同名美国利率接口", List.of(),
                    "来源采集/文件导入", "", "项目外部源合同及数据模型；实库 tables()", Set.of(COLLECTED, DAILY, MACRO, RATES, US)),
            new DataObjectDescription("us_market_daily", "纳斯达克综合、标普500及VIX日收盘序列", "纳斯达克综合、标普500及VIX日收盘序列", Set.of(DataObjectDescription.Market.US),
                    "美股指数/波动率", SourceType.COLLECTED, "FRED：NASDAQCOM、SP500、VIXCLS", List.of(),
                    "来源采集/文件导入", "本表是指数与波动率数据，不是美股个股行情。", "实库序列代码和 source_id/source_url；项目 UsMarketDaily 模型", Set.of(COLLECTED, DAILY, INDEX, US))
        );
    }
}
