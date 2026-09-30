# `post_close` 核心来源冻结计划

`post_close` 在独立运行时默认先执行核心来源 fan-out，再启动 Spring Batch 盘后 DAG。fan-out 的唯一输入是冻结计划，不会临时猜测证券 universe。

计划以内容寻址方式放在 `$JDB_ARCHIVE_ROOT/post-close-plans/<sha256>.json`。对应的 `post_close` `RunRequest.inputFingerprint` 与 `scopeIdentity` 都必须等于该文件原始字节的 64 位小写 SHA-256；请求据此读取不可变计划文件，不依赖可替换的日期别名。这样计划变化会改变盘后业务实例身份。请求、计划和来源请求使用相同的逻辑日期、交易日历版本及 `Asia/Shanghai` 时区；盘后计划仅接受单一逻辑日期。

JSON 结构如下。冻结时将下例实际展开为 15 个来源项，计算原始字节 SHA-256，以该摘要命名文件，并将摘要写入盘后请求的 `inputFingerprint` 与 `scopeIdentity`：

```json
{
  "schemaVersion": 1,
  "logicalDate": "YYYY-MM-DD",
  "calendarVersion": "<冻结交易日历版本>",
  "zone": "Asia/Shanghai",
  "sources": [
    {
      "dataset": "<required source dataset>",
      "definitionVersion": "<registered contract version>",
      "expectedCodes": ["<complete frozen entity scope>"],
      "universeVersion": "<frozen universe version>",
      "revision": "0",
      "supersedes": null,
      "revisionReason": null
    }
  ]
}
```

`sources` 必须恰好包含 `PostCloseGraph.CORE_TABLES` 中的 14 个数据集及 `exchange_calendar`，每个数据集仅出现一次。`definitionVersion` 必须匹配当前已注册契约；`expectedCodes` 必须是该数据集对当日的完整、真实范围，不能用单证券样本代替全市场范围。市场汇总契约按自身定义使用空集合，`exchange_calendar` 使用 `SSE`、`SZSE`。文件大小上限为 2 MiB；符号链接、归档根目录之外路径、日期/日历/时区不匹配及指纹错误均会失败关闭。

运行时按业务实例、触发 requestId 和来源生成确定性幂等键：同一触发请求的重复投递复用原 probe；同一业务实例下明确的新触发可以对失败的只读来源请求重新探测。检查 probe 完整后，系统构造绑定来源指纹、定义版本和 scope identity 的 `source_<dataset>` 请求，并递归经由同一个 `LaunchService` 执行。来源必须产出 VERIFIED 或契约允许的 VERIFIED_EMPTY 证书；失败、缺覆盖、运行中或 IN_DOUBT 都会阻止后续来源或盘后 Batch 启动。整个 fan-out 有序执行，不并发扣用来源配额。已有物理写入或成功证书时，重新探测若改变输入仍要求显式 source revision，不会覆盖旧证据。

同一 requestId 重试会复用 probe；新 requestId 保持相同盘后业务身份但建立新的只读 probe。若计划变化，盘后任务必须使用显式新 revision；已写入来源若输入变化，受影响来源也需提供自己的 revision、前驱 instance ID 和原因。来源身份或物理写入状态不明时仍须先走 reconciliation，不能以新计划掩盖旧的 IN_DOUBT。

此机制已通过 synthetic source/ledger fixture 与真实 SQLite 运行时缺失计划阻断测试，完整记录见 [fan-out 验证](evidence/core-source-fanout-20260930.json)；尚未对完整 15 源计划执行真实 Tushare→隔离 QuestDB 的整链验收。
