# Level2 原生 Java 管线抽样验收

日期：2026-10-07。验收数据日：2026-09-24。

后续已找到 09/28–09/30 归档并完成性能抽样与清洗准备，见
[性能与清洗准备报告](l2-performance-cleaning-20261007.md)。以下保留此前 09/24 准确性验收范围。

## 结论与范围

此前准确性验收时没有找到 09/28 归档，按会话授权使用
`D:\BaiduNetdiskDownload\202609\20260924.7z`。抽取 4 只股票和 3 只 ETF，
逐个运行原生 Java 的 CSV → 规范化成交/委托/快照 → P0–P13 → 日特征。

对照基准是 `D:\work\fund_2\back-monitor` 的
`KuakeSyncPipeline.process_symbol_date(..., load_snapshot=True)`，
版本为 `feature_version=v1.3`、`parser_version=dfcf_csv_v1.2`。
正式模型/表名为 **`l2_daily_features`**，共 110 个字段，业务键为 `(ts,symbol)`。

7 个样本均生成完整日特征，**770/770 个字段通过，差异 0**。
浮点比较使用相对误差 `1e-10`、绝对误差 `1e-9`；整数、布尔、文字、空值和字段集合严格比较。
另逐行核对规范化成交 7 字段、委托 5 字段、快照 51 字段，7 个样本差异均为 0。

此次验收覆盖这一天的这 7 个标的。产物是本地 JSONL 和既有 Java 类型模型；正式 QuestDB 入库、
整月全部标的、09/28 数据尚未在本次执行中验收。

## 样本与规范化记录数

| 标的 | 类型 | 规范化成交 | 规范化委托 | 快照 | 日特征对比 |
|---|---|---:|---:|---:|---|
| 000001.SZ | 平安银行 | 115,965 | 154,628 | 4,833 | 110/110 |
| 600000.SH | 浦发银行 | 47,397 | 61,789 | 4,968 | 110/110 |
| 300750.SZ | 宁德时代 | 198,897 | 298,492 | 4,833 | 110/110 |
| 688981.SH | 中芯国际 | 58,366 | 187,762 | 4,998 | 110/110 |
| 510300.SH | 沪深 300 ETF | 131,641 | 316,155 | 5,007 | 110/110 |
| 159915.SZ | 创业板 ETF | 310,269 | 490,531 | 4,843 | 110/110 |
| 588000.SH | 科创 50 ETF | 233,236 | 458,395 | 5,009 | 110/110 |

归档目录把 510300 和 588000 标为 `.SZ`，Java 与 Python 均按代码前缀规范为 `.SH`。
深市成交中的撤单已重建进委托流：000001 为 30,892 条，300750 为 55,599 条，159915 为 168,148 条。
沪市样本各有 5 条未知状态记录，生产投影按 Python 过滤，原始扫描保留审计记录。

## 人工检查清单

可先检查已完成的这批产物，无需等待其他迁移任务全部完成。

- [x] 日期与业务键：`ts=20260924`，每个规范代码仅一行，沪市 ETF 后缀正确。
- [x] 输入完整性：成交、委托、十档行情读取成功，日期、列宽、字节上限受控。
- [x] 委托语义：过滤未知类型，深市撤单按订单号、方向、撤单时刻之前的最近正委托价回填。
- [x] 数值单位：价格保持 Python 的两步缩放；金额、数量、盘口档位单位一致。
- [x] 同时间事件：在对应阶段复现 NumPy datetime 默认排序，避免开收盘价、替换单及 tick rule 偏差。
- [x] 特征投影：110 字段齐全，类型正确，版本明确；本批完整样本均无空值或非有限值。
- [x] 清洗结果：有效成交数、对倒金额与占比、大单计数有逐字段 Python 对照。
- [x] ETF 分支：沪市及深市 ETF 都通过全管线，未只核对 CSV 行数。
- [x] 输出完整性：批次失败不会覆盖既有文件或发布部分结果；重复规范代码被拒绝。

| 阶段 | 重点检查的数据 | 示例字段 |
|---|---|---|
| P0 | 宽表、主动方向、同向到达间隔 | `mean_rel_aggro`, `median_inter_arrival_ms` |
| P1 | 算法/散户时段及熵 | `algo_windows`, `mean_algo_entropy` |
| P2 | 对倒标记与清洗金额 | `wash_trade_ratio`, `wash_amount`, `clean_amount` |
| P3 | 撤单欺诈、假托底/假压单 | `spoof_count`, `mean_buy_otr` |
| P4 | 净流向、四象限、VWAP | `total_net_flow`, `q2_accumulation`, `mean_vwap_skew` |
| P5 | 大中小单与集合竞价资金 | `main_net_inflow`, `trade_size_gini`, `open_auction_net_inflow` |
| P6 | 价差、失衡、深度、冲击与 OFI | `mean_spread`, `mean_obi_top5`, `impact_30s`, `ofi_std` |
| P7 | 三类 GMM 行为 | `gmm_main_force_ratio`, `gmm_hft_ratio`, `gmm_retail_ratio` |
| P8 | 脉冲、护盘、沉淀及综合强度 | `mfi_pulse`, `mfi_escort`, `mfi_precip`, `mfi_score`, `ofi_slope` |
| P9 | 数据质量与订单回连 | `order_link_coverage`, `valid_spread_ratio`, `deal_order_match_ratio` |
| P10 | 订单生命周期与成交/撤单耗时 | `partial_fill_ratio`, `median_cancel_time_ms`, `replace_like_ratio` |
| P11 | 早盘、午间、尾盘分段 | `open_30m_spread_mean`, `midday_liquidity_drop` |
| P12 | 主动方向和 tick rule 验证 | `aggressor_label_match_ratio`, `tick_rule_fallback_ratio` |
| P13 | 队列耗尽、盘口恢复与衰减 | `depth_recovery_5s`, `depth_turnover_top5`, `book_pressure_decay` |

## 实现入口与重复运行

原先 `DfcfCsvParser` 的归档扫描只生成原始行，既有 D086 作业读取预生成 Parquet。
本次新增 `com.zoutrankil.batch.l2.L2DailyFeaturePipeline` 的完整本地计算路径。
Java 特征计算没有调用 Python 子进程；Python 仅用于生成独立验收基准。

API：

```java
L2DailyFeatures row = L2DailyFeaturePipeline.processSymbolDate(
    symbolDirectory, "510300.SZ", LocalDate.of(2026, 9, 24), 536870912L);
Map<String, Object> fields = L2DailyFeaturePipeline.output(row);
```

PowerShell，工作目录为本仓库，Java 24 工具链：

```powershell
.\gradlew.bat l2DailyFeatureCompute --args='--source-root var/l2-real-sample-validation/python-ref --date 20260924 --symbols 000001.SZ,600000.SH,300750.SZ,688981.SH,510300.SZ,159915.SZ,588000.SZ --output var/l2-pipeline-validation/java-daily-features.jsonl'
```

来源目录可以指向日期的父目录或日期目录本身。每个代码目录应有
`逐笔成交.csv`、`逐笔委托.csv`、`行情.csv`，编码为 GB18030。
缺失单表按 Python 的空表分支处理；三张表全部没有记录时 CLI 拒绝发布。
CLI 每文件默认上限 512 MiB、每批最多 1,000 个显式且规范后唯一的代码。
计算成功后原子替换输出文件。

## 回归与边界

`src/test/resources/l2-daily-pipeline` 固定了沪市 ETF 510300.SZ 和深市 ETF 159915.SZ
两套合成事件及原版 Python 的完整 110 字段期望值。每套有 33 个分钟区间、990 条快照，
覆盖非零对倒、撤单欺诈、部分成交、替换单、方向回退及三类 GMM 分支。
JUnit 运行不依赖本地 Python 仓库或真实行情归档。

Java 24 / Gradle 8.14.3 定向测试 **42/42 通过**：解析 13 项、CSV 检查 3 项、
归档准入及物化 10 项、现有 L2 表端口 2 项、全管线及 CLI 7 项、
GMM/MFI 边界 3 项、生命周期 2 项、同时间排序 2 项。
完整 `l2DailyFeatureCompute` Gradle 入口也以最新源码成功运行，生成 7 行；
仅计算用时约 24.5 秒（本机单次观测，不作为性能承诺）。

新增边界校验涵盖缺失收盘价、GMM 非有限派生价差及缺失价差、ETF 代码别名重复、
批次失败原子性、小数数量、原始缺失时间与撤单价回查。
数量在生产解析中保留有限小数；原始审计扫描保持 64 位整数规则。
原始缺失/错误数字按对应 Python 空值规则处理，订单号和时间仍受整数类型约束。
明确的 `inf`/`Infinity` 或转为 double 后溢出的输入被拒绝，这是有限数值入场限制；
不宣称兼容任意 pandas 非有限或超出 double 范围的输入。
结构错误、日期错误、超限文件和不能进入计算的时间会使本批失败。

最终字段仍复用既有 `L2DailyFeatureField` / `L2DailyFeatures`，不另建特征字段定义或表。
以后 Python 特征版本、NumPy 排序或 sklearn 默认值改变时，应重新生成基准并复核，
不能仅依据这次抽样结果沿用验收结论。

## 验收证据

最终生成行、完整差异报告、逐行解析报告、参考源码 SHA256 和执行摘要保存在
`artifacts/l2-native-validation/20260924`。
执行中间产物及诊断脚本保存在 `var/l2-pipeline-validation` 和
`var/l2-real-sample-validation`。本文件取代初始仅检查原始解析的
`var/l2-real-sample-validation/report-20260924.md` 中“完整管线尚未验收”的旧结论。
