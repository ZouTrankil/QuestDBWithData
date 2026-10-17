# 评分来源核验

新评分必须提供独立核验过的覆盖清单。没有清单、缺证券、重复键或凭证变化，都会停止发布。服务和评分 CLI 共用这项检查。

配置放在根 `config/application.yml` 的 `app.sync.batch`：

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

上游自动产出并批准清单的链路还没接通，本次没有生成生产清单。默认配置为空，所以新评分会明确停止；不能据此说正式盘后链路已经跑通。已有评分和历史时点资料也不因此自动获得完整性证明。
