# D004 `ths_index` 来源和物理基线

状态：running；D003 已 verified。正式表和 Python 项目均只读；来源由 Java 共享限流客户端实际请求一次，未执行生产写入。

- 来源链：`ths_index_sync.py` 调用 `pro.ths_index()`，旧同步以整份快照发布；`update_time` 是本地同步观察时间。旧实现用“今日已同步”判断跳过，并在有限异常时把来源错误转为空结果，Java 实现必须区别失败和真实空响应。
- [Tushare 官方接口](https://tushare.pro/document/2?doc_id=259)声明可按 `ts_code`、`exchange`、`type` 过滤，单次最多 5000 行，一次提取全部数据且不应循环。D004 默认全目录请求参数为空；返回恰好 5000 行须保持截断未验证，不能默认为完整。卡片所列 200/分钟是 Python 装饰器和配置事实，不是账号授权额度。
- 真实 Java 来源请求：`local-D004-source-preflight2`，`ths_index` 一次无参数调用，返回 2517 行、2517 个唯一代码，六个声明字段齐全，未触 5000 行上限；12 行 `count` 为空，其余日期均为 `YYYYMMDD`。完整去敏响应见 `source-preflight.json`，没有保存 token。
- 正式 QuestDB `ths_index`：物理 ID 1754，目录 `ths_index_stage_20260912_022059_65d6f07d~1754`，2517 行、2517 个唯一代码、单个 `update_time=2026-09-11T18:20:59.807013Z`；MONTH/WAL、物理 dedup 键 `(ts_code, update_time)`，七列逐项类型见 `physical-baseline.json`。
- `source-baseline-comparison.json` 对全部 2517 个代码的六个来源字段比较：新增 0、目标独有 0、字段差异 0。当前真实来源没有新增或修订样例；后续首次非空写入验收需使用空的隔离 WAL 表，重跑同源检验幂等。若需要验证修订分支，只能注明受控输入测试，不能称为真实来源修订。

业务身份为 `ts_code`；物理键中的 `update_time` 是快照观察值，不是来源版本。任何删除都只可在经过完整来源和收缩检查的显式全量快照发布时发生。首次真实来源写入以隔离表进行，正式表持续只读。

补充验收：local-D004-mapping-0930 两项通过，2517条实际基线全字段mapper往返一致，包括700012R.TI和700050B.TI字母后缀。local-D004-source-0930 使用显式ts_code=700001.TI的一次请求返回1行，市场A、类型BB；原始响应和指纹保存在source-b3e33a5c-f088-486a-a239-15539e6c2f34。本次写库为0，尚未注册正式owner/writer。ThsIndexSource当前只接受显式代码或单市场/类型范围，全目录发现的准入方式须在job定义中另行明确。
