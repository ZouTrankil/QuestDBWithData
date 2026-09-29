# D005 index_member 实际基线与待实现约束

已开始本地串行执行；D004 实际验收记录存在。D005 为 running，尚未新增来源请求或数据库写入。

## 实际 QuestDB

`physical-baseline.json` 由 tools/audit_index_membership.py 的只读 SELECT 生成：5902 条，131 个行业，全部 L2/is_new=Y；14 列，YEAR/WAL，无 dedup，主时间 update_time。物理表 id=1760，目录 index_member_sw2021_stage_20260912_035448~1760。

观察时间范围 2026-09-11T19:54:48.297652Z 至 19:54:57.559719Z。有界100条全字段样本；自然身份(index_code,ts_code,in_date)重复查询未返回记录。样本 out_date 为旧字符串 None，con_code/weight 为 null，必须显式兼容，不能填0。

## Python 实际行为

只读入口：D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/connectors/index/index_member_sync.py；模型：src/quant_platform/data/adapters/questdb/models/stock/fundamental.py 的 IndexMember。

- index_classify(level=L2,src=SW2021)，然后逐行业 index_member_all(l2_code=...)。
- 后者没有显式 is_new，虽有“完整历史”注释，不能据此认定抓取历史完整。官方文档默认 is_new=Y，与现有表全Y相符。
- collect 函数收集所有行业 DataFrame 再合并，不符合本次逐片有界处理要求；Java 应逐个冻结行业请求、取数、验证和记录checkpoint。
- Python 将网络/值错误转成空结果、空行业跳过；Java 不得复制为成功。MAX(update_time)等于今天也不证明所有行业完成。
- Python 按(index_code,ts_code,in_date,out_date)去重；out_date会随退出修订，不宜作为不可变身份，否则可能保留同一次纳入的旧未退出行。候选自然键为(index_code,ts_code,in_date)，须用当前/历史来源进一步验证。

## 来源契约与字段注意

[官方 index_member_all](https://tushare.pro/document/2?doc_id=335)：单次最多2000行，支持l1/l2/l3_code、ts_code、is_new；默认Y。未声明日期窗口或offset/limit，不能伪造分页；触顶须拒绝不完整请求，必要时显式细分到L3/股票，不能悄悄当完整行业。

- 默认增量以明确L2列表为分片，并显式声明Y/N范围；当前与历史分开请求记录，不能把Y结果当历史全集。
- in_date→membership_start_date LocalDate；out_date→membership_end_date LocalDate；update_time→observed_at Instant(UTC微秒)。有效期端点是否包含仍保留原始日期，不擅自推导交易区间或PIT。
- weight不在这个来源输出字段内，存量为空不代表0权重；con_code同样不凭ts_code截断造值。
- 源l1/l2/l3代码需要在DTO和来源证据保留；现表只保留名称。若同L2/股票/纳入日出现多个不同L3分类，必须报告键冲突，不能覆盖丢失。
- 物理无dedup，不能直接追加重复历史。需有界内容增量合并、保留缺席旧记录，隔离暂存全字段核验后发布；具体分片发布/恢复方案在实现时冻结。

后续：真实有界L2分类与成员Y/N请求，验证自然键、源代码和存量14列映射，再接入读写、owner、组合、管理及实际验收。当前不标记verified。
