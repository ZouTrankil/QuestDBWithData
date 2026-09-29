# D005 分类冻结与单行业任务验收

状态：D005 running，人工 pending_review；尚不能登记整项完成。

## 定义与凭据

`IndexMembershipJobPlan` 定义 `data.index_member` v1，默认 INCREMENTAL。参数必须声明分类回执及 SHA-256、1..32 个唯一行业、CURRENT/HISTORICAL/BOTH。本地观察日期冻结为单日，不向不支持日期过滤的接口传日期。分类恢复重新读取有界原始文件，核实 SHA、SW2021/L2、完整页数/行数、唯一行业，拒绝修改后的文件及伪造 typed catalog。

`local-D005-plan-1034`：4 项测试通过，包含保存的真实 134 行分类范围绑定、未知行业、对象/文件漂移、错误端点/分类版本/完成计数/重复行业及超字节上限拒绝。

## 实际单行业执行

`IndexMembershipSliceJob` 每个 child run 只执行一个明确行业，建立 run/attempt/slice 三级账本，获取整数据集锁，冻结请求、来源、合并前快照及计划。实际回读全部 14 列与合并结果一致后才记录检查点和验证状态。非空有变化才暂存发布；无变化不写；完整空响应记 VERIFIED_EMPTY。历史 N 禁止写正式 index_member，现有 Python 调用方兼容性尚未迁移。

`local-D005-slice-owner-1037`：1 项实际测试通过、0 跳过，包含三个真实运行：

| 范围 | 来源 | 实际目标 | 写入 | 结果 |
|---|---:|---:|---:|---|
| 801011.SI，BOTH，首次 | 7 | 7 | 7 | VERIFIED，首次新增 7 |
| 相同范围重跑 | 7 | 7 | 0 | VERIFIED，7 条未变，表身份/所有原值不变 |
| 801217.SI，BOTH，空行业 | 0 | 7 | 0 | VERIFIED_EMPTY，原表完整保留 |

每个运行三级账本均为相应验证状态，锁释放，正式表前后快照一致。真实源 Y/N 每次独立限流请求；未重新取完整 134 行业的所有成员。

证据：[owner-readback.json](slice-owner-8cd23f9f66674a8195db5b74ae807eec/owner-readback.json)、其 sync-evidence 内原始响应及 completion.json、[独立核对](slice-owner-8cd23f9f66674a8195db5b74ae807eec/independent-source-values.json)。Python 独立校验响应 SHA 和完整性，按完整期间键比对 9 个原始来源字段，0 缺键、0 字段差异；同时核查 indexName、level 及两个非来源字段的空值。本次验收表成功后清理，独立脚本比较的是清理前实际 SQL 回读凭据。

1035 是首次及重跑验证；1036 因新增来源 opt-in 条件跳过，不能算实际验收。1037 同时启用 QUESTDB_WRITE_LIVE 和 TUSHARE_PAGE_LIVE，明确 tests=1/skipped=0/failures=0。

## 未完成

多行业串行协调及已验证行业恢复复用、owner 早期/发布后账本中断恢复、CLI/注册入口、sync 与 typed write 组合仍待接入及实际验证。单行业执行器目前不作为可调度 owner 注册，防止把尚未具备的批量执行/恢复能力对外宣称可用。实际修订样本尚未观察到，先前修订合并仅为受控测试。
