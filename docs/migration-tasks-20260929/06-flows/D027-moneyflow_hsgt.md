# D027 · moneyflow_hsgt

- 状态：implemented_not_verified；D027业务实现与定向javac已完成，真实来源和隔离QuestDB验收待串行执行。
- 工作区：`C:/Users/zouqiang/IdeaProjects/QuestDBWithData`。
- Python项目目录（持续只读查找）：`D:/work/fund_2/back-monitor`；同步配置、connectors、模型、读写SQL和测试均可沿实际调用链检索。
- 串行前置：`D026`；实现可并行准备，实际验收须遵守协调器的串行配额顺序。
- 数据对象：`moneyflow_hsgt`。
- 业务依赖：本卡来源与公共功能契约；未发现的外部依赖须在实施时登记。
- 必读：[公共契约](../00-common-contract.md)、[总体计划](../README.md)。

## 目标与边界

只完成 `moneyflow_hsgt` 一个数据对象的Java业务定义、来源任务、分区/键、读取、写入和验收。已有projection不等于完成。公共D01—D09全部适用；不在本卡实现其他数据。

## 当前证据（2026-09-29快照，执行前复核）

- 分类：`data_model`；来源类别：`TUSHARE_TO_VERIFY`。
- 物理主时间列：`trade_date`；物理分区：`DAY`；WAL：`True`；DEDUP：`False`（协调器2026-09-30只读`tables()`核验，证据 `artifacts/java-migration/flows-schema-preflight-20260930.json`）。
- 物理UPSERT KEY：无。Java不为正式对象新增DEDUP迁移；写入使用同布局隔离stage、全表快照替换及publication journal。
- Python业务键：单行聚合自然键 `trade_date`；它不等于QuestDB物理UPSERT KEY。
- 模型与物理差异：Python模型声明`YEAR`分区，实物为`DAY`；Java隔离目标保留已核验的`DAY/WAL/DEDUP=false`布局。
- 配置source_api：`moneyflow_hsgt`；sync_function：`sync_moneyflow_hsgt`。
- 配置同步日期列：`trade_date`；衍生源记录：`未登记`。
- 配置事实（数组表示匹配记录，非当前授权额度）：`{"api": ["moneyflow_hsgt"], "date_column": ["trade_date"], "frequency": ["daily"], "time": ["06:30"], "rate_limit": [150], "timeout": [300]}`。

## 本数据sync模式与注意事项

按有限日期窗口。六个资金指标单位为百万元，保留源空值，不填零。源侧单次moneyflow_hsgt范围请求没有offset，最多返回300行；Java一次请求限制31个日历日，达到300行按潜在截断拒绝。全任务窗口上限366日，首个INCREMENTAL必须显式给`--from`；之后由同目标、同定义的连续VERIFIED INCREMENTAL receipt推进checkpoint，并重抓尾部5个日历日。BACKFILL/RECONCILE必须处于已验证覆盖内，不推进checkpoint。

正式物理表为非DEDUP。Java只向有界同schema stage追加；按raw receipt逐行比对stage窗口后，journal化地重命名原表到backup、stage到目标。空交易日仍以权威空窗口参与stage替换。中断发布通过`finishInterrupted`恢复；未发布stage仅在writer停止且原目标内容、物理身份未变化时可丢弃。

Python调用/限流证据（仅源码事实，未逐接口验证线上配额）：

- src/quant_platform/data/adapters/connectors/stock/moneyflow/moneyflow_hsgt_sync.py:42 `@api_rate_limit(api_limit=150, period=60)`

## 单数据交付清单

- [x] D01：本表DTO、domain、逐字段mapper与语义类型；实现覆盖全部七个物理字段。
- [x] D02：自然键为`trade_date`；物理无UPSERT KEY，重复业务日期、超界行及cap命中均拒绝。
- [x] D03：隔离DDL保留`trade_date`、DAY/WAL、非DEDUP；不新增正式库迁移。
- [x] D04：本表按键/范围typed read，重复日期检查与有界读取。
- [x] D05：typed stage batch write与完整业务字段回读；正式非DEDUP目标禁止直接写。
- [x] D06：有限Tushare范围source、不可分页和300行cap检测、不可变raw/incomplete receipt。
- [ ] D07：单数据Dataset/Job及CLI、写组合公共接线待协调器完成；API与接线要点见`results/D027.json`。
- [x] D08：有限重试策略、31日slice、checkpoint receipt校验、5日重叠、取消和journal恢复接口。
- [ ] D09：真实来源、隔离QuestDB首次写入/重跑/增量和独立raw回读待串行验收；定向javac不替代此项。

## 可观察验收

本表全字段映射可核对；完整业务键和值一致；日期/空值/精度符合冻结契约；重跑幂等；请求触顶、失败、重复页或未知写入不能成功；管理入口能够查到本表job/run/slice及失败原因。没有真实来源/权限时如实报告阻塞，不用fixture宣称真实同步完成。

## 物理字段清单

下表是待映射输入，不是已经确认的Java业务类型。标准名称与物理名称可以通过显式mapper兼容。

| 当前列 | 快照类型 | 任务要求 |
| --- | --- | --- |
| `trade_date` | `TIMESTAMP` | 待逐字段映射与语义核验 |
| `ggt_ss` | `DOUBLE` | 待逐字段映射与语义核验 |
| `ggt_sz` | `DOUBLE` | 待逐字段映射与语义核验 |
| `hgt` | `DOUBLE` | 待逐字段映射与语义核验 |
| `sgt` | `DOUBLE` | 待逐字段映射与语义核验 |
| `north_money` | `DOUBLE` | 待逐字段映射与语义核验 |
| `south_money` | `DOUBLE` | 待逐字段映射与语义核验 |

## 只读参考入口

- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/connectors/stock/moneyflow/moneyflow_hsgt_sync.py:68`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/questdb/models/stock/fina/moneyflow_hsgt.py`
- `D:/work/fund_2/back-monitor/config/yaml/data/data_quality_rules.yaml:168`
- `D:/work/fund_2/back-monitor/config/yaml/data/data_quality_rules.yaml:300`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/derived/regime/market_monthly.py:99`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/derived/regime/features_monitor_daily.py:240`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/config/tables.py:79`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/materializers/registry.py:78`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/materializers/registry.py:90`

- 全量引用和字段依据：`D:/work/fund_2/back-monitor/artifacts/storage-audit-20260929/objects.csv` 中 `moneyflow_hsgt` 对应行。
- 字典依据：`D:/work/fund_2/back-monitor/artifacts/storage-audit-20260929/object-dictionary.md` 的 `moneyflow_hsgt` 小节。

## 本任务容错、实际验收与完成登记（必做）

- [x] 实现前核对Python connector/model和协调器提供的只读物理schema证据；账号当下权限与有效限流仍须现场确认。
- [ ] 验证真实来源限流/超时、分页边界、取消、stage恢复及publication恢复；ACK不等于回读成功。
- [x] 实现receipt-backed连续增量checkpoint和5日修订重叠；真实checkpoint前后值待验收记录。
- [ ] 对隔离物理目标执行QuestDB全字段SELECT，并用独立原始receipt助手逐行核对；保留请求与证据。
- [ ] 首次非空真实来源写入、同范围幂等重跑及后续增量/修订验证待串行验收。
- [ ] 更新[逐项完成表](../completion-register.md)的 `D027` 行及 `results/D027.json`；填写完成状态、表名、源行/写入行、回读结果、运行时间、证据、问题及人工比对待办。
- [ ] 仅实现测试通过记implemented_not_verified；来源不可用记blocked；只有实际验收通过记verified。人工复核始终由用户决定。

## Orca执行提示

```text
D027实现已准备完成。先核验D026串行门槛以及results/D027.json中的剩余接线，然后只在明确的隔离QuestDB目标进行有界真实source验收。不要通过改正式表DEDUP、把300行cap当成功或伪造empty来放宽边界。独立回读必须从FETCHED事件指向的原始JSON receipt重算7个物理字段。真实首跑、同窗重跑、增量修订、空窗口、失败取消及publication恢复均未验收前保持implemented_not_verified；共享CLI/注册/写组合和完成表由协调器维护。
```
