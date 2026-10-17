# 数据目录接口

启动数据 App 后，可以调用接口查看当前 QuestDB 有哪些表和视图、各自有什么用、带哪些标签，并下载目录 CSV。

```sh
./gradlew webRun
```

默认服务地址为 `http://127.0.0.1:8080`。请求沿用现有 `Authorization: Bearer <app.web.api-token>` 鉴权，连接和账号仍在根 `config/application.yml`。

## 可调用的接口

| 请求 | 返回内容 |
| --- | --- |
| `GET /api/v1/data-catalog` | 当前目录，含对象名、说明、多个标签、来源分类、行数和 MV 信息 |
| `GET /api/v1/data-catalog/objects/mv_backtest_daily` | 指定对象的详情、字段含义、时间语义和业务键 |
| `GET /api/v1/data-catalog/tags` | 标签 ID、中文名及当前对象数量 |
| `GET /api/v1/data-catalog/export?format=csv` | 下载完整目录 CSV，每个对象一行 |

JSON 中 `items[].description` 保存中文说明、市场、来源分类和多个标签；行数、对象类型和刷新状态是同一条目录记录的动态字段。返回的 `observedAt` 表示采集元数据的时间。

## 筛选和导出

列表和导出都支持这些参数：

| 参数 | 示例和含义 |
| --- | --- |
| `market` | `CN`、`US`、`BTC`、`SHARED` |
| `sourceType` | `COLLECTED` 采集、`DERIVED` 衍生、`GENERATED` 运行生成、`UNKNOWN` 未分类 |
| `objectKind` | `TABLE`、`VIEW`、`MATERIALIZED_VIEW` |
| `tag` | 可重复，例如 `tag=CN&tag=ETF`，要求同时具有两个标签 |
| `keyword` | 按对象名、名称、用途、业务分组和来源搜索 |
| `includeMissing` | 默认 `false`；`true` 时同时展示代码已登记但实库缺失的对象 |

列表支持 `page` 和 `pageSize`，默认第 1 页、每页 100 条，最多每页 500 条，按对象名排序。导出包含筛选后的全部对象，不按列表页码截断。

下面假定 `APP_WEB_API_TOKEN` 已设置为服务使用的 token。实际值不写在文档中。

```sh
# 查看 A 股 ETF 数据。
curl -f -H "Authorization: Bearer ${APP_WEB_API_TOKEN}" \
  'http://127.0.0.1:8080/api/v1/data-catalog?tag=CN&tag=ETF'

# 导出同样范围的目录。
curl -f -H "Authorization: Bearer ${APP_WEB_API_TOKEN}" \
  'http://127.0.0.1:8080/api/v1/data-catalog/export?format=csv&tag=CN&tag=ETF' \
  -o data-catalog.csv
```

CSV 为带 BOM 的 UTF-8，保留原分类清单的 12 列并追加“标签”。标签以分号连接，按 ID 排序；未知行数留空。此接口导出的是数据目录，业务表内的明细数据仍通过已有的受限读取接口获取。

## 说明和标签怎么维护

说明和标签维护在代码中，运行时不依赖此前人工生成的 CSV：

- A 股及国内数据：`market-cn` 的 `CnDataDescriptions`。
- 美股数据：`market-us` 的 `UsDataDescriptions`。
- 共用数据：App 的 `SharedDataDescriptions`。
- 标签 ID 和中文名：`data-core` 的 `CatalogTag`。

一个对象可以有多个标签。添加说明时登记对象名、用途、来源分类、市场、上游和标签；App 统一组装，重复登记会报错。后续新增 BTC 对象时可按同样方式增加对应市场登记。

实际对象和统计每次从 QuestDB 系统元数据发现。新表未登记说明时仍显示为 `UNCLASSIFIED`；被删除的表默认不再出现。MV 通过原生 MV 元数据识别，并展示实际基表、刷新方式和刷新事务，不能按名称猜测。

目录请求只读元数据，不扫描业务表计算行数，也不刷新 MV、补数据或启动同步任务。统计不可取得时保留未知值。目录可见和 `readSupported` 不代表数据已经完整或最新，既有读取守卫继续生效。

查询超时配置为 `app.data-catalog.query-timeout-seconds`，默认 10 秒，允许 1～60 秒。数据库不可用返回 `503`，无效筛选返回 `400`，完全未知的对象详情返回 `404`。
