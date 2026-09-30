# Q 对象研究用途与落库处置

审查日期：2026-09-29。只读检查 Java 迁移计划、现场 QuestDB schema 导出，以及 `/Users/Apple/zq/fun/back-monitor` 当前 Python 数据/研究源码。现场 schema 中没有这 14 个同名对象；以下是“是否保留为待落库候选”的处置，不是对已存在 QuestDB 表执行 DROP，也不删除 Python 源码或研究计算。

## 处置结果

14 项中保留 3 项 Alpha Source registry 候选；另外 11 项退出活跃落库候选。任务卡和研究源码仍保留作审计/兼容依据，避免抹掉历史。

| ID / 对象 | 当前研究用途和源码证据 | 落库决定 |
|---|---|---|
| Q001 `alpha_research_admission_v1` | Alpha Breadth 构建过程读取 admission，并按 `approved_at`、`approved_by`、PIT policy 判断来源是否可用于研究；研究 registry adapter 明确提供写/读该表。 | **保留待准入**。研究决策和 as-of 审批证据有明确用途。 |
| Q002 `alpha_source_definition_v1` | Alpha Breadth registry 按 `effective_from/effective_to` 选择当前来源版本；`alpha_breadth.py` 有持久化读写实现。 | **保留待准入**。 |
| Q003 `alpha_source_member_v1` | Alpha Breadth registry 按 `valid_from/valid_to` 选择来源包含的 factor/variant，处理成员替换；有持久化读写实现。 | **保留待准入**。 |
| Q004 `eco_cal_daily_agg` | `EcoCalendarQuantifier` 有事件 surprise/importance/impact 计算，但没有该表的写入者或消费者；这是未接入的结果模型。 | **移出落库候选**。保留 `eco_cal` 原始输入和研究接口中的计算，不建日聚合表。 |
| Q005 `eco_cal_quantified` | 同上；量化结果由 `quantify_eco_cal_df()` 内存计算返回，没有该持久化表的实际读写链路。 | **移出落库候选**。保留计算能力，不建结果表。 |
| Q006 `factor_exposure_snapshot_daily` | 仅发现 Pydantic/schema 模型声明，没有同步函数、发布器或研究消费者。 | **移出落库候选**。未来若有具体 exposure 生产者和 PIT 消费者，再重新申请。 |
| Q007 `factor_lifecycle_event` | Python repository 的 `write_lifecycle_events()` 明确走 `_retired_write()`，提示该对象只作历史只读兼容，正式发布不再写入。 | **移出落库候选**。不为已退役写入创建新表。 |
| Q008 `index_daily` | 旧 `index_market_sync.py` 的直接脚本写该表，但不在当前 connector registry；现有 connector 使用 `index_daily_market`，迁移计划 D019 已覆盖该数据。研究场景仍有旧表名引用。 | **移出独立表候选，归并 D019**。切换时需把旧表名消费者改到 canonical dataset，不能双建双写。 |
| Q009 `macro_bond_yield` | 旧 `index_market_sync.py` 写入 10Y 曲线简表，但该同步模块不在当前 connector registry；当前宏观物化/研究读取 `cn_bond_yield_curve`（D060）。列粒度不同，合并前需核对 10Y 映射。 | **移出独立表候选，归并 D060 的来源/投影核对**。不要另建第二个未消费表。 |
| Q010 `macro_news` | 仅发现模型声明，未发现 connector、QuestDB repository 或研究消费者。 | **移出落库候选**。 |
| Q011 `prediction_observation_v1` | 有通用 schema/发布接口和未绑定的 prediction catalog，但没有具体模型绑定、生产发布调用方或下游消费者。 | **移出当前落库候选**。出现已批准模型及真实消费方后再重新准入。 |
| Q012 `report_rc` | 有 Tushare provider 和 `FinancialDataApplication.get_report_rc()` 读取能力，但未发现研究模块调用或独立同步注册。可按需通过 provider 查询。 | **移出当前落库候选，保留 provider/read API**。若后续证明确有历史研究查询需求，再单独评估缓存表。 |
| Q013 `stock_news` | 仅发现模型声明；未发现新闻抓取 connector、写入 repository 或研究消费者。 | **移出落库候选**。 |
| Q014 `swan_industry` | 仅发现静态模型声明，没有同步/读写调用方。现有 `index_member` 已处理申万行业成分；stock detail 另有行业字段。 | **移出独立表候选**。行业成员用 `index_member`，静态映射若有缺口在该 canonical 来源上补契约。 |

## 计划状态

- 活跃专项准入从 14 项缩为 Q001–Q003 三项。
- Q004–Q014 共 11 项标记为 `retired_with_evidence`，不再出现在活跃准入顺序；历史 task card 保留但禁止据此创建对象。
- D019 (`index_daily_market`) 和 D060 (`cn_bond_yield_curve`) 仍是各自 canonical 数据迁移任务；合并不代表它们已在 Java 完成。
- Q012 的 provider API 和 Q004/Q005 的内存计算保留；移除的是目前无消费者依据的独立落库目标。
- 本次处置已应用到迁移计划；未来若出现新的生产者和研究消费者证据，可另开准入评估。现场没有这14个同名对象，因此物理 DROP 无目标。

## 查阅依据

- Alpha Source：Python `data/adapters/questdb/alpha_breadth.py`、`data/application/product_definitions/alpha_breadth.py`、`research/application/alpha_breadth_study.py`、`research/engines/alpha_source_registry.py`。
- 经济日历：Python `research/application/eco_calendar.py`、`research/interfaces/eco_calendar.py`、`data/adapters/questdb/models/macro/eco_cal.py`。
- Factor lifecycle/prediction：Python `data/adapters/questdb/factor_data_product.py`、`data/adapters/questdb/models/factor_data_product.py`、`data/application/factor_data_product.py`。
- 指数/债券：Python connector registry、`connectors/index/index_market_sync.py`、`connectors/index/index_daily_market_sync.py`、`data/adapters/materializers/registry.py`。
- News、factor exposure、Swan Industry：分别检查 Python `models/news.py`、`models/macro/factor_exposure_snapshot_daily.py`、`models/stock/fundamental.py` 全仓调用。
- ReportRc：Python `data/application/financial_data.py`、`data/adapters/providers/financial_data.py`、`data/adapters/providers/fina_client.py`。
