# 20260928–20260930 Level2 全归档清洗最终验收

结论：三日全部完成，按完整归档清单验收通过。范围为所有归档代码，包含股票、ETF及归档中的其他品种。

## 数量与性能

| 日期 | 成功标的日 | CSV 文件 | 110 字段检查次数 | 计算耗时 | 合法 ofi_slope 空值 |
|---|---:|---:|---:|---:|---:|
| 20260928 | 7926 | 23778 | 871860 | 14分2.9秒 | 46 |
| 20260929 | 7918 | 23754 | 870980 | 13分12.2秒 | 65 |
| 20260930 | 7888 | 23664 | 867680 | 13分37.9秒 | 37 |

合计 23,732 条标的日记录、71,196 个 CSV、2,610,520 个字段值。
从启动至结束 45.81 分钟（包括归档校验、解压与构建）；4 个 Java 工作线程。计算每日期约 13–14 分钟。

## 验收项目

- 三日 manifest 均 COMPLETE、published=true，启动器 exitCode=0，原任务 Java/7z 和启动器均已退出。
- census.json 的标的数、CSV 数、展开字节数与 archive-metadata 完整清单一致；实际目录集合一致，每个源 CSV 存在且大小匹配。
- 汇总 SHA256 与 manifest 一致；每个单标的结果 SHA256 与 manifest 一致，且内容与汇总逐行一致。
- 业务键（日期、规范代码）唯一，汇总按规范代码排序，结果集合与全部归档代码一致。
- 每行恰有 110 字段；类型按正式 Java StorageType 核对，非空数值均有限；必填标识非空。
- 日期均为所属交易日，feature_version=v1.3，parser_version=dfcf_csv_v1.2；代码遵循现有 Java/Python 规范化合同，股票/ETF 别名映射包含在全量检查中。
- 源归档解压日志均 exitCode=0，三日期 errors.jsonl 均为空，复用数为 0。

## 空数据、忽略及缺失清单

三日 EMPTY 标的、忽略目录、缺失源目录、意外源目录、缺失 CSV 和失败标的清单均为 []。

可空字段并非空数据标的：ofi_slope 合计 148 个合法 null（09/28:46、09/29:65、09/30:37）；完整代码清单见 output-validation-all.json。
正式 Java/Python 模型允许此字段为 null。另选 160515.SZ、185640.SZ、246132.SZ 重新运行 Python，330/330 字段与 Java 一致，包括 null；未将 null 改成 0。

## 汇总文件 SHA256

| 日期 | SHA256 |
|---|---|
| 20260928 | `eddb7db904003b95ba1403bf92a9218ddb18ccea1eeedd137da340ad66d478c1` |
| 20260929 | `5d68c6428af0fb5eb0522337648161e03618e8de735fedc1a42a20767205000d` |
| 20260930 | `2e3b7ef0a76078f206b9261bb1168203067ebec956fcac812f2f6557d259b757` |

## 输出与数据库边界

输出：D:\l2-native-features\20260928、20260929、20260930 下的 l2_daily_features.jsonl、features、manifest、进度和状态文件。
本任务未向正式或本地 QuestDB 写入。此前只读查询证据见 local-questdb-check.json；其查询时间以文件为准。本次验收不包含入库。

## 证据与验证范围

output-validation-all.json 为本次全量验证；nullable-reference-comparison.json 为合法空值的 Python 对照；此前股票/ETF 的 21 个标的日、2,310 字段和 10,389,818 原始行对照见 docs/l2-correctness-recheck-20261007.md。
完整源 CSV 本轮检查存在性和大小；未再次遍历 128.6 GB 源内容计算 SHA。归档 SHA256、解压结果及计算阶段源文件哈希来自已保存运行证据。全量结果结构/范围检查不等同于对全部 23,732 个标的日重跑 Python。
