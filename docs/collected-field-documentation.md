# 采集模型字段注释

CN 的来源字段说明维护在 [collected-fields.json](../markets/cn/src/main/resources/model-docs/collected-fields.json)。模型中文注释、目录详情接口和离线导出读取同一份资料；运行时不访问 Tushare 网站。

目前覆盖 70 个采集相关存储投影、1360 个字段。1285 个字段已对应公开文档，37 个是本地产生或补充列，38 个保留待核实说明。范围包括股票、基金、财务、资金流、两融、指数、期货和国内宏观。QMT、文件导入、ChinaBond 和本地衍生产品不套用 Tushare 注释。

## 在代码里看

例如 [DailyRow](../markets/cn/src/main/java/com/zoutrankil/data/domain/stock/market/table/DailyRow.java)：类上记录接口和官方链接，字段上记录中文含义、来源字段、来源类型、明确公开的单位、日期格式和本地转换限制。`daily.vol` 为手，`daily.amount` 为千元；未明确的单位留空，不凭字段名补成元或百分比。

现有 `Instant`、字段名、字段顺序和原 schema 快照日期都保留。文档的来源类型不等于 Java 类型或数据库类型；来源日期也不一定是真实事件时刻。`update_time` 等本地列不能冒充官方输出字段。

## 更新与导出

先对照官方文档更新 JSON，保留接口名、URL、核对日期和原文摘要 SHA-256。官方页面的当前字段不能自动覆盖历史来源契约、单位换算、时间处理或已验收的写入规则。

```sh
# 只刷新已有生成模型的注释，不删模型，不依赖原 schema catalog。
python3 tools/generate_domain_models.py --market cn --annotate-existing

# 离线导出 JSON、字段 CSV 和说明；目标必须是新目录。
python3 tools/generate_domain_models.py --market cn \
  --export-documentation outputs/models/CN字段注释
```

完整模型重生成仍需 `--catalog` 和明确的 `--market`。选中 CN 后会在写入前一并校验字段说明，避免生成后丢失注释或只改完一半。模型和说明不一致时整批停止。

每个对象的 `category` 和 `modelPackage` 记录分类与 Java 包。CN 来源模型按这份分类生成；CSV 同时导出分类和模型包，方便从官网条目定位代码。没有来源字段资料的模型，catalog 要显式提供 `category`。目录分类不改变来源、表名或业务契约。分类对应见[模型目录](model-schema-conventions.md#模型目录怎么分)。

## 三种核验状态

| 状态 | 含义 |
| --- | --- |
| `VERIFIED_SOURCE` | 接口和字段在核对时的官方输出参数中存在；不证明账号权限、来源完整或实际值正确 |
| `LOCAL_RULE` | 本地产生、转换或其他路由补充的列；说明具体依据，不称为 Tushare 原字段 |
| `UNVERIFIED` | 官方缺项、旧文档失效、接口不匹配或来源未确认；不猜含义和单位 |

当前待核实的 38 个字段分布如下：

| 对象 | 字段数 | 原因 |
| --- | ---: | --- |
| `dc_index` | 11 | 当前官方页面为 `dc_daily`，与现有 `dc_index` 接口及字段不同 |
| `moneyflow_hsgt` | 7 | 原 `doc_id=47` 返回 404；现有来源契约继续保留 |
| `rmb_index_daily` | 7 | 未找到与这组本地组合字段对应的 Tushare 官方接口 |
| `etf_share` | 2 | 当前文档未列出 `fund_type`、`market`；已存在的探针和运行规则不因此删除 |
| `fina_indicator` | 11 | 历史模型额外的 `yoy_*`、`growth_*` 字段不在当前官方输出列表中 |

`index_weight` 同时有 Tushare 和中证 XLS 路由，名称等补充列单独标注。现有 `cn_bond_yield_curve` Java 来源为 ChinaBond，目录说明已纠正。

## 接口怎么返回

`GET /api/v1/data-catalog/objects/{对象名}` 的每列新增 `sourceDocumentation`，包含提供方、来源字段、来源类型、说明、单位、来源格式、官方链接、核对日期、状态和本地规则。

已有 `meaning`、`temporal`、数据库类型和可空声明继续使用本地契约与实库结果。来源说明只补缺，不覆盖本地契约；未核实的说明不填入确定的字段含义。实库缺失的对象仍不伪造物理字段。

本轮只浏览公开文档、更新注释和导出元数据，没有调用 Tushare 数据接口或执行数据库写入。

## 本轮验证

2026-10-19：20 个生成器离线用例通过；字段资源加载、目录服务、公共装配和 CN 入口相关测试通过。70 个模型去掉注释后与原代码一致，导出 JSON 和 1360 行 CSV 与维护资料一致。模块边界、依赖兼容、Batch 驱动隔离和根/CN 数据/主策略/实时 L2 发行包构建通过。

`architectureTest` 被当前仓库缺少根架构测试源码拦截，本轮没有绕过检查。依赖已删除历史快照的 184 模型生成用例已清理；当前生成器全部 20 个用例通过。没有进行真实来源采集或数据完整性验收。
