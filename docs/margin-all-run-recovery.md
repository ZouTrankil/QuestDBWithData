# 汇总表已发布运行的显式收尾

`reconcile-margin-all-run` 已支持认证原运行和解除原锁。它只读取 QuestDB，修改原本地同步账本；不抓来源、不发送数据、不重命名或清理表。

## 两步处理

先核实原任务及其发送器已停止。常驻 JVM 可以继续存活，不能用观察超时推断任务已停。

```sh
# 先恢复物理发布；已恢复时会重新核对原布局和全部值。
APP_SYNC_MARGIN_ALL_WRITE_TABLE=margin_all \
./gradlew run --args='finish-margin-all-publication --run 原运行编号 --writer-stopped true'

# 再认证原运行，最后解除它自己的区间锁。
APP_SYNC_MARGIN_ALL_WRITE_TABLE=margin_all \
./gradlew run --args='reconcile-margin-all-run --run 原运行编号 --writer-stopped true'
```

原发布已经是 `VERIFIED`、只是完成凭证或账本落盘失败时，可以直接收尾。物理发布成功不再使失败回调自动释放原锁。

## 收尾检查什么

1. 原逻辑目标、作业 v1/v2、冻结请求指纹及日期范围一致。
2. 持有原发布操作锁，发布日志为 `VERIFIED`，当前表和 backup 的物理代、稳定 WAL、全部值与原证明一致。
3. 重开所有原始来源，核对摘要、逐日连续覆盖、冻结开休市规则、完整交易所范围和旧业务键。不会用新的来源替换旧原件。
4. 原账本只有对应的一次尝试和全部日分片。FETCHED 原件、归属、行数及不可变事件历史一致；非空分片必须已完整验证。只有已证实为空的 FETCHED 分片允许在这里补认证。
5. 保留原租约。全部条件通过后，先保存耐久收尾意图，再认证空分片、尝试及原运行。
6. 再次检查当前表和 backup，保存核验凭证，最后只解除原运行自己的锁。

普通范围记为 `VERIFIED`，完整休市空范围记为 `VERIFIED_EMPTY`；后者不表示整张表为空。原冻结请求、作业版本和历史事件都不改写。

收尾中断后沿同一意图重试。账本已经认证、但最终回读未通过时，锁仍保留。当前原发布未变化时，重复收尾不重发数据或增加成功事件；后续已发布新代时，查询旧运行状态，不再用旧发布重新认证当前表。

## 未发布 stage 的单独分支

未发布 stage 使用 `finish-margin-all-publication`。它要求原运行仍为 `IN_DOUBT`，持有原租约且没有发布journal。原表完整基线一致、stage确认消失后，显式取消原运行及全部子项；最终回读通过后才解锁。CLI返回 `CANCELLED` 和 `sourceRefetchRequired=true`，不表示采集成功。

随后单独执行已有的恢复入口：

```sh
APP_SYNC_MARGIN_ALL_WRITE_TABLE=margin_all \
./gradlew run --args='run-margin-all-job --resume-from 原运行编号'
```

新运行保持原冻结请求和父链，重新采集整个范围，`reusedRows=0`。原始来源和历史事件保留。取消后最终回读失败，继续保留锁；再次执行finish按同一意图收尾。已有 `FAILED`、`PARTIAL`、普通 `CANCELLED` 终态不会被这里静默改成成功。

[取消与重采决定](decisions/2026-10-22-未发布隔离stage的显式取消与重采.md)说明事务和恢复限制；[私有验收](audits/2026-10-22-cn-margin-all-stage-recovery.json)覆盖来源中断、全部原子项取消、原表不变及整窗重采。

原 writer／锁、来源、backup 或发布证明缺失或不符时，保留未决状态。正式生产接管和周期队列尚未启用，本轮只验证私有实例。

[阶段证据](audits/2026-10-21-cn-margin-all-finalization.json)覆盖：原运行收尾、原锁解除、后续范围更新、休市空源、完成凭证落盘故障、重复收尾和收尾中断恢复。日历、旧行、修订及故障为夹具，真实来源只覆盖已归档的指定日期。
