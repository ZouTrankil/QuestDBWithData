# 当前源码定向回归及批量入口行为复查

2026-10-07，Java 24.0.2。生产文件未修改。

## 定向 JUnit

独立构建：`var/l2-correctness-recheck/gradle-build`，独立 Gradle 项目缓存。
仅运行指定类族：

| 类 | 通过 |
|---|---:|
| DfcfCsvInspectorTest | 3 |
| DfcfCsvParserTest | 13 |
| L2DailyFeaturePipelineTest | 7 |
| L2GmmIntensityEdgeTest | 3 |
| L2LifecycleFeaturesTest | 2 |
| L2NumpySortTest | 2 |
| L2ArchiveAdmissionTest | 10 |
| L2QuestDbTablePortTest | 2 |
| 合计 | **42/42** |

failures/errors/skipped 全部为 0。`targeted-junit-summary.json`、`targeted-gradle.log`
与 `gradle-build/test-results/test/*.xml` 为具体证据。
QuestDB 端口测试只检查编码和非法目标拒绝；归档准入测试使用 JUnit @TempDir 内的隔离 SQLite。
未连接或写入正式 QuestDB，未执行 live 或全仓库测试。

## 新批量入口：15 项实际行为守卫

`batch_cli_behavior_recheck.py` 使用上述 fresh Gradle 类，从两只合成 ETF 复制 CSV
到验证目录后逐场景运行原生 CLI。`batch-cli-behavior-summary.json` 为汇总，**15/15 通过**。
每场景保存命令输出、manifest、checkpoint 和 JSONL，校验全部 110 字段的字段名、类型、有限数值、
日期、规范代码唯一性、排序及聚合 SHA-256。

1. Fresh：两只 ETF 生成 2 行，未恢复旧结果。
2. 不变 resume：复用 2/2，聚合字节不变。
3. 结果 JSON 文件加空格：受影响代码重算，另一个代码复用。
4. checkpoint 计算指纹变旧：受影响代码重算，另一个代码复用。
5. 一个有效成交 CSV 的数量加 1：只重算对应代码，输出 hash 实际改变。
6. 恢复该源文件：对应代码重算，重新得到原聚合 hash。
7. 相同 510300.SZ/510300_SH 别名：只有一行 510300.SH；全空目录记 EMPTY。
8. 别名内容冲突：该规范代码 FAILED，其余成功/空代码仍处理；exit=1，published=false，保留此前完整聚合。
9. 一个合法代码目录内 CSV 损坏：另外两个代码全部成功；exit=1，无半日聚合文件。
10. 同一不完整日 resume：两只成功代码复用，失败代码重试；仍不发布半日结果。
11. 修复坏代码：保留此前成功 checkpoint，完整发布 3 行。
12. 第一个日期有坏代码：第二日期仍完整成功，整体进程 exit=1。
13. 显式白名单：只处理交集，排除计数及当日缺失 requestedNotPresent 正确。
14. 只改变实际加载的 javac 合成 enum-switch helper 字节：两只代码均失效重算；算法输出字节不变。
15. 恢复原 helper 字节：两只代码再次失效重算；算法输出字节仍不变。

14–15 并非手动修改 checkpoint 字符串。把当前 Pipeline 源码用 `javac -g:none` 编译到验证目录，
仅将其 `L2DailyFeaturePipeline$1.class` 放在另一个 classpath 前缀，生产 Pipeline 类及生产源码保持原样。
helper 的 debug 属性变化令实际 class hash 改变，计算指纹从
`a3b6545e0a6b9ec1628d6c64aff7ba6663ea2cd5ee4a00d0a1150235c1a93961`
变为 `103291241972240a5b77000565c342d3e1b9dee04170aed2c132c99ec3c9a5a3`，
两次输出仍为 `978d57fbe422c92aaffb80f1aa4eaa26997f8d02fe87e9e163ca65a88721a92b`。
这确认指纹包含 reflection 通常不会返回的 `$1` helper。

## 代码审查结论

本次检查路径未发现需要修复的正确性问题：

- 至多 workers 个任务在途，取到一个完成结果才继续提交；逐标的失败不会漏掉后续标的。
- 规范代码分组发生在计算前，相同内容别名去重、冲突别名失败。
- 单代码结果和 checkpoint 独立原子写入；当日只有全部非空选中代码成功后才按规范代码聚合并原子发布。
- 恢复核对三个指定源 CSV 的 hash/大小/缺失状态、计算 class 指纹、日期/代码及结果 hash。
- 输入 hash 计算前后核对，输出 hash 在聚合前再次核对。

两项行为需要调用方遵守，均已有使用文档且本次实际验证符合设计：

- 新失败批次保留前次完整 JSONL；消费方必须以 manifest 的 COMPLETE/published=true 和 aggregateSha256 为准。
- 白名单当日缺失代码记录 requestedNotPresent，按交集处理；不会把当日未出现的代码当作失败任务。

本次没有模拟断电、磁盘故障或同时修改已解压源文件。真实三日的 Python 逐字段对照由其他并行任务负责。
