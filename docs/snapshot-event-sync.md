# 回购和大宗交易的范围快照

`repurchase`、`block_trade` 已接有限 v2 owner，任务分别是 `data.repurchase`、`data.block_trade` 版本 2。一次查询一个公告日或交易日的完整来源，保留相同字段值出现多次的情况。没有正式写入，也没有启用日批。

## 为什么不用旧键续写

回购的 `end_date` 可以为空，同证券、公告日、截止日和进度也不能代表唯一方案。大宗来源没有可靠成交编号，旧键会合并同值多笔。真实单日检查已发现大宗旧键碰撞。

v2 保留原列和 YEAR/WAL，增加 LONG 列 `snapshot_ordinal`，不启用数据库去重。证券、来源业务日、范围内序号组成当前快照的读取键。序号由完整内容排序生成，同值多条分别编号；它不是供应商事件编号，也不能跨修订追踪同一方案或成交。

旧表必须显式选择迁移，才创建 v2 stage 并切换原同名目标。旧请求、生成模型和原物理对象不被重标成 v2。原表保留为 backup，范围外每条旧记录及重复次数完整保留，但不认证它们为当前来源完整历史。旧键过去吞掉的记录仍需逐日重新补数。

## 有限入口

```sh
./gradlew run --args='plan-event-snapshot-v2 --dataset repurchase --date 2026-09-19 --logical-date 2026-10-21 --migrate-legacy false'

# 只读完整目标并抓取单日来源，不创建正式同步账本，不执行DDL。
./gradlew run --args='check-event-snapshot-source-v2 --dataset block_trade --date 2026-09-18 --logical-date 2026-10-21 --migrate-legacy false'

# 完成原writer、账本、停写、备份及消费者核对后，才显式迁移旧表。
./gradlew run --args='run-event-snapshot-v2 --dataset repurchase --date 2026-09-19 --logical-date 2026-10-21 --migrate-legacy true'

# 已是v2时用false；发生来源失败且尚未建立stage，可恢复原请求。
./gradlew run --args='resume-event-snapshot-v2 --dataset repurchase --run 原运行编号'

# 原writer停止并释放操作系统锁后，核对原stage/backup并续接原重命名尾部。
./gradlew run --args='reconcile-event-snapshot-v2 --dataset repurchase --run 原运行编号 --writer-stopped true'
```

两个来源均按完整单日查询，不加未证实的单证券过滤或 offset。回购达到 2000 行、大宗达到 1000 行时拒绝完整，保留原文。公告日和交易日仍是 UTC 午夜日期载体，不冒充盘中可用时刻；数量和金额保留来源单位，不归一化。

当前一次完整目标最多一百万行、规范内容 128 MiB；超预算拒绝发布，不能静默截断。真实两表均已通过该预算下的完整读取。首次迁移和每次修订都创建完整 stage，发送阶段不访问正式表；先收齐来源，再保留范围外行、替换范围内所有记录。非空旧范围遇到空来源时阻断，需要独立撤销证据；原本为空的范围可认证合法空源。

## 发布和恢复

- 复用原同步账本、全表区间锁和 `ReferencePublicationJournal`；每次持有独立操作系统发布锁。
- 创建 stage 前保存耐久意图。stage 不去重，发送后核对全部字段、序号和重复次数、原物理对象、稳定 WAL。通用页只再次核验已经写完的 stage，不追加第二遍。
- 所有 stage 页通过后才保存重命名意图。正式表移至 backup，原 stage 移至正式表，每次只发送一次 DDL，重命名后等待同一目标 WAL 收敛。最后再次核对新旧对象和完整内容。
- 原生 MV 引用原表时阻断切换，先制定刷新和消费者方案。外部消费者归属仍需正式接管材料，不能仅凭数据库内没有 MV 依赖就宣布就绪。
- 发送或发布结果未决时保留原锁、stage、backup 和原文，不自动重发或删除。重命名中断可显式续接原已核验 stage；来源摘要、页凭证、对象或内容不符时拒绝恢复。
- stage 本身部分发送且没有完整核验证据时，当前恢复入口拒绝继续，保留原件供人工核查。不能用重抓当前来源、另建任务或解锁冒充恢复。
- 公开读取只接受 v2 布局；有原区间锁或未决运行时拒绝读取。每页前后固定物理对象和已应用 WAL 版本，游标不能跨版本。

本批真实来源、私有发布、重复行夹具、修订、重命名故障及回归范围见[阶段证据](audits/2026-10-21-cn-event-snapshots.json)。私有故障演练不等于正式迁移；后续调度、全历史补数和消费者验收仍未完成。
