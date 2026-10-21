# BTC 数据工程任务

先把六组 BTCUSDT 数据做成能直接研究的输入，暂不做策略、网格、OI、链上或订单簿。

范围：2020-01-01 至 2026-10-19（UTC），程序内部为 `[2020-01-01T00:00:00Z, 2026-10-20T00:00:00Z)`。每组按真实可用起点记录，缺少的历史不填造。后续更新另行固定截止日。

## 六组数据

路径均在 `https://data.binance.vision/data/` 下。下表是待样本验证的月包目录，不代表本次已下载或验收；日包能力逐组核实。

| ID | 数据 | 月包路径 |
| --- | --- | --- |
| D01 | 现货 1d | `spot/monthly/klines/BTCUSDT/1d/` |
| D02 | 现货 1h | `spot/monthly/klines/BTCUSDT/1h/` |
| D03 | U 本位永续 1h | `futures/um/monthly/klines/BTCUSDT/1h/` |
| D04 | 资金费率事件 | `futures/um/monthly/fundingRate/BTCUSDT/` |
| D05 | 标记价格 1h | `futures/um/monthly/markPriceKlines/BTCUSDT/1h/` |
| D06 | 指数价格 1h | `futures/um/monthly/indexPriceKlines/BTCUSDT/1h/` |

## 按这 11 件事做

不机械拆成 18 个阶段任务：六组数据各有一份任务，下载、修订、对账和读取共用。

| 任务 | 完成后得到什么 |
| --- | --- |
| [00 核对现有代码和官方样本](00-核对现有代码和官方样本.md) | 六组真实样本、字段约定和旧代码缺项 |
| [01 下载官方归档并校验](01-下载官方归档并校验.md) | 可恢复的 ZIP/CHECKSUM 下载与来源记录 |
| [02 采集 BTC 现货日线](02-采集现货日线.md) | 完整字段的官方现货日线 |
| [03 采集 BTC 现货小时线](03-采集现货小时线.md) | 完整字段的现货小时线 |
| [04 采集 BTC 永续合约小时线](04-采集永续合约小时线.md) | 独立的永续成交小时线 |
| [05 采集 BTC 历史资金费率](05-采集历史资金费率.md) | 真实结算事件和资金费率 |
| [06 采集 BTC 标记价格小时线](06-采集标记价格小时线.md) | 独立的标记价格小时线 |
| [07 采集 BTC 指数价格小时线](07-采集指数价格小时线.md) | 独立的指数价格小时线 |
| [08 补新数据和处理历史修订](08-补新数据和处理历史修订.md) | 能补新数据，也能发现并处理官方修订 |
| [09 核对六组数据是否能一起用](09-核对六组数据是否能一起用.md) | 可解释的范围、缺口与跨表对账报告 |
| [10 导出 Parquet 并统一读取](10-导出Parquet并统一读取.md) | 六组 Parquet、统一取数和 DuckDB 示例 |

先做 00、01，再做现货 02、03。其余四组逐项接入；每组可先交付已核验部分，不必等六组都完成。08 负责后续更新，09 和 10 随数据集逐步验收。00–10 首版已实施。六组已按真实来源范围写入远端 QuestDB，并完成全范围对账及研究导出。来源缺口、4 天成交字段冲突、改键修订和定时部署单列待处理，不标为齐全。

## 沿用什么

已有 [BTC 采集入口](../../../markets/btc/data-app/src/main/java/com/zoutrankil/market/btc/app/CryptoDataMain.java)、归档下载、CHECKSUM、UTC 转换和恢复队列，应复用并补缺。现有小时线还会聚合日线，不能把它当成已经有独立原生日线；完整原始字段、资金费率、标记价和指数价也要单独核对。

本仓继续由 Java 负责正式采集和写入，Python 用于研究、只读取数及派生研究文件。不新建重复的 `crypto-data` 正式下载工程。Parquet 和 DuckDB 用于研究读取；正式数据仍按已有存储和恢复约定处理。若以后改为纯 Parquet 正式存储，应另定迁移，不在本计划里暗中切换。

配置只放根 `config/`，扩展 [BTC 应用配置](../../../config/apps/btc-data.yml)，不另建 `configs/datasets.yaml`。新增市场模型放 `markets/btc`，公共层不引入市场业务。

## 几条不能弄错

- 原 ZIP 全留；标准 K 线保留全部有效字段，不只留七列。来源数值先按原精度保存，再做明确的类型转换。
- K 线逻辑时间用 `open_time`，结算用 `funding_time`；都是 UTC 真实时刻，不统一叫 `trade_date`。已有 `ts` 等字段通过明确映射兼容。
- 键必须包含交易所、币对、产品和周期；价格数据还要区分成交、标记和指数。来源摘要属于版本，不作为躲避同键去重的维度。
- 数据收盘时刻、结算时刻不等于真实可用时刻；下载时间也不能证明历史上当时已知。无可用时间证据时写明研究限制。
- 不改变旧请求、物理目标或恢复归属；结果未决不自动重发或解锁。正式写入仍做完整键值回读、WAL 和稳定版本核对，不能拿 ZIP 校验代替。

现有默认起点是 2021-10-01，必须显式扩到本计划范围。若新增模块或依赖，按项目要求检查架构、模块边界、配置加载和发行包。改动用相关样本测试，真实六组覆盖另验。本轮已下载和解析 24 个官方样本，并新建六张远端产品表；未改旧表。实际覆盖和缺项看队列及审计报告。

## 官方依据

- [Binance 公开数据说明](https://github.com/binance/binance-public-data)：现货自 2025-01-01 起使用微秒；日/月文件发布、CHECKSUM 和历史修订规则以此为准。
- [标记价归档下载示例](https://github.com/binance/binance-public-data/blob/master/python/download-futures-markPriceKlines.py)、[指数价归档下载示例](https://github.com/binance/binance-public-data/blob/master/python/download-futures-indexPriceKlines.py)：可用于核对专用目录与文件名。
- [Binance U 本位市场数据文档](https://developers.binance.com/en/docs/catalog/core-trading-derivatives-trading-usd-s-m-futures/api/rest-api/market-data)：标记/指数 K 线有占位字段；资金费率事件和结算间隔分别定义，不能固定套八小时。

官方文档核对日期：2026-10-20。实际文件的字段和可用范围仍由任务 00 验证。

## 本轮记录

- [样本结果](../../audits/2026-10-20-btc-archive-probes.json)：六组各取 2020-01、2024-12、2025-01、2026-09。
- [运行和恢复说明](../../btc-data.md)：命令、状态、原文和剩余限制。
- 卡住的数据单列后继续；未决写入保留原运行，不通过跳过来解除锁。

- [本轮验收结果](../../audits/2026-10-21-btc-数据工程验收.md)：六表行数、缺口、差异和恢复记录。
