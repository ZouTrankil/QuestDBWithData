# F015 批量写入组合验收

本功能已完成，人工复核 pending_review。WriteGroupRequest/WriteGroupPlan 冻结组与成员批次、逻辑日期、完整定义、目标身份和规范化行指纹。PreparedWriteAdapter 验证 owner 的完整字段往返转换，再复用单数据执行器；PersistentWriteGroupRunner 使用 SQLite 父子账本和区间锁串行执行，保留部分完成与未知提交，不承诺跨表事务。

## 真实链路

`local-F015-json-live-4cd2` 显式启用真实写入验证。Tushare 两条真实响应转换成写入请求 JSON，再经 WriteGroupJson 的正式类型解析及生产 stock-basic 映射写入两个隔离表。第二项在预检后故障，父任务 PARTIAL；重开账本恢复后首项只读复核、第二项写入；再次恢复仅复核。各表实际 send 调用 1 次，WAL settled，完整键和值一致，成功后清理自有隔离表。

- [请求文件](d9f7a5e107734669b54b34e245ed9df9/write-request.json)（隔离测试专用 dataset ID，不是已准入业务数据）。
- [父子运行与实际 SQL 回读](d9f7a5e107734669b54b34e245ed9df9/write-group-readback.json)。
- [原始来源与实际值的独立核对](d9f7a5e107734669b54b34e245ed9df9/independent-values.json)：2 表、2 行、7 字段一致，无缺失、重复或差异。

`local-F015-final-8c71` 全套 155 项：137 通过，18 项外部条件未启用而跳过，0 失败。真实测试单独执行通过，不以跳过代替外部验收。定向测试覆盖有损映射、目标变化、数据缺失拒绝复用、空批次不写、重复恢复不发送、未知提交保持 IN_DOUBT 且拒绝自动重放。

随后使用真实物理表 id 和目录名计算目标身份，重跑 `local-F015-verified-latest`：155 项中 147 通过、8 项按条件跳过、0 失败。真实写入证据见 [最新回读](21381336bae144e8a94aed2608194f49/write-group-readback.json) 和 [独立核对](21381336bae144e8a94aed2608194f49/independent-values.json)，仍为 2 表 2 行 7 字段一致、各发送 1 次。一次修正前的测试在写入前因目标 ID 含冒号被拒绝，遗留的两张专用表经精确表名和 0 行核对后清理；该次失败不计入成功验收。

## 正式入口

`write-dataset-group --request PATH`；恢复使用相同有效载荷并增加 `--resume-from GROUP_RUN_ID`。结果不完整时输出运行 ID/状态并以失败结束；不得因命令失败直接当作未写入重新提交。

JSON 根字段：batchId、logicalDate（ISO 日期）、members。成员字段：memberId、datasetId、definitionVersion、batchId、rows。rows 必须包含定义的全部逻辑字段，包括显式 null；日期用 ISO 日期，Instant 用带偏移的 ISO 时间。拒绝未知属性、重复 JSON 键、不完整字段、歧义日期/错误类型、View/MV、版本不匹配。输入最多 64 MiB，每个字符串值当前最多 4096 字符，另有规范化字节/行预算。JSON 不接受任意目标表，目标由 owner 解析。

应用目前仅准入已有 stock_basic_snapshot 写入 owner。通用执行器已经通过双成员真实验收；后续单数据任务需在完成自身验收后再绑定 owner，不能据此认为其他业务数据已完成迁移。批量写入属于有限 prepared-input INGEST，不声称完成了尚未实现的数据历史增量同步。
