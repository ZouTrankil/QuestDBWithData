# D005 组合任务接入验收（2026-09-29）

状态：D005 running；本轮通过不代表整项完成。累计仍为 21 项 verified。

## 实际执行

- 构建：`local-D005-group-1055`，`IndexMembershipGroupLiveTest`，1 项通过，0 跳过、0 失败。
- 凭据：沿用 Python 参考项目配置，仅注入本地 Java 子进程环境。
- 证据：`group-0297070b1099442282e6e33e41f1eea3/group-readback.json`。
- 注册组合 `group.index_member_manual@1` 执行 `data.index_member@1`；组合保留子批次 run ID、父子关系、冻结请求和初始目标身份。
- 两个显式 L2 行业 CURRENT 串行取数，共 2 次真实 Tushare 请求；实际隔离 QuestDB 表包含 6 条成员期间记录。
- 恢复同一组合复用原批次，不新增来源请求；逐行业凭据、发布前后整表链和最终 14 字段实际回读通过，恢复前后完整快照一致。
- 正式 `index_member` 完整快照保持一致。成功验收的本轮隔离目标及发布备份已清理，SQLite 账本与 JSON 证据保留。

## 未完成边界

更早批次中断、尚未创建子任务的恢复边界及最终回归仍待验收。不得据此把 D005 标记 verified。完成后仍需在逐任务对照表记录证据和人工 pending_review。

## 跨数据同步组合（1056）

`group.market_classification_manual@1` 明确按 `data.ths_index@1` → `data.index_member@1` 执行，成员参数分开覆盖。

- `local-D005-cross-group-1056`：1 项通过、0 跳过、0 失败。
- 证据 `group-4c541085a42e42228d4b7ffc0165896c/`：`group-readback.json`、`ths-readback.json`、`serial-order-readback.json`。
- 3 次真实来源请求；同花顺指数 2517 条、行业成员 6 条分别写入隔离 QuestDB 表，owner 完整字段回读通过。
- 只读 SQLite 事件核验：同花顺任务 VERIFIED 时间早于成员批次 PENDING 创建时间，实际顺序符合声明依赖。
- 整组恢复复用两个原 child run，0 新来源请求；两张表完整快照不变，两个正式表完整快照不变。

## typed-write 单成员组合（1057–1059）

- 1057 暴露 `prepared.membership.l2` 未加入写入组合策略目录，在写入前被拒绝；已补注册。
- 1058 首次写入成功，但恢复复核将解析后的 JSON 小整数与 Java long 节点直接比较，错误报告目标变更。改为反序列化到 `IndexMembershipStorage.Snapshot` 后做强类型完整快照比较。
- 1059 `IndexMembershipPreparedWriteLiveTest` 1 项通过、0 跳过、0 失败：使用正式表只读取得的 2 条完整成员记录，在隔离表写入、逐值回读；组合恢复复用原 child、不再次写入、正式表不变。
- 证据 `prepared-c9e55d3bf5ba4932a25ec238bda3aade/write-readback.json`。这是显式本地输入写入验收，来源类型为 `prepared-write-request`，不冒充 Tushare sync 请求。
- 1057/1058 失败现场保留。多数据集 typed-write 仍受现有“替换型成员必须最后一个”约束，尚未宣称完成任意写入组合。

## 两个 WAL 数据的写入组合与部分失败恢复（1060–1061）

已允许具有发布身份保留与完整回读恢复能力的 WAL_REPLACE owner 后续继续独立数据成员。非 WAL STATIC_REPLACE 的末位限制暂时保留。组合仍是逐数据串行提交，不声明跨表原子事务。

- 1060：`twoWalReplacementDatasetsPublishAndResumeWithoutSecondWrite` 1 项通过、0 跳过。来源是正式表只读取得的显式本地输入，每个数据 2 条；THS → membership 两张隔离表完整回读通过，恢复复用两个原子任务、完整快照不变。
- 证据 `prepared-bd9b39a5c530442d9dbdd31ac95d3dfc/write-readback.json` 与 `ths-readback.json`。
- 1061：`secondDatasetLockFailureResumesWithoutRewritingFirstDataset` 1 项通过、0 跳过。先持有本次测试自己的 membership 数据锁，THS 验收后 membership 子任务 FAILED，父组合 PARTIAL，成员目标仍为空；释放自己的测试锁后恢复，THS 原子任务复用且整表不变，membership 新子任务写入并回读 2 条；再次恢复两者均复用。
- 证据 `prepared-22f1d3e8bae34bcebf4eac77e443ebe6/partial-resume.json`、`write-readback.json`、`ths-readback.json`。账本事件证明 THS VERIFIED 早于成员任务创建，两张正式表完整快照保持一致。
- 成功验收后的本次隔离目标及发布备份已清理，凭据、账本和验收 JSON 保留。未释放其他任务锁。

## 后续写入成员尚未创建时恢复（1062）

恢复目标从原组合冻结 JSON 的对应 job/ordinal 读取；不再依赖每个数据都已经创建 child run。原组定义、输入指纹、目标及实际已完成子任务仍由组合执行器和 owner 逐层核验。

- `local-D005-unstarted-write-1062`：`cancellationBeforeSecondChildCreationResumesFromFrozenGroupTarget` 1 项通过、0 跳过。
- 第一张隔离 THS 表 2 条实际验收完成后，通过账本正常取消父组合；成员表仍为空，membership 的 group slot.childRunId 确认为 null。
- 恢复复用 THS 原任务及完整快照，创建 membership 新子任务并写入/回读 2 条；再次恢复两个任务均复用。
- `prepared-87a4cbd3477d404ebd8eded8e3db47ae/partial-resume.json`、`write-readback.json` 与 `ths-readback.json` 留存证据；正式表不变，成功的隔离目标/备份已清理。
- 此项证明正常取消后尚未创建成员的写入组合恢复；不把它等同于进程突然退出后的批次早期恢复，也未完成 sync 组合相同边界验收。
