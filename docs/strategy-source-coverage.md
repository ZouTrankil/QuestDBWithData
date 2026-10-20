# 评分来源核验

新评分必须提供独立核验过的覆盖清单。没有清单、缺证券、重复键或凭证变化，都会停止发布。服务和评分 CLI 共用这项检查。

默认配置放在根 `config/apps/cn-main-strategy.yml` 的 `app.sync.batch`，个人覆盖仍用标准配置覆盖方式：

```yaml
source-coverage: /绝对路径/coverage.json
source-coverage-sha256: 清单文件的64位小写SHA256
```

CLI 对应 `--source-coverage`、`--source-coverage-sha256`。清单属于运行证据，不提交真实生产清单。

## 清单内容

格式为 `java.strategy.source_coverage.v1`，包含 `start`、`end`、`warmupStart`、`sourceClocksSha256` 和 `sources`。日期用 `YYYY-MM-DD`。

`sources` 必须覆盖下面全部输入。每项包含 `expectedRows`、`expectedKeysSha256`、`certificate` 和 `certificateSha256`。凭证路径相对于清单目录，也支持绝对路径。

| 输入 | 完整业务键，顺序不能换 |
| --- | --- |
| stock_daily | trade_date, ts_code |
| l2_raw | ts, symbol |
| etf_factor | trade_date, ts_code |
| etf_daily | timestamp, ts_code |
| stock_metadata、etf_reference | ts_code |
| etf_portfolio | ts_code, ann_date, end_date, symbol |
| sentiment、market_context | trade_date |
| calendar | exchange, cal_date |

键摘要：把每行的键转成字符串数组，用紧凑 JSON 编码；按字符串排序。逐项写入 4 字节大端长度及 UTF-8 内容，再计算 SHA-256。空键和重复键拒绝通过。数据库版本摘要使用 `QuestDbStrategyInputs.clocks()` 返回对象的 Jackson 紧凑 JSON。实现见 `StrategySourceCoverage`。

范围和读取器一致：股票及 ETF 因子含预热期，ETF 行情再向前 5 天；持仓含开始日前最后一次公告和区间内公告；日历延长到结束日后 14 天。L2 按批准的实际范围核验，不要求全市场覆盖。

## 目前的限制

这是发布前的阻断检查。清单要由独立采集核验、上市停牌规则和来源范围共同确定，不能把目标表当前行数或本次查询结果当成应有范围。文件摘要只能固定批准结果，本身不能证明供应商数据完整。

程序已支持从独立批准的预期键和真实上游运行自动组装清单。默认输入仍为空；没有真实批准材料时，新评分继续停止。已有评分不因此自动获得完整性或历史时点证明。

## 自动组装

输入格式 `java.strategy.coverage_inputs.v1`，包含 `start,end,warmupStart,sourceClocksSha256,sources,pointInTime`。每个来源填写 `expectedKeys`（按上表顺序的字符串数组列表）、`certificate,certificateSha256,upstreamRuns`。运行项含 `ledger,runId`。

预期键必须来自独立证券及来源范围，不能复制目标表当前结果。上游必须是对应数据集的已核验根运行；股票输入要求 `stk_factor,stk_limit,stk_suspend,stk_st_daily,daily_basic` 的凭证。用别的数据集运行充数会失败。

```sh
./gradlew mainStrategyData --args='build-coverage --coverage-inputs /实际路径/inputs.json --coverage-inputs-sha256 批准摘要 --output /实际路径/coverage.json'
```

配置 `app.sync.batch.coverage-inputs`、`coverage-inputs-sha256` 后，主策略在评分前自动组装；独立 CLI 使用同名参数。未配置时仍读取原 `source-coverage`。两种方式都会检查[历史时点输入](strategy-point-in-time.md)，并在发布前重查原批准文件和账本。

## v2 覆盖协议

覆盖工具已支持 v2。主策略新版读取、评分产物和 Python 消费者仍在贯通，当前不切换正式配置。旧文件继续按 v1 核验，不能只换版本号或沿用旧键摘要。

v2 清单使用 `java.strategy.source_coverage.v2`，批准输入使用 `java.strategy.coverage_inputs.v2`。两者还须明确：

```json
{
  "inputSchema": "java.strategy.inputs.v2",
  "keyEncoding": "java.strategy.source_keys.v2"
}
```

每个来源的 `businessKeys` 必须是下表对应的有序数组；其他证书、范围、上游和摘要要求保留。

| 输入 | v2 完整业务键 |
| --- | --- |
| stock_daily、l2_raw、etf_factor、etf_daily | trade_date, instrument_code |
| stock_metadata、etf_reference | instrument_code |
| etf_portfolio | etf_code, announcement_date, report_period_end_date, constituent_code |
| sentiment、market_context | trade_date |
| calendar | exchange, calendar_date |

v2 日期键只能是规范 `yyyy-MM-dd`，Java 调用也可提供 `LocalDate`；不接收 `YYYYMMDD`、时间戳或猜测时区。标识必须是非空、无首尾空白的文本。ETF 与成分证券不能压成一个键。

先把 `["java.strategy.source_keys.v2", 来源角色, 有序字段名数组]` 编码为紧凑 JSON，写入 4 字节大端长度和 UTF-8 内容；随后按 v1 同样方式写入排序去重后的键值数组，再算 SHA-256。即使值相同或来源为空，不同角色、字段或版本也不能复用摘要。

`build-coverage` 根据批准输入版本生成相同版本的清单。v2 必须提供新的独立批准输入及摘要，原 v1 文件不覆盖、不重签；发布前仍重查新批准文件、原证书、历史证据与账本。单元样本不是生产批准材料，协议测试通过不证明真实源覆盖已完成。
