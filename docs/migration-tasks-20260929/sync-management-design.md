# 同步任务定义、增量与批量组合

这是F003、F009—F017及各单数据任务共同采用的设计要求，尚未实现。Python依据目录为 `D:/work/fund_2/back-monitor`，Java目标目录为 `C:/Users/zouqiang/IdeaProjects/QuestDBWithData`。

## 五种身份分开

| 定义 | 职责 | 必需字段 |
| --- | --- | --- |
| DatasetDefinition | 单个数据的字段与读写契约 | datasetId、schemaVersion、provider、owner、fields、businessKey、upsertKeys、timestampColumn、partition、read/write能力 |
| SyncJobDefinition | 单个数据的同步/导入/物化任务 | jobId、definitionVersion、datasetId、supportedModes、参数schema、slicePolicy、ratePolicy、retryPolicy、revisionWindow、verificationPolicy、依赖、dailyEligible |
| SyncGroupDefinition | 有序组合已有单数据job | groupId、version、有序members、显式依赖、共同参数、逐job参数覆盖、fail-fast、并发上限1 |
| SyncRun / Attempt / Slice | 一次执行及其可恢复证据 | runId、attemptId、sliceId、parentRunId、定义快照、logicalDate、请求窗口、源指纹、游标、计数、写入/验证状态 |
| ScheduleDefinition | 触发单job或group | scheduleId、targetId/version、时区、时间规则、交易日约束、enabled、misfirePolicy、重入策略 |

计划卡ID（例如D007）只用于开发编排；Orca运行时Task/Dispatch ID由Orca创建；Java同步job/run ID由应用生成，三者不能混用。

## 单数据模式

- `incremental`：默认模式。从已验证checkpoint与实际QuestDB覆盖出发，带有界修订重叠窗口；旧公告/旧期间修订不能被MAX日期截断。
- `backfill`：显式起止区间和预算；空库也必须有限bootstrap，不能默认获取全部历史。
- `snapshot`：用于确实只有当前快照的接口。标明无历史增量能力，只变更必要行或保留版本化快照；不能伪造历史。
- `reconcile`：针对确定范围对账，先检查旧attempt及writer是否结束，不盲目再次提交。
- `ingest`：用于文件、QMT或服务事件，按文件hash/事件身份/批次增量；不是虚构Tushare接口。
- `materialize`：按已验证源分区/版本更新衍生结果；普通View提供read/validate，MV刷新通过已确定的基表依赖触发。

每个job只声明真实支持模式，未知或不支持参数直接拒绝。对于无法服务端分片的来源，必须如实记录限制并解决来源能力问题，不能把本地分批写当成增量拉取。

## 定义示例（目标语义，非现有CLI或可直接导入配置）

```json
{
  "jobId": "data.etf_daily",
  "definitionVersion": 1,
  "datasetId": "etf_daily",
  "provider": "tushare",
  "endpoint": "fund_daily",
  "supportedModes": ["incremental", "backfill", "reconcile"],
  "defaultMode": "incremental",
  "slicePolicy": {
    "kind": "trade_date",
    "calendarDataset": "exchange_calendar",
    "paging": "offset_when_supported",
    "pageSize": 2000,
    "failOnPossibleTruncation": true
  },
  "ratePolicyRef": "tushare.account-and-fund_daily",
  "retryPolicyRef": "bounded-provider-retry",
  "verificationPolicyRef": "questdb-full-key-and-values",
  "dailyEligible": true,
  "enabled": false
}
```

实现时还必须冻结账号实际额度、修订窗口、最大页数/行数/字节数、超时及允许的参数schema。示例没有授权来源请求或定时执行。Python该表配置限流25与请求装饰器200/60s存在差异，不能直接取较高值。

## 批量同步、读取、写入分别组合

- sync组合引用单job，例如 `data.daily → data.daily_basic → data.stk_factor`；各自保留请求方式、频率与完成状态，共享账号限流。
- read组合携带每个dataset自己的filter/projection/pageSize/cursor，输出各自typed结果、来源版本和错误。不同表不隐式join，也不承诺跨查询一致快照。
- write组合携带每个dataset自己的typed rows/批次身份，分别校验、写入、回读验证。一个失败时父结果是partial/failed，不承诺跨表原子事务。
- 日常组合仅选择dailyEligible且已经验收的数据；allEnabled与dailyEligible不是同一集合。专项或低频接口不可因批量操作自动纳入每日执行。

## 故障与状态

取数成功不等于写入成功，写入ACK不等于QuestDB验证完成。建议分别保存runStatus、deliveryStatus和verificationStatus，避免一个success覆盖全部阶段。

正常流程：planned → running → fetched → validated → submitted → acknowledged → verified。0行的有效请求走verified_empty，不执行空写；首次数据写入验收仍必须另有非空真实样本。失败进入failed/partial，提交结果不明进入in_doubt，取消进入cancelled并保留已验证片。

恢复读取原attempt、checkpoint、源版本和QuestDB实际数据；只有明确已完成的片才复用。来源版本变化、页偏移失效时重建有限计划，不盲续旧offset。锁到期或CLI超时不证明旧writer退出。

## 完成记录与人工复核

每项更新 [completion-register.md](completion-register.md) 与 `results/<task-id>.json`。记录内容包括源请求与响应、有效/拒绝行、提交行、实际插入/更新或unknown、Query及参数、完整键和值比较、重复/缺失键、checkpoint前后、run/attempt及完成时间。

来源无权限、没有真实样本、目标库不可用时给出明确阻塞，不承诺任务必然成功。任务实施完成、数据验收verified、用户逐表检查accepted是三个独立状态；AI只能填写前两项及证据。
