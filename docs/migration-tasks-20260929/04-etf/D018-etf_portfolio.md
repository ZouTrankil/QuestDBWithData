# D018 · etf_portfolio

- 状态：verified；真实分页、九字段回读、多chunk恢复和独立CLI通过；08-28冲突来源拒绝，人工复核 pending_review。详见 results/D018.json。
- 工作区：`C:/Users/zouqiang/IdeaProjects/QuestDBWithData`。
- Python项目目录（持续只读查找）：`D:/work/fund_2/back-monitor`；同步配置、connectors、模型、读写SQL和测试均可沿实际调用链检索。
- 串行前置：`D017`；前项验收后才执行本项。
- 数据对象：`etf_portfolio`。
- 业务依赖：本卡来源与公共功能契约；未发现的外部依赖须在实施时登记。
- 必读：[公共契约](../00-common-contract.md)、[总体计划](../README.md)。

## 目标与边界

只完成 `etf_portfolio` 一个数据对象的Java业务定义、来源任务、分区/键、读取、写入和验收。已有projection不等于完成。公共D01—D09全部适用；不在本卡实现其他数据。

## 当前证据（2026-09-29快照，执行前复核）

- 分类：`data_model`；来源类别：`TUSHARE_TO_VERIFY`。
- 物理主时间列：`end_date`；物理分区：`YEAR`；WAL：`True`；DEDUP：`True`。
- 物理UPSERT KEY：`ts_code,ann_date,end_date,symbol`。
- Python声明键：`ts_code,ann_date,end_date,symbol`。
- 模型与物理差异：`清单未发现已比较项差异；不代表全部字段一致`。
- 配置source_api：`fund_portfolio`；sync_function：`sync_etf_portfolio`。
- 配置同步日期列：`ann_date`；衍生源记录：`未登记`。
- 配置事实（数组表示匹配记录，非当前授权额度）：`{"api": ["fund_portfolio"], "date_column": ["ann_date"], "frequency": ["daily"], "time": ["18:00"], "rate_limit": [200], "timeout": [3600]}`。

## 本数据sync模式与注意事项

fund_portfolio按代码/报告期或公告窗口及接口实际分页能力取数；end_date是报告期，ann_date是同步依据，symbol为持仓证券身份。

### Java实施快照（2026-09-30；尚未验收）

- DTO/domain/mapper覆盖源字段 `ts_code,ann_date,end_date,symbol,mkv,amount,stk_mkv_ratio,stk_float_ratio`；`update_time` 是Python预处理增加的非源字段，Java冻结为run级UTC观测时刻。日期按日历日解释，QuestDB存UTC午夜carrier；数值保留源DOUBLE和空值。
- 使用完整业务键/物理UPSERT键 `(ts_code,ann_date,end_date,symbol)`，指定时间为 `end_date`，保留YEAR/WAL/DEDUP契约。正式 `etf_portfolio` 仍是外部所有对象；实现不添加正式表Flyway迁移，只允许 `java_d018_etf_portfolio_<suffix>` 隔离目标。
- WritePort发送与按键readback单批均上限250行、1MiB，和共享 `SyncJobRunner` 的250行分页批次一致；历史首轮在200/250界限不一致时进入IN_DOUBT，之后已将D018专属端口上限对齐为250。IN_DOUBT账本保持原样，现场应由协调器先读回隔离表并用新目标重试。
- 增量首跑要求明确 `--from` 且隔离目标为空；checkpoint只拼接同目标、同job定义、INCREMENTAL且逐日receipt完整的连续区间，30日重叠从锚点夹取。每个公告日全量对比源receipt与物理行；源撤回造成的旧键无法由upsert删除，因此多余物理键会失败关闭，不会被当作reconcile成功。
- 仅INCREMENTAL run推进checkpoint；同目标、同定义且更新的已验证BACKFILL receipts覆盖其影响日期的物理值校验依据，不改变checkpoint边界。
- 参考Python使用 `limit=8000`、递增 `offset` 请求每个 `ann_date`。官方文档没有声明分页能力/总量上限；结合协调器的真实来源探测，Java本地上限提高到每公告日256,000行、最多33次请求并要求终止短页，再拆为最多26个不超过10,000行的ledger/write page；单run总行数上限为1,000,000。到界或分页证据不完整时失败并保留不完整原始证据。Java缺少 `ann_date` 时失败关闭，不采用Python旧数据兼容回退。
- 协调器只读来源探测观察到2026-08-27返回18,718行（8,000 + 8,000 + 2,718）；2026-08-28收到offset 0至64,000的9个完整页（合计72,000行）后，按旧64,000行上限失败关闭，因此不能据此认定该公告日完整。证据位于 `artifacts/java-migration/D018/provider-diagnostic/00cd6407-a7e9-46ab-b463-c2286a70ef46`。更新后的实现由协调器做了范围编译并完成2026-08-27的18,718行隔离写读与重复回放；后续增量探测受08-28重复键冲突阻断，D018仍未整体验收。
- 后续Java增量来源探测在新256,000行上限下抓取2026-08-28的27个完整页/216,000行，发现2组跨页同四键冲突（`021461.OF`、`161217.SZ`，均为 `20260630` / `601112.SH`；冲突字段为 `mkv`、`amount`），因此完整来源与该日写入均失败关闭；未通过静默去重接受。详见 `artifacts/java-migration/D018/source-diagnostic/2026-08-28-conflicting-duplicate-summary.json` 和原始不完整receipt。08-27此前仍为18,718行已验证；PARTIAL run不推进checkpoint。

Python调用/限流证据（仅源码事实，未逐接口验证线上配额）：

- src/quant_platform/data/adapters/connectors/etf/etf_portfolio_sync.py:47 `FUND_PORTFOLIO_PAGE_LIMIT = 8000`
- src/quant_platform/data/adapters/connectors/etf/etf_portfolio_sync.py:50 `@api_rate_limit(api_limit=200, period=60)`

## 单数据交付清单

- [x] D01：本表DTO、domain、逐字段mapper与语义类型；核对下方全部物理列。
- [x] D02：本表业务Key、物理去重键、冲突/修订规则。
- [x] D03：本表主时间、WAL、分区、DDL及兼容方案；确认快照漂移。
- [x] D04：本表按键/范围的typed read与分页，接入读取组合。
- [x] D05：本表typed batch write及逐键值验证，接入写入组合；View/MV提供拒绝直写的验证。
- [x] D06：本表真实来源sync/ingest/materialize，有限窗口/页/批及截断检测。
- [x] D07：注册 `etf_portfolio` DatasetDefinition和单数据job，支持管理、计划预览、运行与状态查询。
- [x] D08：本表限流、重试、断点、取消和完整性证据；不吞失败为empty。
- [x] D09：本表有界示例、隔离库读写及来源样例对照，完成后提交本卡结果。

## 可观察验收

本表全字段映射可核对；完整业务键和值一致；日期/空值/精度符合冻结契约；重跑幂等；请求触顶、失败、重复页或未知写入不能成功；管理入口能够查到本表job/run/slice及失败原因。没有真实来源/权限时如实报告阻塞，不用fixture宣称真实同步完成。

## 物理字段清单

下表是待映射输入，不是已经确认的Java业务类型。标准名称与物理名称可以通过显式mapper兼容。

| 当前列 | 快照类型 | 任务要求 |
| --- | --- | --- |
| `ts_code` | `SYMBOL` | 待逐字段映射与语义核验 |
| `ann_date` | `TIMESTAMP` | 待逐字段映射与语义核验 |
| `end_date` | `TIMESTAMP` | 待逐字段映射与语义核验 |
| `symbol` | `SYMBOL` | 待逐字段映射与语义核验 |
| `mkv` | `DOUBLE` | 待逐字段映射与语义核验 |
| `amount` | `DOUBLE` | 待逐字段映射与语义核验 |
| `stk_mkv_ratio` | `DOUBLE` | 待逐字段映射与语义核验 |
| `stk_float_ratio` | `DOUBLE` | 待逐字段映射与语义核验 |
| `update_time` | `TIMESTAMP` | 待逐字段映射与语义核验 |

## 只读参考入口

- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/connectors/etf/etf_portfolio_sync.py:161`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/questdb/models/etf/market_data.py`
- `D:/work/fund_2/back-monitor/scripts/validation/validate_etf_portfolio_data.py:53`
- `D:/work/fund_2/back-monitor/scripts/validation/validate_etf_portfolio_data.py:65`
- `D:/work/fund_2/back-monitor/scripts/validation/validate_etf_portfolio_data.py:77`
- `D:/work/fund_2/back-monitor/scripts/validation/validate_etf_portfolio_data.py:96`
- `D:/work/fund_2/back-monitor/scripts/validation/validate_etf_portfolio_data.py:117`
- `D:/work/fund_2/back-monitor/scripts/validation/validate_etf_portfolio_data.py:166`
- `D:/work/fund_2/back-monitor/config/yaml/research/factor_platform_v2.yaml:234`

- 全量引用和字段依据：`D:/work/fund_2/back-monitor/artifacts/storage-audit-20260929/objects.csv` 中 `etf_portfolio` 对应行。
- 字典依据：`D:/work/fund_2/back-monitor/artifacts/storage-audit-20260929/object-dictionary.md` 的 `etf_portfolio` 小节。

## 本任务容错、实际验收与完成登记（必做）

- [x] 先核实本任务所需来源、权限、schema、键、参数和QuestDB连接；有阻塞即记录，不能盲目继续。
- [ ] 验证本任务适用的限流/超时重试、分页异常、取消和断点恢复；已ACK但未回读一致的写入保持未验证。
- [x] 默认增量，记录checkpoint前后与有限修订窗口；sync有数据才写，没有数据明确记0，不用假数据充数。
- [x] 对本任务实际目标进行QuestDB SELECT，按完整业务键逐字段比对源规范化数据；保留请求范围、查询/参数、返回样本和汇总。
- [x] 首次非空真实来源写入、同范围幂等重跑及再次增量验证有记录；本任务为View/MV或功能时按公共契约对应的实际验收方式执行。
- [x] 更新[逐项完成表](../completion-register.md)的 `D018` 行及 `results/D018.json`；填写完成状态、表名、源行/写入行、回读结果、运行时间、证据、问题及人工比对待办。
- [x] 仅实现测试通过记implemented_not_verified；来源不可用记blocked；只有实际验收通过记verified。人工复核始终由用户决定。

## Orca执行提示

```text
在 C:/Users/zouqiang/IdeaProjects/QuestDBWithData 执行计划任务 D018：etf_portfolio。Python参考项目为 D:/work/fund_2/back-monitor，请持续按真实调用链只读查找，不只依赖摘要。先读取 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/04-etf/D018-etf_portfolio.md 和 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/00-common-contract.md，核验串行前置 D017 的验收记录。只完成本卡功能或单个数据，不代做后续任务。保留已有用户修改，使用明确目标的隔离QuestDB和有界真实来源样例。默认增量sync，有有效数据才写入，按完整键实际SELECT回读逐字段核对；首次0行不能认定写入验收完成。实现有限重试/限流/断点/取消/未知写入核验，验收失败保留现场并停止。更新 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/completion-register.md 和 results/D018.json，记录源返回、写入、QuestDB回读、checkpoint及证据，人工复核保持pending_review。若本卡为conditional，先核验显式准入记录；无记录不实施。完成后停止，由Orca协调器验收再决定下一项。
```
