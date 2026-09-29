# 公共执行契约

适用范围：本目录全部任务。这里是实施要求，不代表已实现。每个数据一个任务；每个功能一个任务。

## 串行与范围

1. 同时最多一个Orca Dispatch修改本计划工作区。按manifest的primary_order执行；serial_after是顺序前置，不代表所有前序数据均是业务依赖。
2. 每个任务只改自身功能或单个数据的DTO/domain/key/schema/mapper/repository/sync/定义/测试；共用注册文件只添加该项。公共框架缺陷回到对应功能任务修复，禁止顺手迁移其他表。
3. 保留当前build.gradle、application.yml和integration测试等已有修改，先git status再工作，不覆盖、不回滚、不自动提交或push。
4. 自动生成record是schema projection，不能作为业务定义完成证据；先检查生成器输入与映射层，不手改生成文件。遵守仓库分包和Flyway约定。
5. 验收使用隔离测试库及有界样本；默认不改变现有生产表、不启动定时同步/实时订阅/实盘。迁移代码应可审阅且具备显式执行入口。

## 每个数据的完整交付（D01—D09）

- D01：冻结source→DTO→domain→persistence逐字段映射，列出缺失/新增字段、单位、空值、时区、精度、日期语义及schema版本。
- D02：明确业务Key与物理UPSERT KEY，包含真实版本/类别/期间维度；旧物理键是审计事实，不是自动接受的目标。
- D03：给出主时间列、WAL/dedup、分区及DDL迁移/兼容方案；无分区/无dedup必须说明原因。普通View的分区/写入标N/A，MV键为聚合粒度，不能伪造UPSERT KEY。
- D04：完成单数据typed read：显式列、按键与范围、稳定游标、有界页、空值/精度可往返；可被read group调用。
- D05：完成单数据typed write：批大小和字节上限、背压、键校验、幂等、ACK与验证分离；可被write group调用。View/MV拒绝直写并给出基表刷新入口。
- D06：实现真实来源sync模式，登记支持的incremental/backfill/snapshot/reconcile/materialize/ingest，unsupported必须拒绝。逐片逐页取数处理，不全历史聚合到List/DataFrame；物理写分批不能替代网络取数分片。
- D07：登记一个canonical SyncJobDefinition与DatasetDefinition，引用共享限流/分片/重试/账本/runner。提供单数据job示例，可由组合引用，不新建另一套调度/状态文件。
- D08：分页结束、窗口完整、来源版本、覆盖区间、逐键值及未知写入核验均有证据；checkpoint只在规定的完成条件后推进。
- D09：提供有界request、预期response、run/status/resume示例及测试证据；同时核对Python真实语义，不直接复制其错误分支。

## 限流、分页与组合约束

- 卡片的Python限流数是源码事实，不是当前账号授权额度；执行时以账号/接口限制配置为准，可调低，不能凭卡片调高。
- 限流放在真实HTTP尝试边界；普通/VIP路径、重试和多线程共同计费。开发任务串行不妨碍单任务内部受控并发，但必须共享预算、有界队列和可取消等待。
- 配置rate_limit与connector装饰器不一致时分别保留证据，先使用已确认约束中的保守值并查明实际生效路径。例如etf_daily清单配置25而请求装饰器200/60s，不能直接选较高数值。
- 不支持offset的接口不能强传参数。满页/重复页/游标不动/缩到最小窗口仍可能截断时，拒绝完成，保留未验证片。
- Python已有的按日、按股、按季度/分类、30天缩7天模式分别迁移。宏观整段请求和FRED全CSV后过滤是需改进的事实，不声称已符合有界取数。
- group引用单数据job，不复制业务逻辑。父run保存有序子run，默认串行fail-fast；partial/in_doubt/failed不可被聚合为success。
- ReadGroup逐数据返回结果与来源版本，不承诺跨查询同一原子快照；WriteGroup逐数据验证，不承诺跨表原子提交。

## 验收与任务结果

- 适用测试：列映射、非法日期/时区、完整Key碰撞、幂等重跑、分页边界、限流、失败恢复、WAL未可见、源修订。只做与本项相关的测试。
- 不以生成record、HTTP 200、ACK、行数相等、MAX日期或exit 0单独证明完成。
- evidence目录建议为artifacts/java-migration/<task-id>/，保存变更、命令及结果、键和值对照、边界及未解决项；禁止记录token或账户敏感值。
- 完成报告写明implementation_status与data_validation_status。未完成真实来源验证时只能说明测试范围，不声称生产数据就绪。
- owner/来源/键/日期语义未确认时结果为blocked；不得用猜测实现凑齐验收，也不得自行跳过串行前置。
- 退役候选任务可以交付有证据的retained_compatibility或retired_with_evidence决策，但不可把not_needed决定伪装成实现完成；删除仍需满足原调用方切换及审计保留条件。

## 增量与真实QuestDB验收（用户追加，优先于较弱验收描述）

- 默认incremental：读取已验证checkpoint及QuestDB实际已有业务区间，按数据的日期/公告期/修订规则生成有限重叠窗口；不能只取MAX日期后加一天而漏掉旧期修订。首次空库采用显式有界bootstrap，不默认全历史。
- 每片实际调用来源sync，有有效记录才校验、分批写入；返回0行不调用空写，不填零、不造行。来源错误与正常0行严格区分。
- 实际source返回行、规范化后行、拒绝行、已存在未变化行、插入/更新行分别记录。写入端若无法可靠区分插入/更新，标unknown并用前后回读核实，不以submittedRows冒充insertedRows。
- sync返回数据后必须查询QuestDB实际目标表，按本次完整业务键和范围显式SELECT全部业务列，比对源规范化值、空值、单位、精度、日期、版本和重复键。COUNT/MAX仅辅助，tables()元数据不能替代实际数据读取。
- 回读有界轮询等待WAL可见，记录deadline；超时结果是in_doubt/verification_timeout，不是成功。重放前核查旧writer结束、attempt和数据证据。
- 首次数据任务验收至少包含一片非空真实来源样本的sync→write→QuestDB read证据，以及相同范围重跑幂等证据。若当前增量0行，可使用已声明的有界历史窗口作额外验证；无合法样本/权限时记implemented_not_verified或blocked，不能写complete。
- 已通过首次非空验收的日常运行允许verified_empty，但必须有正常来源响应、有效请求范围和原checkpoint；仅记录此次无新增，不伪造写入成功或推进未知覆盖。
- 再执行增量，核查已验证片复用、修订片重取和旧记录变化；有真实新增/修订时必须写入并回读。没有真实修订样本时注明验证边界，不能伪称验证过修订行为。
- View验收查询View实际数据并对照基表；MV验收有效刷新状态、覆盖区间及实际输出与基表聚合。内部元数据/结果表的sync含义为真实owner的ingest/materialize，仍要求持久化后真实回读；不能虚构Tushare调用。
- 测试库也必须是真实QuestDB实例，记录targetId、版本/表及隔离方式。模拟HTTP、mock repository或纯record测试只能算实现测试，不能替代数据验收。不得把测试样例写入生产数据表。
- 公共功能按职责验收：F001只读实际schema和有界数据，不要求新增sync或写入；日期/模型/读取功能用实际QuestDB读取样例核对；HTTP/限流/分片功能以真实有界来源请求加独立失败测试核对，写入验证复用已有stock_basic隔离链路。写入/runner/组合功能必须包含实际QuestDB写后回读。不能为公共功能强行新增别的数据任务；不适用项写N/A及理由，不能写伪通过。

## 每任务容错与完成记录

- preflight先检查来源权限、接口能力、时间参数、表结构/键、WAL与连接、任务冲突、资源预算及依赖。缺前置时尽早blocked，禁止启动无界同步碰运气。
- 网络超时/限流按有限次数和总时长退避；权限/参数/schema错误不盲重试；取消/崩溃保存到最小已验证片。缓冲队列、页数、窗口和回读等待均有上限。
- 页内/跨页重复、截断、源版本变化、坏行、键碰撞、日期漂移、部分写入分别可观察；错误行隔离并计入partial，不能静默丢弃后仍成功。
- 源取数失败和写入未知分开恢复；没有安全修复路径则保留现场及失败记录，后续串行任务不自动开始。
- 每个任务都必须更新本计划的completion-register.md对应行和results/<task-id>.json；完成时间、运行ID、目标表、请求窗口、增量checkpoint前后、源返回行/写入行、回读匹配结果、容错验证、证据路径及人工比对状态均不得漏填。
- 只有实际QuestDB验收通过、适用容错项通过且记录已更新，才允许任务状态verified；用户逐表复核状态独立为pending_review/accepted/rejected，AI不得代填accepted。
- 不保证未知来源一定能成功；任务必须交付有证据的verified或明确阻塞原因，不能通过降低验收条件制造完成。

## 持续查找Python依据

- Python项目根目录：D:/work/fund_2/back-monitor，所有任务均可持续只读查找；不要依赖本卡有限引用作为唯一依据。
- 同步注册/配置：src/quant_platform/data/adapters/connectors/registry.py、src/quant_platform/data/adapters/config/tables.py及table_definitions/。
- 同步实现：src/quant_platform/data/adapters/connectors/；模型：src/quant_platform/data/adapters/questdb/models/。
- 读写/SQL：src/quant_platform/data/adapters/questdb/、src/quant_platform/common/persistence/；衍生：src/quant_platform/data/adapters/materializers/。
- 任务与状态：src/quant_platform/common/runtime/、src/quant_platform/data/adapters/local_files/、src/quant_platform/data/application/sync_completion.py。
- L2：src/quant_platform/data/composition/level2_pipeline.py、src/quant_platform/data/application/level2_features/及connectors/level2/。
- 查找命令示例：rg -n '表名|sync函数名' src scripts config docs（cwd=Python根目录）。路径/入口不存在时沿registry及实际caller追踪，不能猜测替代API。
- 用读取时的代码为准，报告已发现的源码与审计快照差异；Python只读参考，不从Java任务顺手修改Python生产链路。
