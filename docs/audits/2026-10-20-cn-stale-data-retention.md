# 长期未更新采集表的保留与清理核查

先核查上一轮业务日期落后至少 60 天的 6 张表。目前没有一张满足直接删除条件。`margin_zrz` 适合转为历史归档；`margin_trading` 可评估清洗后退役，但当前没有被 `margin_detail` 完整替代。其余 4 张保留，是否恢复更新按实际消费者和来源能力决定。

核查日期：2026-10-20。最新日期和行数沿用[实库新鲜度核查](2026-10-20-cn-collected-data-freshness.md)，本轮补查当前 Java 工程、相邻 `back-monitor` 工程、实库 MV/视图定义和两融数据的键值差异。没有删除、归档或触发同步。

## 六张表的判断

| 表 | 最新业务日期 | 行数快照 | 用处与证据 | 处理建议 |
| --- | --- | ---: | --- | --- |
| `margin_zrz` | 2025-07-25 | 1,238 | 转融资历史汇总。Java owner 已明确退役、手动调用且不进入日调度；Python 配置也标注退役历史只读 | 历史归档候选，不再当作日更故障催补。归档和历史读取安排完成后，可评估退出在线库；目前不直接删 |
| `margin_trading` | 2026-03-11 | 181,140 | 本地 CSV 导入的两融历史。当前两工程只发现模型/目录登记，没有找到直接业务 SQL 消费；但完整对照发现另一表缺键及值差异 | 保留原始历史，先查代码后缀、来源和差异。处理完替代与归档后再评估退役，不能按重复表删除 |
| `margin_all` | 2026-03-19 | 4,722 | 交易所维度融资融券汇总；Java 有读取与同步契约，Python 有通用只读产品登记。不是个股明细的同一数据产品 | 保留。是否日更按研究/市场监控需要确认；未证明用明细聚合能完整替代 |
| `moneyflow_dc` | 2026-03-20 | 3,478,841 | 东财口径资金流；Python 有具体查询和排名服务，Java 有独立来源与隔离验收实现 | 保留。配置默认关闭不等于没有手工消费者；不能由 Tushare/THS 资金流表直接替代 |
| `qmt_1m_bars` | 2026-07-06 | 4,873 | 执行分析的 VWAP/TWAP、行情动量、T0 状态读取和 canary 命令都有明确 SQL 路径 | 保留，先查 QMT 写入是否仍需要。部分 T0 读取会拒绝超过 5 分钟的旧行情；删除不能修复数据过旧问题 |
| `index_daily_basic` | 2026-08-03 | 42,588 | 指数每日估值等指标；Java 有来源合同、同步 CLI 与读仓库，Python 保留模型 | 保留，可降低补数优先级。当前未找到主策略评分的直接引用，但不能因此认定指数估值历史无用 |

行数不是存储体积，也不证明数据质量。代码中存在读取路径不证明当前进程实际调用；配置 `enabled: false` 也不证明所有手工或外部消费者都已停用。

## 转融资不是普通漏同步

`MarginZrzJobService` 将最后已审计来源日限定为 `2025-07-25`，`MarginZrzSyncJobOwner` 明确是禁用日调度的退役历史源，只允许有界历史恢复。相邻 Python 配置对 `margin_zrz` 的描述也是“已退役来源，历史只读”。

因此上一轮的 452 天只表示数据日期距今，不能解释成应该补齐 452 天。这里确认的是项目处理约定，没有重新验证供应商当前是否继续提供该接口。只有 1,238 行，优先腾挪这张表的收益也不能单凭行数推断。

证据：[Java 历史任务](../../markets/cn/data/src/main/java/com/zoutrankil/data/margin/application/MarginZrzJobService.java)、[owner](../../markets/cn/data/src/main/java/com/zoutrankil/data/margin/application/MarginZrzSyncJobOwner.java)、[Python 只读产品配置](../../../back-monitor/config/yaml/data/questdb_read_products.yaml)。

## 本地两融表尚未被完整替代

`margin_trading` 的实库范围为 `2026-01-05` 至 `2026-03-11`，共 181,140 行。`margin_detail` 的整体范围虽更长、最新到 `2026-10-16`，也不能因此认定它完整包含旧表。

本轮在旧表完整日期范围内，按 `(trade_date, ts_code)` 关联，逐行比较 `rzye`、`rzmre`、`rzche`、`rqyl`、`rqmcl`、`rqchl`、`rqye`、`rzrqye`，保留空值差别，结果如下：

| 检查项 | 数量 |
| --- | ---: |
| 比较的旧表行 | 181,140 |
| 在 `margin_detail` 未匹配的键 | 25,041 |
| 已匹配但至少一个数值不同的行 | 157 |
| 已匹配且比较字段一致的行 | 155,942 |
| 两表在该范围内各自重复业务键 | 均为 0 |

未匹配样本包含 `510050.SZ`、`510100.SZ`、`510150.SZ` 等，须核对本地来源的证券范围和交易所后缀。未匹配不能直接当作有效独有数据，也不能未经验证就改后缀后删除。157 行差异还需要结合原 CSV、来源单位和修订规则解释。

后续先保留原文件和表，建立明确的代码映射与差异处理记录，再决定保留哪些行、迁往哪里。比较查询不是冻结来源与稳定 WAL 的迁移验收，真正清理前仍需重查原始证据、版本和恢复归属。

证据：[完整比较 SQL 与结果](2026-10-20-margin-trading-retention-parity.json)、[本地 CSV 模型说明](../../../back-monitor/src/quant_platform/data/adapters/questdb/models/stock/market_data.py)。

## 仍有业务读取路径的表

`qmt_1m_bars` 的消费者最明确，不能仅凭主策略日频评分没读它就删除：

- [执行市场数据适配器](../../../back-monitor/src/quant_platform/best_trader/adapters/execution_market_data.py)直接查询分钟线计算 VWAP/TWAP 和价格。
- [行情监控](../../../back-monitor/src/quant_platform/best_trader/application/market_monitor.py)使用分钟线计算动量。
- [T0 状态服务](../../../back-monitor/src/quant_platform/best_trader/application/t0_action_value_service.py)读取分钟 OHLCV，并对超过 5 分钟的旧状态返回空结果。

`moneyflow_dc` 有[查询仓库](../../../back-monitor/src/quant_platform/data/adapters/questdb/moneyflow.py)及[资金流应用服务](../../../back-monitor/src/quant_platform/data/application/moneyflow.py)中的查询、排名入口。它与 `moneyflow`、`moneyflow_ths` 来自不同口径，不能只因都叫资金流就合并删除。

`margin_all` 和 `index_daily_basic` 有明确产品或来源接口，但本轮没有找到它们直接进入当前主策略评分的证据。可先保留历史、暂停盲目补数；有真实消费者需求后再完成正式来源接管。Java 部分默认目标仍是隔离表，存在代码实现不代表正式表已有持续 writer。

## 删除前还缺什么

本轮读取实库 4 个原生 MV 和 1 个普通视图定义，没有发现对这 6 张表的引用；这不覆盖应用动态 SQL、用户脚本和其他机器上的消费者。

本地 `var/sync-ledger.sqlite3` 当前只有调度、MV 证书和旧元数据退休记录等表，没有 `sync_runs`、`sync_entries` 或锁表。因此未完成六表的正式运行账本和未决状态核查，不能宣称没有恢复依赖。

实际退出在线库前，需要完成消费者停用或替代、原始来源与全字段归档、摘要与恢复演练、原物理身份及正式账本核查，再移除目录和读取绑定。归档必须保留原始业务键、字段值、代码映射和来源说明。对 `margin_trading` 还要解决上述缺键与差异，不以范围覆盖或少量相同样本代替完整验证。

本轮结论是：优先处理退役源的历史定位，再清理本地两融的替代关系；其余四表按用途保留。没有授权或执行任何删表操作。
