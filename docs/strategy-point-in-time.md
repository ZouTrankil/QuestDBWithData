# 历史评分的时点输入

新评分按生效日期和当时可用时间选择名称、行业及市场状态，缺原件就停止，不把今天的资料用于整个历史区间。

覆盖清单的 `pointInTime` 包含 `path`、`sha256`，指向 `java.strategy.point_in_time.v1` 文件：

- `decisionTimes`：评分日到真实 UTC 决策时刻的映射，限定在当天上海收盘至下一实际交易日开盘之间。
- `stockMetadata`：`ts_code,name,industry,effectiveFrom,effectiveTo,availableAt`。`effectiveTo` 可空，区间左闭右开。同一可用时刻冲突则停止。
- `marketContext`：`trade_date,availableAt,values`。只取决策时已可用的修订；`values` 完整覆盖正式市场状态列，不用今天的值补空。
- `sourceEvidence`：历史原件的 `path,sha256` 列表。相对路径以本文件目录为基准。

`availableAt` 是真实可用时间，不能用今天导出文件的时间倒填过去。文件、原件及摘要在发布前重查。摘要只能固定材料，不能补出缺失的历史事实。

更名、ST、行业及事后市场修订的反例测试已覆盖。本轮没有生成生产历史包，实际评分区间仍需真实原件。
