# 本地串行执行状态（2026-09-29）

当前：F001–F010已验收（10/201），第1批10项通过；下一项F011。全套91项82通过9跳过0失败。首批本地提交：8bd4f2d7f120f2773bb6a72c61a16e339f31fa53；未push。以下保留历史Orca故障，不代表当前阻塞。

用户已授权立即本地串行执行，持续监督，每10项验收通过做一次本地提交，然后继续；不push。Q系列仍按原有逐对象准入条件处理。

## 历史Orca启动记录

- Run：`run_d6a2ce95481a`。
- F001对应Task：`task_f36bf16ecd30`。
- 当前阻塞：Orca Agent启动/派发环境；F001未获得执行证据，未做QuestDB查询或sync。
- 已验收任务：0/201；当前批次：第1批F001—F010，0/10；本次提交：0。
- 不存在正在执行本计划代码任务的已确认worker，不声称后台会自动推进。

| 尝试 | Dispatch | 实际结果 | 处置 |
| --- | --- | --- | --- |
| 1 | ctx_4775da700ab2 | structured会话dispatch_input未确认；transcript_required，session未附着；worker-show报告exited | 原dispatch弃置并release，未执行任务 |
| 2 | ctx_4cc6af3d2ce3 | 同一Task重试，structured会话仍未附着、输入未确认 | 原dispatch弃置并release，未执行任务 |
| 3 | ctx_83372639feda | 改用本地Orca管理的Codex终端接管；agent_readiness失败，terminal_exited | Task为failed；release返回no_owned_resource；无reclaimable资源 |

第三次终端为 `term_8866136f-90a9-4a39-8a8d-cef1f6afa46f`，最终Orca记录orphaned=true、connected=false、exitCause=operator_close；本轮未调用terminal close，不能据此推断具体操作者或根因。

已尝试读取状态、同Task重试、Orca managed Codex hook trust修复和本地终端接管。不能把终端屏幕能显示、Task已登记或输入accepted作为Agent真正执行的证明。

## 恢复约束

本轮读取 `orca skills get orchestration` 和 `references/recovery-and-cleanup.md`。恢复规范原文：

> After three consecutive failures for one Task, its dispatch context circuit-breaks and the Task is failed. Do not route around that boundary with a new Run or an unrelated Dispatch.

因此不继续新建Run/无关Task绕过失败。需先恢复本地Orca会话附着、状态回报与稳定就绪，再按同一任务的受支持恢复路径继续F001。

## 后续监督及提交规则

1. 继续前读取本文件、manifest和completion-register，确认当前任务与实际Orca Task/Dispatch映射，不从F002跳过F001。
2. 同时只有一个当前任务worker；验收通过后立刻派发下一卡。worker提前停下时核实退出与证据，再恢复当前任务，不能静默跳项。
3. 每10项验收通过，运行该批适用检查、审查diff、只暂存本批任务产物并本地commit；末批不足10项完成后也提交。不得git add .或把用户无关修改捎入提交，不push。
4. 每批记录任务ID、commit hash、验证证据和未解决项；写入ACK/fixture/只生成record不计完成。
5. 完成登记中的human_review保持pending_review，由用户逐表复核。

## 原有修改保护

已记录并复核build.gradle、application.yml、已有QuestDbLiveSmokeTest.java的SHA256，启动尝试前后均未变化。
本地恢复收据和基线在 `var/orca-migration-20260929/`（gitignore覆盖，未写入凭证）。本次只新增执行记录，不改变数据库或Java实现。

## 后续启动原因核查

进一步只读检查Orca会话登记、journal.db和对应Codex原始会话后，发现比CLI回执更具体的证据：

- 第一次Codex原始会话确实出现task_started，随后226ms即turn_aborted，reason=interrupted；没有模型执行任务的输出或工具调用。
- 第二次同样出现task_started，随后308ms即turn_aborted，reason=interrupted。
- 两次Orca journal都记录dispatch.state=unknown、reason=provider_closed_before_acknowledgement。
- 两次会话lease的deathEvidence.detail均为 `the last surface holding this session released it`，claimStatus=released、ownerProcess=null。这些记录形成于后续协调器worker-stop/abandon之前，不能归因于协调器稍后的清理。
- 因此“没有启动”应更准确表述为：底层Codex turn短暂启动后立即被中断，Orca后台会话在派发确认前已关闭，项目任务没有实际执行。
- 第三次daemon.log记录00:53:07.512（北京时间）session-exited，code=1；紧接着1ms后记录session-killed。退出码不能单独判断是程序崩溃还是关闭路径引起，也不能凭operator_close标签认定用户手动关闭。

判断：前两次明确是Orca/Codex会话生命周期中的提前释放/中断，存在后台worker会话引用保留异常的迹象；具体哪个代码分支触发最后surface释放，现有日志尚未证明。未发现Java编译、QuestDB连接或Tushare调用导致本次启动失败的证据，因为这些步骤没有执行。

只读证据位置：Orca `agent-sessions/agent-sessions.json` 中两次session记录；`agent-session-journal/.../journal.db` 中epoch/submission/dispatch三条事件；Codex原始会话 `01a0e8eb-8498-71e0-a5d1-5ae3ce2ce591` 与 `01a0e8ec-429d-7cb1-9efd-6f2227806951`；`logs/daemon.log` 第481—487行。未复制dispatch capability或凭证到文档。

## 当前执行方式：本地直接串行
用户已明确取消Orca执行，改由当前会话直接完成。前述Orca恢复约束仅适用于历史Orca尝试，不阻塞本地执行。F001只读基线已verified，实际证据见artifacts/java-migration/F001/review.md。JDK24已补齐，gradlew test成功；保留每10项验收后本地提交、不push、人工pending_review规则。

F003定义注册与F004共享HTTP已验收，详见逐项结果。F004两次真实单股请求各1行，实际HTTP2复用连接；当前累计4项verified，未到10项提交点。下一项F005共享限流。

F005已验收：总预算+接口预算、重试计费、取消、有界并发和本机凭证锁接入真实HTTP入口。真实两次单股请求自动节流约6秒。累计5项verified，下一项F006。原有build.gradle/application.yml/integration用户文件SHA256仍一致。

F006已验收：日期/代码/分类计划、offset/cursor契约、有界逐页消费和触顶缩窗；两个真实stock_basic代码片各1行，失败边界测试和整套测试通过。累计6项verified，下一项F007。本轮观察到同目录其他写入，已保留并核对新增测试；尚未提交。

F007已验收：DatasetDefinition白名单、绑定过滤、有界投影、复合键游标与严格类型读取。真实QuestDB 10.0.1 PGWire的daily和etf_daily各4行跨2页，同日6个代码跨3页，与独立SQL逐字段一致；全套测试通过。累计7项verified，下一项F008。首次本地Java示例口令不能登录PGWire，测试进程改用Python项目既有本地.env连接参数；未改凭据配置。F007临时隔离测试表已清理，留有清理前证据。尚未到10项提交点。

F007已验收：通用typed有界读、完整键游标及现有stock_basic入口接入；真实QuestDB14行与独立查询一致。PG认证通过参考Python配置在子进程环境覆盖解决，用户application.yml未改，复现工具tools/run_local_validation.py。隔离完整测试54项、46通过/8跳过/0失败。累计7项verified，下一项F008。

F008进行中：DatasetWritePreparation与DatasetWriteReceipt已实现，写入前字段/键/行数/规范化字节限制及ACK/verified状态边界3项测试通过。传输字节上限、QWP分批提交、真实来源逐字段回读和未知写入核验待做；仍累计7项verified。

F008继续：完整键/全字段比对与未知sender终止门槛已补齐，11项定向测试通过。真实隔离WAL表2行分2批写入、QWP显式ACK、逐字段回读、幂等重放及空值修订1项live测试通过并清理表。来源是测试数据；真实Tushare验收和传输字节边界尚待完成，仍累计7项verified。

F008继续：真实Tushare两代码各1行写入后7字段一致，同键重跑仍2行；source-write.json保留源/写/读证据。真实错误分区、错误dedup键、多余列全部预检拒绝，0行且清理。字节估算和恢复边界仍待收敛，F008保持running，累计7项verified。

F008已验收：应用批次字节预算、串行背压、显式ACK和完整键/值/WAL核验；错误分区/键/额外列拒绝；未知sender保留IN_DOUBT。真实源2行回读/幂等及修订fixture通过，全套74项65通过9跳过0失败。累计8项verified，下一项F009，尚未到10项提交点。

F009进行中：单dataset版本化定义及参数/模式/区间/预算校验，不可变运行参数快照4项测试通过。注册依赖校验和已有入口接入待做，累计8项verified。

F009已验收：不可变版本化单数据job、typed参数与有界窗口、dataset/policy/模式和依赖注册校验、实际拥有者注册与CLI查看。8项测试通过；修复实际入口Duration序列化错误。样例disabled且只声明snapshot；本定义功能数据I/O为N/A。累计9项verified，下一项F010账本；10项后本地提交。

F010进行中：本地SQLite事务账本、run/attempt/slice层级、CAS状态和不可分割事件追加。4项真实SQLite测试通过，重开恢复、SQL投影一致、注入故障回滚、完成证据门槛。查询入口和真实回执持久化尚待完成，仍9项verified。

F010继续：只读有界查询与真实CLI入口、完整定义快照、父子完成约束通过测试。真实Tushare1行写后全值回读，账本保存并重开恢复3级verified和7个slice事件；8项账本测试及1项live测试通过。待全套复核后登记第10项并进行首批提交审计。


首批提交复核：已存在commit 8bd4f2d（338文件），本轮不重复提交。工作区F001–F010实现与提交一致；application.yml及原有integration smoke未纳入。独立索引导出测试88项78通过10未启用跳过0失败；实际写入证据仍以local-F010-final全套结果为准。开始F011互斥/执行链路复核。

F011继续：SyncJobRunner通用同步页消费、预检/互斥/验证/账本链路落地，定向runner和区间锁测试通过；冻结logicalDate不从写入时钟重新计算，来源异常不转empty。真实适配与未知写入联合验收尚待做，累计10项verified。

F011继续：StockBasicSyncAdapter显式代码列表和快照v2契约已实现。真实2代码各1行经统一SyncJobRunner完成取数/写入/回读/3级账本与锁释放，保留source及runner-readback证据，隔离表清理。正式入口与未知写入超时联合测试待补，仍10项verified。

F011已验收：通用执行器、正式v2手动入口、区间锁和未知写入保护；真实2行来源写后全值回读与三级账本通过，同范围重跑物理行仍为2。local-F011-final2全套103项94通过9跳过0失败；随后跨页重复键防护的定向回归通过，待下一次全套复核。累计11项verified，下一项F012，第二批1/10。

F012进行中：SQLite持久化取消与cancel-sync-run入口，runner逐边界观察取消；已验证片保留、未知写入不释放锁。恢复请求指纹包含完整配置/参数/日期/目标，测试通过。checkpoint与真实恢复待实施，累计11项verified。

F012继续：verified ledger slices作为持久化checkpoint，VerifiedSliceRecovery绑定完整请求/目标/源摘要；重新限量取数且当前目标值/WAL再验证后才跳过写入。恢复入口--resume-from已接入，第二页失败后只补第二页及漂移/未终止拒绝定向测试通过。实际恢复和未知提交核验待做，仍11项verified。

F012已验收：真实Tushare两代码首轮第二页注入失败，原run保留PARTIAL与一片VERIFIED；新run重新取数，按源指纹及当前QuestDB完整键/值/WAL复核首片，只补写第二片，7字段回读2/2一致，隔离表已清理。未知提交继续IN_DOUBT并保留跨进程区间锁，不因租约时间到期自动重放；取消请求持久化。local-F012-final全套113项104通过9跳过0失败。累计12项verified，下一项F013，第二批2/10；人工复核pending_review。

F012补充复核：local-F012-reopen-proof真实验证通过。重开账本、重建区间锁和执行器后恢复；实际send调用1次，已验证首片不重写，QuestDB全值回读2行一致。证据live-recovery-871ee9e0995e4800a94b01711ad1c0fd.json。继续本地串行执行，不走Orca。

F013进行中：版本化组合及SyncGroupPlan冻结共同参数/成员覆盖/窗口/逻辑日期，已接入串行执行器。local-F013-cancel-plan共12项通过、0失败、0跳过；SQLite父子账本、失败停止、恢复及持久化取消边界通过。外部组合链路与正式入口待验收，仍累计12项verified。复核见artifacts/java-migration/F013/plan-review.md。

F013继续：真实两成员组合失败/重开账本恢复通过，只执行未完成第二项；源响应与QuestDB实际回读7字段2行独立核对一致。local-F013-full-review为127项111通过16跳过0失败。生产恢复路径仍需对旧VERIFIED子任务重新核对当前目标值，F013保持running，累计仍12项verified。证据见artifacts/java-migration/F013/recovery-review.md。

F013已验收：生产组合恢复强制重新有限取源及QuestDB全值/WAL复核，复用凭据进入父任务记录；真实两成员恢复与目标漂移拒绝通过。local-F013-recheck-final全套128项112通过16条件跳过0失败；真实测试另行通过。累计13项verified，下一项F014批量读取，第二批3/10。人工复核pending_review，详见results/F013.json及artifacts/java-migration/F013/final-review.md。

F014进行中：ReadGroupRequest/ReadGroupReader实现独立typed结果、版本、错误和游标。4项核心测试及真实QuestDB双表独立分页/独立SQL对照通过；错误投影未掩盖为空页。修复Instant结果导出。正式入口和请求/游标解析待完成，累计仍13项verified。证据见artifacts/java-migration/F014/core-review.md。

F014已验收：read-dataset-group JSON入口支持逐成员定义、投影、类型化过滤及游标；严格拒绝歧义日期/精度/重复属性/不匹配游标。真实QuestDB双表JSON续页8行与独立SQL一致。local-F014-json-final-73d8全套138项121通过17条件跳过0失败；真实测试另行通过。累计14项verified，下一项F015批量写入，第二批4/10。人工复核pending_review。

F015进行中：WriteGroupRequest/WriteGroupPlan冻结有界多数据写入批次、目标身份、完整定义及行指纹，复用完整字段/主键/日期类型校验，拒绝View/MV。local-F015-plan-canonical-2a10共6项通过，未调用writer。执行器、恢复与真实回读待完成，累计仍14项verified。证据见artifacts/java-migration/F015/plan-review.md。

F015继续：PreparedWriteAdapter将冻结批次接入既有SyncJobRunner，保留SQLite状态/区间锁/写后核验，往返映射与目标身份变更发送前拒绝。local-F015-adapter-b72c共6项通过；重开账本恢复不重发、空批次不写。组级持久化关联及真实组合写入待验收，仍14项verified。

F015继续：PersistentWriteGroupRunner复用SQLite父子账本与单数据锁，真实Tushare两行分别写入两隔离表，第二项故障后重开恢复、重复恢复均通过，send各1次。原始来源与SELECT独立核对2表2行7字段一致，隔离表清理。未知提交拒绝自动重放的定向回归通过。正式入口及完整回归待完成，仍14项verified。证据见artifacts/java-migration/F015/persistent-live-review.md。

F015已验收：write-dataset-group JSON正式入口及恢复入口完成，目标由已准入owner解析，严格完整字段/类型/日期/版本校验。真实来源经JSON解析、双隔离表写入、故障恢复和重复恢复通过，2表2行7字段一致、send各1次。local-F015-final-8c71全套155项137通过18条件跳过0失败；真实测试另行通过。累计15项verified，下一项F016调度定义，第二批5/10，人工复核pending_review。

F016进行中：修复SQLite调度重入检查，将IN_DOUBT与CLAIMED共同拦截。local-F016-boundaries-84ef三项测试通过，覆盖重开存储、DST缺失/重叠时间、交易日与跨UTC逻辑日期。未启动真实定时同步；调度入口和runner结果关联待完成，累计仍15项verified。证据见artifacts/java-migration/F016/calendar-store-review.md。

F016继续：local-F016-service-728c共11项通过、0失败/跳过。覆盖定义导入与持久化启停、重复/非法JSON拒绝、限定misfire、PARTIAL/IN_DOUBT保留、重开后的禁止重放，以及独立SQLite连接同槽并发只认领一次。未启动真实sync；正式命令和实际runner关联验收待完成，仍15项verified。证据见artifacts/java-migration/F016/management-boundary-review.md。

F016继续：真实调度→既有runner→Tushare→QuestDB链路通过，1行7字段独立核对一致，重开同槽不重发；完整回归170项151通过19条件跳过0失败。CLI及job/group服务路由验证通过。复核新增分钟间隔发现370槽扫描截断，已扩至有界7天分钟槽并补回归。证据见artifacts/java-migration/F016/live-dispatch-review.md；最终登记待复核，仍15项verified。

F016已验收：调度定义/启停/时区/日期适用性/有界misfire/重入/下次时点/结果历史及CLI完成。真实1行7字段回读一致，重开同槽不重发。最终回归{'tests': 173, 'failures': 0, 'errors': 0, 'skipped': 20}。累计16项verified，下一项F017，第二批6/10；人工复核pending_review。

F017已开始：统一参数解析拒绝重复选项、空值和非法名称，避免后一个参数静默覆盖同步范围或启停值。local-F017-options-416b共10项CLI测试通过，无失败/跳过。统一plan/validate/history等管理能力待完成，累计16项verified。
