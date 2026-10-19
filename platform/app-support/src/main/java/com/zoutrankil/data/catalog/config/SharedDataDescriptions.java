package com.zoutrankil.data.catalog.config;

import com.zoutrankil.data.catalog.domain.DataObjectDescription;
import com.zoutrankil.data.catalog.domain.DataObjectDescription.SourceType;
import java.util.List;
import java.util.Set;
import static com.zoutrankil.data.catalog.domain.CatalogTag.*;

/** 维护业务说明与多个标签；对象是否存在、行数和日期始终读取实库。 */
public final class SharedDataDescriptions {
    private SharedDataDescriptions() {}
    public static List<DataObjectDescription> all() {
        return List.of(
            new DataObjectDescription("eco_cal", "全球财经事件、实际值、前值及预期值", "全球财经事件、实际值、前值及预期值", Set.of(DataObjectDescription.Market.SHARED),
                    "全球宏观事件", SourceType.COLLECTED, "Tushare eco_cal", List.of(),
                    "来源采集/文件导入", "含国家/货币维度，供不同市场共享。", "项目外部源合同及数据模型；实库 tables()", Set.of(COLLECTED, EVENT, MACRO, SHARED)),
            new DataObjectDescription("exchange_calendar", "交易所开闭市及前一交易日日历", "交易所开闭市及前一交易日日历", Set.of(DataObjectDescription.Market.SHARED),
                    "日历", SourceType.COLLECTED, "Tushare交易日历接口", List.of(),
                    "来源采集/文件导入", "", "项目外部源合同及数据模型；实库 tables()", Set.of(CALENDAR, COLLECTED, SHARED)),
            new DataObjectDescription("factor_catalog", "因子目录/定义发布记录", "因子目录/定义发布记录", Set.of(DataObjectDescription.Market.SHARED),
                    "因子/模型平台", SourceType.GENERATED, "价格、财务、宏观及L2等输入的因子计算、定义登记和评价任务", List.of(),
                    "应用计算、登记或记录", "跨资产共用结构；不根据表名将整表限定为某个市场。部分产品当前使用本地发布。", "项目规范化因子/模型合同及存储归档清单；实库 tables()", Set.of(FACTOR, GENERATED, MODEL, SHARED)),
            new DataObjectDescription("factor_corr_snapshot", "因子相关性及冗余快照", "因子相关性及冗余快照", Set.of(DataObjectDescription.Market.SHARED),
                    "因子/模型平台", SourceType.GENERATED, "价格、财务、宏观及L2等输入的因子计算、定义登记和评价任务", List.of(),
                    "应用计算、登记或记录", "跨资产共用结构；不根据表名将整表限定为某个市场。部分产品当前使用本地发布。", "项目规范化因子/模型合同及存储归档清单；实库 tables()", Set.of(FACTOR, GENERATED, MODEL, SHARED)),
            new DataObjectDescription("factor_daily_metric", "日频因子评价指标", "日频因子评价指标", Set.of(DataObjectDescription.Market.SHARED),
                    "因子/模型平台", SourceType.GENERATED, "价格、财务、宏观及L2等输入的因子计算、定义登记和评价任务", List.of(),
                    "应用计算、登记或记录", "Java 仍有正式写入和读回；Python 部分读取路由至本地 SQLite，不能作为无用归档删除。两侧存储职责和历史覆盖需核对。", "Java 正式因子读写实现、Python 读取路由及实库元数据", Set.of(FACTOR, GENERATED, MODEL, SHARED)),
            new DataObjectDescription("factor_definition", "因子的版本化定义", "因子的版本化定义", Set.of(DataObjectDescription.Market.SHARED),
                    "因子/模型平台", SourceType.GENERATED, "价格、财务、宏观及L2等输入的因子计算、定义登记和评价任务", List.of(),
                    "应用计算、登记或记录", "跨资产共用结构；不根据表名将整表限定为某个市场。部分产品当前使用本地发布。", "项目规范化因子/模型合同及存储归档清单；实库 tables()", Set.of(FACTOR, GENERATED, MODEL, SHARED)),
            new DataObjectDescription("factor_dependency", "因子定义依赖关系", "因子定义依赖关系", Set.of(DataObjectDescription.Market.SHARED),
                    "因子/模型平台", SourceType.GENERATED, "价格、财务、宏观及L2等输入的因子计算、定义登记和评价任务", List.of(),
                    "应用计算、登记或记录", "跨资产共用结构；不根据表名将整表限定为某个市场。部分产品当前使用本地发布。", "项目规范化因子/模型合同及存储归档清单；实库 tables()", Set.of(FACTOR, GENERATED, MODEL, SHARED)),
            new DataObjectDescription("factor_ic_daily", "历史按运行批次记录的日频IC证据", "历史按运行批次记录的日频IC证据", Set.of(DataObjectDescription.Market.SHARED),
                    "因子/模型平台", SourceType.GENERATED, "价格、财务、宏观及L2等输入的因子计算、定义登记和评价任务", List.of(),
                    "应用计算、登记或记录", "项目模型明确标记为已退休的历史运行证据；当前仅归档用途。", "项目规范化因子/模型合同及存储归档清单；实库 tables()", Set.of(FACTOR, GENERATED, MODEL, SHARED)),
            new DataObjectDescription("factor_materialization_run", "历史因子物化运行回执", "历史因子物化运行回执", Set.of(DataObjectDescription.Market.SHARED),
                    "因子/模型平台", SourceType.GENERATED, "价格、财务、宏观及L2等输入的因子计算、定义登记和评价任务", List.of(),
                    "应用计算、登记或记录", "项目模型明确标记为已退休的历史运行证据；当前仅归档用途。", "项目规范化因子/模型合同及存储归档清单；实库 tables()", Set.of(FACTOR, GENERATED, MODEL, SHARED)),
            new DataObjectDescription("factor_monitor_daily", "因子日频有效性监控", "因子日频有效性监控", Set.of(DataObjectDescription.Market.SHARED),
                    "因子/模型平台", SourceType.GENERATED, "价格、财务、宏观及L2等输入的因子计算、定义登记和评价任务", List.of(),
                    "应用计算、登记或记录", "跨资产共用结构；不根据表名将整表限定为某个市场。部分产品当前使用本地发布。", "项目规范化因子/模型合同及存储归档清单；实库 tables()", Set.of(FACTOR, GENERATED, MODEL, SHARED)),
            new DataObjectDescription("factor_monitor_summary", "滚动因子健康汇总", "滚动因子健康汇总", Set.of(DataObjectDescription.Market.SHARED),
                    "因子/模型平台", SourceType.GENERATED, "价格、财务、宏观及L2等输入的因子计算、定义登记和评价任务", List.of(),
                    "应用计算、登记或记录", "跨资产共用结构；不根据表名将整表限定为某个市场。部分产品当前使用本地发布。", "项目规范化因子/模型合同及存储归档清单；实库 tables()", Set.of(FACTOR, GENERATED, MODEL, SHARED)),
            new DataObjectDescription("factor_observation", "计算后的规范化因子观测值", "计算后的规范化因子观测值", Set.of(DataObjectDescription.Market.SHARED),
                    "因子/模型平台", SourceType.GENERATED, "价格、财务、宏观及L2等输入的因子计算、定义登记和评价任务", List.of(),
                    "应用计算、登记或记录", "Java 仍有正式读写任务；Python 还会组合读取 QuestDB 历史与本地分段，不能作为无用归档删除。自动调度和历史覆盖另行核验。", "Java 正式因子读写实现、Python 读取路由及实库元数据", Set.of(FACTOR, GENERATED, MODEL, SHARED)),
            new DataObjectDescription("factor_platform_run", "因子平台执行及覆盖窗口", "因子平台执行及覆盖窗口", Set.of(DataObjectDescription.Market.SHARED),
                    "因子/模型平台", SourceType.GENERATED, "价格、财务、宏观及L2等输入的因子计算、定义登记和评价任务", List.of(),
                    "应用计算、登记或记录", "跨资产共用结构；不根据表名将整表限定为某个市场。部分产品当前使用本地发布。", "项目规范化因子/模型合同及存储归档清单；实库 tables()", Set.of(FACTOR, GENERATED, MODEL, SHARED)),
            new DataObjectDescription("factor_regime_daily", "因子有效性状态及动态权重", "因子有效性状态及动态权重", Set.of(DataObjectDescription.Market.SHARED),
                    "因子/模型平台", SourceType.GENERATED, "价格、财务、宏观及L2等输入的因子计算、定义登记和评价任务", List.of(),
                    "应用计算、登记或记录", "跨资产共用结构；不根据表名将整表限定为某个市场。部分产品当前使用本地发布。", "项目规范化因子/模型合同及存储归档清单；实库 tables()", Set.of(FACTOR, GENERATED, MODEL, SHARED)),
            new DataObjectDescription("factor_registry", "运行批次的因子选择及证据登记", "运行批次的因子选择及证据登记", Set.of(DataObjectDescription.Market.SHARED),
                    "因子/模型平台", SourceType.GENERATED, "价格、财务、宏观及L2等输入的因子计算、定义登记和评价任务", List.of(),
                    "应用计算、登记或记录", "跨资产共用结构；不根据表名将整表限定为某个市场。部分产品当前使用本地发布。", "项目规范化因子/模型合同及存储归档清单；实库 tables()", Set.of(FACTOR, GENERATED, MODEL, SHARED)),
            new DataObjectDescription("factor_usage", "策略/研究/模型使用因子的记录", "策略/研究/模型使用因子的记录", Set.of(DataObjectDescription.Market.SHARED),
                    "因子/模型平台", SourceType.GENERATED, "价格、财务、宏观及L2等输入的因子计算、定义登记和评价任务", List.of(),
                    "应用计算、登记或记录", "跨资产共用结构；不根据表名将整表限定为某个市场。部分产品当前使用本地发布。", "项目规范化因子/模型合同及存储归档清单；实库 tables()", Set.of(FACTOR, GENERATED, MODEL, SHARED)),
            new DataObjectDescription("factor_validation_daily", "因子验证日频指标", "因子验证日频指标", Set.of(DataObjectDescription.Market.SHARED),
                    "因子/模型平台", SourceType.GENERATED, "价格、财务、宏观及L2等输入的因子计算、定义登记和评价任务", List.of(),
                    "应用计算、登记或记录", "跨资产共用结构；不根据表名将整表限定为某个市场。部分产品当前使用本地发布。", "项目规范化因子/模型合同及存储归档清单；实库 tables()", Set.of(FACTOR, GENERATED, MODEL, SHARED)),
            new DataObjectDescription("prediction_definition_v1", "模型输出/预测的版本化定义", "模型输出/预测的版本化定义", Set.of(DataObjectDescription.Market.SHARED),
                    "因子/模型平台", SourceType.GENERATED, "价格、财务、宏观及L2等输入的因子计算、定义登记和评价任务", List.of(),
                    "应用计算、登记或记录", "跨资产共用结构；不根据表名将整表限定为某个市场。部分产品当前使用本地发布。", "项目规范化因子/模型合同及存储归档清单；实库 tables()", Set.of(FACTOR, GENERATED, MODEL, SHARED))
        );
    }
}
