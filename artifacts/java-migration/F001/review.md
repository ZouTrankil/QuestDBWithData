# F001 本地基线核对

2026-09-29，执行方式改为当前会话直接本地执行。历史 Orca 失败保留，不再作为本地任务阻塞条件。

## 实际证据

`baseline.json` 保存查询时间、数据库版本、全部实际列定义、样本查询及摘要；`disposition.md` 逐对象登记。184 个对象存在且有 Java 投影，184 次有界数据读取成功，182 个非空，2 个为空；相对原审计无列名/类型变化。空表不是迁移写入验收通过。14 个原缺失模型仍单独列出，不自动建表。

生成器为 `tools/generate_domain_models.py`，默认输入 `schema-export/questdb-qdb-2026-09-28/domain-catalog.json`。生成的 record 仅反映物理结构，不等于业务定义、来源、完整键或同步实现。此任务未改生成文件。每个对象的 Python 模型、sync 函数和 source_refs 调用证据保存在 JSON；这些代码引用不等于 owner 已确认，未知 owner 保持待确认。退役候选仅是审查建议，不授权删除。

## stock_basic 现有链路

`cli/CommandLineRunner` → `StockBasicSyncService` → `SchemaMigrationService` / `TushareClient` → `StockBasicMapper` → `QuestDbStockBasicRepository`。客户端当前只请求 list_status=L，按响应 fields 映射六列；list_date 转 LocalDate。仓库写入 java_tushare_stock_basic_qwp_test，通过 latest 视图读取；snapshot_ts 是运行日 UTC 零点快照。该业务契约与 stock_detail_info 不能直接等同。

现有验收仅等待快照行数达到预期，不能证明完整业务键、逐字段一致和无旧数据干扰。现有接口一次加载来源和读取结果，无共享限流、分页完整性、持久化增量 checkpoint、任务取消恢复或未知写入核验。分别交给 F004–F012，不冒充已实现。读取入口会触发迁移，不用于此只读基线审计。

## 容错与复现

首次样本查询发生 20 秒超时。审计已增加明确超时记录和逐对象 partial 证据；失败对象继续登记，最终有错误时返回非零。重新执行全部 184 个对象成功，无重试写入，因为全部是 SELECT。样本 LIMIT 1 仅限定返回行数，不保证视图执行成本；20 秒请求超时限制客户端等待。

在 Python 项目 `D:/work/fund_2/back-monitor` 执行：

```powershell
uv run python C:/Users/zouqiang/IdeaProjects/QuestDBWithData/tools/audit_migration_baseline.py --workspace C:/Users/zouqiang/IdeaProjects/QuestDBWithData --python-project D:/work/fund_2/back-monitor
```

安装本机缺失的 JDK 24.0.2（OpenJDK 官方归档，下载包 SHA256 已匹配），不修改项目要求的 Java 24。在 Java 项目执行：

```powershell
$env:JAVA_HOME='C:/Users/zouqiang/.jdks/jdk-24.0.2'
.\gradlew.bat test --console=plain
```

结果 BUILD SUCCESSFUL；6 项测试通过，3 项需显式启用的 live smoke 跳过。实际数据库读取证据来自独立只读审计，不来自跳过的测试。F001 的 source sync、写入、checkpoint、幂等重跑验收均 N/A（只读基线任务）；后续数据任务必须另行证明。人工复核 pending_review。
