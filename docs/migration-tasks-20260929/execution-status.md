# 本地任务执行状态（2026-09-30）

范围切换前的历史计划曾覆盖 F001–F017、D001–D084；此前条目的状态继续保留在全局清单，当前执行范围以用户最新指令为准。

最新用户范围：按目录顺序串行执行 D085–D184（10-l2、11-derived、12-factor、13-strategy、14-runtime，共100项）。用户明确覆盖任务卡中 D084 的跨范围前置安排；D007–D084保持原状态。

D085 `l2_dataset_manifest` 已完成隔离验收：本地QuestDB隔离表创建成功；2026-09-21至23回填、同范围幂等重跑、覆盖至09-24的增量运行均为VERIFIED。累计提交10行，隔离表4个唯一键；独立typed read逐字段比较16列、4/4匹配。取消边界与D085及共享批处理边界定向测试通过。正式表未修改。结果和证据见 `results/D085.json`；人工复核pending_review。D086 `l2_daily_features` 已完成：隔离表 `java_d086_l2_daily_features_acceptance_final8_20260930` 共4行，110列×4的独立源对照全部一致；首次回填、幂等重跑、三日重叠增量和精确resume均VERIFIED，源取消/指纹漂移检查及job/run/slice管理入口通过。主工作区编译和全应用启动仍受未完成ETF类型及final Repository代理问题阻断；D086测试在临时隔离副本通过，未将编译垫片带回。正式表未修改，详见 `results/D086.json`；人工复核pending_review。按序转入D087 `l2_intraday_bar_features`。

连接恢复：已定位本机Windows服务 `QuestDB`，配置为 `D:/tool/questdb/db/data/conf/server.conf`。localhost:9000 的 SELECT 1 与 localhost:8812/qdb 的 PostgreSQL只读查询均成功；认证值从本机服务配置/注明的默认值读取，仅通过当前子进程环境覆盖Java项目中的占位值。D007–D009隔离验收已重新启动，最终结果待运行完成。

范围切换前的进度快照：F001–F017、D001–D010、D013–D014已验收，共29项；其余条目按全局清单原状态保留。人工复核pending_review。

本次 D085–D184 按目录串行推进，不启动并行任务。Q系列仍需单独准入。

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
2. 每批最多三个互不依赖的实现worker；协调器独占共享接线，live验收一次只跑一个。worker提前停下时核实退出与证据，批次遇阻后续验收暂停。
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

F017继续：plan-sync-job/validate-sync-job冻结注册定义和类型化参数，明确executed=false/dataVerified=false；show-sync-history只读有界分页读取真实账本状态。local-F017-plan-713d共14项通过，无失败/跳过，涵盖参数歧义、SQLite只读及分页。统一管理与退出码端到端验收待完成，仍16项verified。

F017继续：增加job/group版本化show与list别名，JSON输出独立解析验证；修复plan启动时提前创建调度账本，改为按需加载。真实Spring启动确认计划不创建ledger/WAL。local-F017-startup-bf13共13项通过，无失败/跳过。进程退出码及组合计划待完成，仍16项verified。

F017继续：main结束关闭资源，明确0成功/1运行故障/2参数错误/3未验证完成的退出码。真实Java子进程已验证plan=0、非法日期=2且不建账本；修复Windows内联JSON传参问题，增加有界parameters-file入口。local-F017-process-file-e84c定向测试通过；完整进程输出及F017最终验收待完成，仍16项verified。

F017继续：真实子进程完整验证退出码0/1/2/3，stdout结果JSON与stderr日志分离；阻塞调度返回3且不派发。调度配置命令也返回executed=false的JSON。组合快照可显式清除继承窗口。local-F017-full-a817完整回归BUILD SUCCESSFUL；F017最终验收登记待逐条复核，累计仍16项verified。证据见artifacts/java-migration/F017/process-output-review.md。

F017已验收：CLI逐项契约、四类进程退出码、JSON输出、计划不写、只读历史和取消/恢复路径通过。完整189项169通过20条件跳过0失败，最终控制补验通过。实际QDB证据按功能契约复用前置runner，未伪计新增写入。累计17项verified，下一项D001 exchange_calendar，第二批7/10；人工复核pending_review。

D001已开始：已查Python trade_calendar_sync真实调用链并读取QuestDB现有4列schema和2026年9月SSE日历30行。关注按交易所增量、修订重叠及失败不能当empty；尚未新sync/写入。证据见artifacts/java-migration/D001/physical-baseline.json与baseline-review.md，累计17项verified。

D001继续：完成业务LocalDate日历、完整exchange+calendar_date键、来源DTO和显式物理mapper；逻辑calendar_date/previous_trade_date兼容既有cal_date/pretrade_date。local-D001-mapping-e429三项通过，包括30行真实读取样本往返及非法日期/标记/非零点拒绝。未注册可执行owner，sync/读写/真实增量验收待完成，累计17项verified。

D001继续：实现按exchange/year有限分片及按各交易所已验证覆盖重读修订窗口；精确每日覆盖检查拒绝缺日/重复/越界/空响应冒充完成。来源适配器接公共HTTP链路，保存响应指纹，未引入offset。local-D001-source-compile-d922映射/分片6项通过；真实来源、实际writer及checkpoint owner验收待完成，累计17项verified。

D001继续：真实trade_cal按SSE/SZSE各4日返回8行，严格覆盖/日期映射通过，未写库。有效限流10/min且强制不高于Python20/min；更严格配置保留。修正测试对休市天数的周末假设，实际来源含3个休市日。源失败/取消/缺日/重复/越界/错误类型回归完成。证据见artifacts/java-migration/D001/source-live-review.md；实际writer和增量checkpoint待完成，仍17项verified。

D001继续：ExchangeCalendarWritePort与公共schema/WAL检查接入既有VerifiedBatchExecutor。真实SSE/SZSE 8行写入隔离YEAR/WAL表并重跑，两次全值回读通过，最终8行0重复；原始来源与SQL四字段独立核对一致。local-D001-write-live-853d通过，隔离表成功后清理。持久化增量checkpoint、owner/组合/管理准入及任务验收待完成，仍17项verified。

D001继续：日历单数据adapter接入既有runner，VERIFIED根任务重建按交易所连续覆盖，缺口/partial/其他目标不推进。local-D001-runner-live-f16a真实首次4行→重开账本+实际回读→重叠增量8行通过，两交易所checkpoint由09-26推进09-28；源与SQL四字段独立一致，隔离表清理。生产owner/组合/CLI及恢复准入待完成，仍17项verified。

D001继续：生产owner自动回读checkpoint候选并绑定物理目标，CLI计划/运行/恢复已接入；真实owner首次4行、重开增量8行通过。组合入口支持日历子任务及父取消，local-D001-group-live-e805真实8行写入回读通过，恢复完成子任务重新核验后复用，最终仍8行。组合读写与完整容错验收待完成，累计仍17项verified，人工复核pending_review。证据见artifacts/java-migration/D001/owner-group-review.md。

D001已验收：日期语义/键/分区、限流有界增量、typed读写及组合、管理与容错通过。真实owner4→8行，按完整键四字段独立比对一致；相同范围重跑和完成子任务恢复通过。完整回归{'tests': 214, 'failures': 0, 'errors': 0, 'skipped': 28, 'passed': 186}。累计18项verified，第二批8/10，下一项D002 stock_detail_info；人工复核pending_review。

D002已开始：实际读取18列schema、5908行/5908代码及3行完整样本。该表无主时间、NONE分区、非WAL、无dedup，Python使用暂存表核验后替换；不可套用日历追加写。发现历史delist_date存在字符串None，update_time为本地采集时间且需追查Python无时区转换。尚未D002新sync或写入；证据见artifacts/java-migration/D002/baseline-review.md。

D002继续：18字段DTO/domain/显式mapper和未注册定义完成；update_time映射observed_at Instant，上市/退市日期映射LocalDate。查实Python旧时间按naive-UTC兼容，Java保留存量epoch；仅旧日期None显式转空。local-D002-mapping-839e三项通过，含3行实际基线往返。尚未新来源/写入/增量验收，仍18项verified；证据见artifacts/java-migration/D002/mapping-review.md。

D002继续：按ts_code有限增量合并完成，保留未请求记录、内容未变不发布、重复身份拒绝；旧Python时间与新Java时间不同源，不用观察时间大小误判业务修订，修正后合并3项复测通过。真实stock_basic对2个代码逐L/D/P执行6次请求，得到上市/退市各1行；限流50/min上限保留更严配置，来源故障/空响应/取消/身份漂移测试通过。尚未D002写库与非WAL发布验收，仍18项verified。证据见artifacts/java-migration/D002/source-merge-review.md。

D002继续：有界原始快照读取通过实际5908行，发现并兼容历史T600018.SH。非WAL暂存写入真实1→2行、全18字段回读一致，重复输入2行未变化不创建新暂存表，原目标保持不变；取消/无变化不连接数据库测试通过。发布切换、持久恢复、owner与组合准入仍待完成，累计18项verified。证据见artifacts/java-migration/D002/storage-staging-review.md。

D002继续：SQLite发布日志、整表锁校验、旧表备份→暂存改名→全值回读正常路径实际通过；注入旧表改名后异常，重开识别OLD_MOVED，要求写入者停止证明后恢复原表并精确回读，保留IN_DOUBT直至核验。进程崩溃/已发布回执丢失分支及owner/组合接入待完成，累计仍18项verified。证据见artifacts/java-migration/D002/publication-review.md。

D002继续：非终态发布恢复与已发布回执丢失补记完成。local-D002-interrupted-live-218c在真实隔离QDB注入跳过异常处理的Error，分别恢复OLD_RENAMED原表及核验NEW_RENAMED新表；无停止证明拒绝恢复，完整字段回读一致。未实际杀OS进程，范围已注明。owner/run/checkpoint与组合管理接入待完成，仍18项verified；证据见artifacts/java-migration/D002/interrupted-publication-review.md。

D002继续：正式owner已注册，整表锁覆盖来源→合并→发布→回读。首次真实owner暴露账本缺验证载荷，保留IN_DOUBT；修正后对原失败现场真实回读恢复VERIFIED，无新来源/数据写入。新隔离目标首次1行、重复不换表、增量第二代码后2行保留原记录，三个run/attempt均verified；注册/计划/锁回归通过。CLI/组合/发现模式完整验收待完成，累计仍18项verified。证据见artifacts/java-migration/D002/owner-review.md。

D002继续：空结果/来源失败/取消/锁冲突的真实QuestDB保留数据验证通过。修正目标标识只含表ID的跨服务器混淆风险，static-v2绑定实际PG端点/数据库/表物理身份；恢复同时比对发布后的物理表身份。local-D002-target-binding-f831三项通过，local-D002-target-owner-b169真实来源首次/重复/增量及旧回执拒绝通过。完成表已更新当前实际进展，仍18项verified，D002未最终验收。证据见artifacts/java-migration/D002/target-identity-review.md。

D002继续：整表发布slice已接入run/attempt/slice三级账本，实际组合写入和失败边界通过。注入发布后账本确认失败时三级保留IN_DOUBT；恢复暴露Instant反序列化缺失，已修复并对原失败现场执行零新取数/零QDB写入的真实回读恢复，三级VERIFIED且锁为0。全套回归252项（207通过/45条件跳过/0失败）在时间读取修复前通过，随后修复专项与原现场恢复通过。完成回执生成前的中断恢复和最终证据收敛仍需核验，累计18项verified。证据见artifacts/java-migration/D002/slice-ledger-recovery-review.md。
D002已验收：5910条真实来源与隔离目标完整回读一致（新增2、修订16、未变5892）；补齐三级账本、缺完成回执、未完成改名和残缺暂存的显式停止写入者恢复。local-D002-final-regression-021a全套256项：209通过、47条件跳过、0失败。结果及完成表保持人工pending_review；累计19项verified，下一项D003，再完成1项达到第二批10项提交点。

D003继续：直接本地执行。真实目录文件2343行合并原2274行，新增529、修订1814、保留缺席460，隔离发布2803行全字段回读一致；重跑不换表。正式index仍只读。已接入三级任务账本、文件SHA256冻结、CLI和sync组合；组合恢复复用原子任务前实际回读。发布后账本失败保留IN_DOUBT，确认写入者停止后零新来源/零行插入恢复VERIFIED。空源、文件漂移、父取消、锁冲突均验证原目标未变，写入前失败安全重跑通过。组合写入及暂存早期中断恢复仍待完成，D003保持running，累计19项verified，尚未达到第二批提交点。证据：artifacts/java-migration/D003/owner-management-review.md。

D003已验收：真实文件2343行→隔离合并2803行，全字段及独立来源比对一致；job/CLI/读写组合、无变化/空源、取消/锁、阶段恢复通过。最终回归278项：216通过/62条件跳过/0失败。累计20项verified，第二批达到10项提交点；人工pending_review，下一项D004。既有F011–D002部分已由工作区其他本地提交保存，保留其历史，本批仅提交尚未提交的D003收敛变更，不push。

第二批本地收敛提交：2593014，无push。D004已开始：读取ths_index实际2517行、7列和物理键，发现有效字母后缀代码；2517行mapper往返与非法值两项测试通过。官方接口不声明分页且不建议循环，先实现单显式范围有界来源请求。累计仍20项verified，D004未完成。

D004继续：2517行typed分页和独立7列SQL比对通过；来源边界、限流及内容合并测试通过。真实保留来源在隔离库暂存1→2517行，新增2516，11批写入回读一致，Python六来源列独立核对0差异（含12条count为空）。正式表未变；发布阶段恢复、owner与组合管理仍待验收。累计20项verified。

D004继续：修正完整目录观察的缺席代码保留语义与取消误记FAILED。正式owner真实首次2517及重跑通过；受控缺席一条后实际2517行7字段不变；空源/来源失败/取消/锁冲突实际隔离库保留数据与三级账本通过。当前全套299项：225通过/74条件跳过/0失败；正式owner恢复及组合仍待验收，累计20项verified。详见artifacts/java-migration/D004/incremental-owner-boundary-review.md。

D004继续：已接入sync组合，真实首次同步及组合恢复通过，恢复未重新取数。发布后缺回执、发布前完整stage复用/残缺stage重建、无变化中断均实际回读恢复通过，三级账本VERIFIED且锁释放。typed组合写2条实际已有数据及恢复通过。CLI计划/参数专项通过；prepared写入失败恢复与最终回归待收敛，累计仍20项verified。证据见artifacts/java-migration/D004/recovery-composition-review.md。

D004已验收：实际2517条首次owner及重跑、1→2517增量、完整7字段回读和独立来源核对、sync/typed写组合及恢复、CLI管理、空源/取消/锁/发布前后中断与prepared回执漂移拒绝通过。最新全套306项：226通过/80条件跳过/0失败；1016实际增量保留专项通过。累计21项verified，第三批1/10；人工pending_review，下一项D005 index_member。

D005已开始：实际只读5902条/131行业/14列，YEAR/WAL无dedup，全部L2与is_new=Y。查明Python未传is_new，不能将完整历史注释当证据；out_date有旧None字符串，weight/con_code为空。冻结逐L2显式Y/N取数与日期/观察时间分离方向，尚未新增来源或写入，累计21项verified。

D005继续：单L2=801011.SI显式Y/N真实请求分别4/3条，通过共享Java限流HTTP路径，无QDB写入。DTO/domain/mapper完成，键加入纳入日期，退出日可修订；旧None仅存量兼容，来源非法日期拒绝。local-D005-mapping-1019三项通过（7条源与100条存量）；分类来源、读写owner/组合及恢复待完成，累计仍21项verified。

D005继续：仅READ的数据定义/typed repository/read group接入。local-D005-read-1021实际5902条24页、14字段独立SQL全部一致，5902个旧None日期按声明转null。发现并显式兼容历史T00018.SH；未改码或丢行。现有Python部分调用方未筛is_new，历史N仅可隔离验收。writer/owner及恢复待完成，累计21项verified。

D005继续：成员期间增量merge完成，退出日期修订不换键、重新纳入分期间、缺席不删除、来源不提供的weight/con_code保留。local-D005-merge-1022五项通过；1023保存的真实Y4/N3与5902行回读合并新增3历史期间，结果5905（未落库），重跑7条未变。来源adapter/写入owner与恢复待完成，累计21项verified。

D005继续：单L2的IndexMembershipSource支持显式CURRENT/HISTORICAL/BOTH，逐Y/N一页有界请求，分响应与完整slice保存SHA回执；第二请求失败不生成完整回执。1024四项边界通过；1025真实adapter当前4/历史3条通过，无QDB写入。分类发现/任务owner/隔离写入及恢复仍待，累计21项verified。

D005继续：分类adapter固定SW2021/L2有界观察并保存SHA，选择1..32个明确行业scope，is_pub=0不丢弃。1026两项边界及实际分类134→林业ⅡY/N共7条链路通过。正式job分类SHA绑定、逐行业checkpoint及隔离写入/恢复待完成，累计21项verified。

D005继续：物理快照5902条原始值精确读取通过；有界stage写入24批5905条，新增3历史期间、未变4，完整14列实际回读相等。独立Python核对源9字段0差异及5902原始旧行全部保留。重跑不写，正式表未变。stage尚未发布，owner/checkpoint/组合及恢复待完成，累计21项verified。

D005继续：local-D005-publication-1033实际隔离4→7行发布通过，正常及PREPARED/OLD_MOVED/PUBLISHED三阶段中断恢复全14列一致，原4行及备份保留；重复恢复一致、发布前取消不改表。1030/1031测试准备中名称复用后的WAL未就绪现场保留，1033使用已验证seed表规避该准备问题，未宣称底层根因修复。正式owner/checkpoint/CLI/组合待完成，累计21项verified。见artifacts/java-migration/D005/publication-recovery-review.md。

D005继续：分类SHA重新加载与范围冻结4项边界通过；单行业执行器接入三级账本/整表锁/回读后checkpoint。local-D005-slice-owner-1037实际首次7条、重跑7条0写入、另一行业真实空源0条且原7条保留通过，独立源字段0差异。1036因来源opt-in跳过未计验收。多行业串行协调、owner恢复、CLI及读写组合仍待完成；累计21项verified。见artifacts/java-migration/D005/slice-owner-review.md。

D005继续：冻结Y/N原始凭据重建与owner恢复接入。1038发布后未确认恢复实际7条、0新请求/0WAL事务、三级VERIFIED；1041完整stage复用及CREATE后中断重新建stage两项实际通过。来源漂移/未停写证明拒绝。1040 TRUNCATE模拟未完成stage遇WAL未就绪现场保留、不计通过；1041改为实际CREATE后故障注入。无写入恢复、多行业/CLI/组合待完成，累计21项verified。见artifacts/java-migration/D005/owner-recovery-review.md。

D005继续：无变化/真实空来源中断恢复及写入前失败安全重试接入。local-D005-no-write-1043实际三种场景通过，已有7条全14列/身份/WAL事务不变；恢复0新来源请求，重试同范围真实7条未变，换范围和已完成run重试拒绝。三级账本/锁核验通过。多行业串行与复用、CLI/组合及最终回归待完成，累计21项verified。见artifacts/java-migration/D005/no-write-recovery-review.md。

D005继续：多行业批次/父slot及逐行业复用实现。修复IntNode/LongNode表ID快照比较误报，1046原失败现场只读通过；1047实际801011(Y/N)后801217(Y/N)按序4请求、目标7条，整批恢复复用2子run且0新请求、快照不变。批次失败/取消/中断及多个非空行业连续发布待验，CLI/跨数据组合仍待完成；累计21项verified。见artifacts/java-migration/D005/batch-review.md。

D005继续：1049正常/第二行业失败/首行业后取消3场景通过；失败取消均停止后续，恢复仅继续未验行业。1051协调任务与恢复过程再次中断专项通过，旧批次PARTIAL、新批次VERIFIED，已验首行业不重新取数，实际7条全值不变。多个非空行业、更早批次中断、CLI/组合待完成；累计21项verified。见artifacts/java-migration/D005/batch-boundary-review.md。

D005继续：1052两个非空行业4+2连续发布/整表链及外部漂移拒绝通过；1054经注册CLI实际取数写入6条、整批复用及任务查询通过，离线计划无DB/无账本。1053计划误访问尚不存在隔离目标已通过分离离线入口修正并重验。跨数据sync/typed-write组合、更早批次中断及最终回归待完成；累计21项verified。见artifacts/java-migration/D005/cli-chain-review.md。

D005继续：1055本地直接执行组合入口实际通过（1项、0跳过）；两个行业2次真实请求、隔离QuestDB全14字段6条，组合恢复复用原批次且0新请求，完整快照一致、正式表不变。跨不同数据集组合、typed-write组合、更早批次中断及最终回归待完成；累计仍21项verified。见artifacts/java-migration/D005/group-review.md。

D005继续：1056跨数据sync组合实际2517条同花顺指数→6条成员按账本顺序完成，3次请求，恢复两个子任务均复用/0新请求。1057写入策略漏注册及1058强类型快照误比已修复，1059单成员typed-write真实2条回读/恢复不重复写入通过；多数据集typed-write、早期中断及最终回归仍待。累计21项verified，见artifacts/java-migration/D005/group-review.md。

D005继续：1060两个WAL数据typed-write组合实际2+2条完整回读/恢复均复用通过；1061第二数据锁冲突→PARTIAL→释放本次测试锁恢复，仅重试第二数据，首数据原run及完整快照不变，再恢复均复用。早期中断、未创建子任务恢复边界及最终回归仍待；累计21项verified。证据见artifacts/java-migration/D005/group-review.md。

D005继续：1062写入组合第一项验收后取消、第二项childRunId=null的实际恢复通过；改为从原组合冻结记录读取目标身份，首项不重写，第二项新建写入2条，再恢复全部复用。sync同类边界、批次早期退出及最终回归待完成；累计21项verified。证据见artifacts/java-migration/D005/group-review.md。

D005已验收：单行业真实来源 Y4/N3 首次隔离写入7条、同范围真实重跑0写入，空行业0行；14字段QuestDB回读与独立9来源字段核对均无差异。多行业及跨数据sync组合、typed-write双WAL数据组合、部分失败及早期中断恢复通过。准备写入暂存后与已发布后中断恢复各1项通过；批次计划后及slot后首子任务前恢复各1项通过。`local-D005-full-live-build` 48项中46通过、2只读条件跳过、0失败；`local-D005-final-read-build` 补跑2项通过；项目常规回归351项中244通过、107条件跳过、0失败。正式index_member保持5902行、ID1760、WAL writer/sequencer=2且无积压；结果见results/D005.json与artifacts/java-migration/D005/final-review.md。累计22项verified，第三批2/10；人工pending_review。下一项D006 ths_member。

D006已验收：同花顺 `ths_member` 以显式单板块切片请求，真实来源 `885800.TI` 返回506键、`700001.TI` 大板块预检5565行；隔离表首次替换506行并完整复制另5565行，8字段回读与流式原值指纹一致，重跑506行无再次发布。正常/旧表改名后/暂存后与账本失败后恢复、prepared写入组合、sync组合及来源触顶/重复键/取消边界通过。项目常规回归371项中249通过、122条件跳过、0失败；最终正式表只读扫描416612行、ID1779、WAL writerTxn=3，未作正式表发布。结果见results/D006.json与artifacts/java-migration/D006/final-review.md。累计23项verified，第三批3/10；人工pending_review。下一批实现从D007–D009开始。

## 用户授权的并行批次（2026-09-29）

- 批次1：D007 `daily`、D008 `daily_basic`、D009 `stk_factor` 并行实现；共享注册、CLI和写组路由由协调器集成，来源预算和QuestDB验收保持串行。
- 完成：三个owner/DTO/domain/mapper/read/write/source/CLI入口已接线；D008后续增量CLI允许由checkpoint推导30日修订重叠起点。完整 `gradlew test`：409项、0失败、123项条件跳过（286通过）；审计修正后于2026-09-30复跑通过；CLI专项与live验收harness编译通过。
- live预检：2026-09-29T15:46Z，连接池查询 `tables()` 时QuestDB返回invalid username/password。证据：`artifacts/java-migration/market-live-27353976694746f6a25c9633222814fc/batch-live-acceptance-failure.json`。发生在DDL和Tushare请求之前：0来源请求、0隔离表创建、正式表无修改。
- 恢复后再次预检：2026-09-29T16:06Z，同一只读连接仍返回invalid username/password；本地未设置 `APP_QUESTDB_PASSWORD`，`application.yml` 当前密码项仍为占位值。第二次仍为0来源请求、0隔离表创建、正式表无修改；证据：`artifacts/java-migration/market-live-e8970dc5629d4229b5b302bef716d4b1/batch-live-acceptance-failure.json`。
- 2026-09-30审计复跑：先修正StockFactorSyncAdapter中的pattern-variable编译错误，再运行 `gradlew test`，409项、0失败、123项条件跳过（286通过）。最新测试报告覆盖D007–D009审计修正和新增回归测试；`git diff --check`及三份结果JSON解析均通过。
- 当前下一步：修复本地QuestDB凭证后，从D007隔离验收重新启动；D007通过后依次验收D008和D009。未完成前这三项继续计入178项未完成主线。

## 用户最新指定串行范围（2026-09-30）

用户将当前执行范围切换为 `10-l2` → `11-derived` → `12-factor` → `13-strategy` → `14-runtime`，从D085开始逐项串行。本节按该最新指令更新，前面D007–D009批次记录保留为历史状态，不作为当前调度顺序。

- D085 `l2_dataset_manifest`：verified。
- D086 `l2_daily_features`：verified；独立证据见 `results/D086.json`。
- D087 `l2_intraday_bar_features`：verified；正式表只读，隔离样本1014行、60列逐字段回读匹配，幂等回灌/增量/续跑及写组入口通过；人工复核 `pending_review`。证据见 `results/D087.json`。
- D087验收期间三张失败尝试隔离表和一张最终验收表均保留，未修改正式表。详见D087 target census。
- D087协调器验收门槛已通过；用户总体迁移目标仍在执行，D088已按顺序启动。D087人工复核仍为 `pending_review`。
- D087 Java与测试源码在JDK24下成功编译（临时排除工作区已有未跟踪的不完整D019源文件；`build.gradle`已还原）；定向映射与live测试5项通过。默认全工作区编译仍受上述未跟踪源文件阻塞，详见D087结果文件。
- D088 `l2_event_response_features`：verified；隔离表354行、73列全字段回读一致（25,842值），backfill/幂等重跑/incremental/resume/write-group通过；正式表只读，前后均114,123,728行。运行与核验记录见 `results/D088.json` 和 `artifacts/java-migration/D088/commands/target-census-20260930.json`。
- D088语义核验记录了六类事件阈值；`future_mid_return_*` 按Python实际公式是未来VWAP/事件分钟mid−1，未来收益为前视结果。无Parquet part按错误处理，不冒充空成功。限流不适用于本地文件源；单次尝试、有限行/文件/页/字节/时间预算、取消和断点/重放已验收。
- D088定向映射与live验收各1项通过。默认全工作区编译仍受无关且不完整的D019/D020源影响；D088 focused compile和验收测试通过，详情见结果文件。Orca主协调器核验当前QuestDB 354行隔离目标、25,842个字段比较、正式表114,123,728行及验收报告后接受D088门槛；记录见 `artifacts/java-migration/D088/coordinator-review-20260930.json`。人工 `pending_review` 保留；D089现按目录顺序启动。
- D089 `l2_t0_training_labels`：verified。D085回执认证来源在2026-09-21..24为000001.SZ返回1,014行/8页；隔离表62列逐完整键回读1,014/1,014，全值62,868/62,868一致；正式表前后181,049,686行。BACKFILL/幂等重跑/INCREMENTAL/RESUME均VERIFIED，resume复用1,014行；取消与旧source fingerprint按预期拒绝。结果见 `results/D089.json` 和 `artifacts/java-migration/D089/commands/target-census-20260930.json`。
- D089 outcome有前视语义且无maturity列：30m窗口有294行双侧alpha缺失但二元label为0，消费者须按非空coverage解释，人工复核仍pending_review。D089定向映射3项、真实live acceptance 1项通过。默认全工作区Gradle编译仍被既有不完整Moneyflow/D024/D026源阻断；D089依赖切片使用`javac --release 24`编译通过。写入组合分支已注册和静态复核，组合端到端未运行，结果内如实记录。
- Orca主协调器复核D089来源、目标回读、formal row count及运行证据后接受D089串行门槛；记录见 `artifacts/java-migration/D089/coordinator-review-20260930.json`。下一项按指定目录顺序为D090 `backtest_daily`（`11-derived`）；D089人工复核保持pending_review。
- D090 `backtest_daily`：按实际Python registry/readthrough/cache调用链确定为 `retained_compatibility`。保留旧正式表只读兼容定义；不新建Java writer、sync job或compute owner。Java bounded typed read与ReadGroup接入；本机QuestDB 2026-09-17有界读取200行，与`v_backtest_daily`按完整键比较13列、2,600个字段值全部匹配；正式表行数10,355,884及table_txn=17前后不变。映射测试4项和本机只读验收1项通过，focused compile通过。Python owner当前用source_version读取`backtest_daily_cache`，cache miss由`v_backtest_daily`填充；旧CLI materializer仍可手动运行但未调用。证据见`results/D090.json`与`artifacts/java-migration/D090/`。primary-coordinator gate接受串行推进；用户人工复核保持`pending_review`；下一项D091 `backtest_daily_cache_coverage`（`11-derived`）。

- 2026-09-30已合并远端 `origin/main` 最新代码，合并提交 `8448730`：包含Java包名改为 `com.zoutrankil.data`、批处理模块和Web API；本地D085–D091任务状态及证据保留。JDK 24下全工作区 `compileJava`、`compileTestJava` 通过，依赖锁文件已同步更新。
- D091 `backtest_daily_cache_coverage`：裁决为 `retained_compatibility`，仅Java typed READ和ReadGroup，不创建竞争Python publisher的writer/job。本机QuestDB最新已存回执日期2026-09-21；回执5,565行对应cache切片实读5,565行、证券键唯一，Python原版内容摘要复算一致，Java映射3项和只读实库测试1项通过。coverage和cache表行数/table_txn读前后不变。当前来源指纹与该已存版本不同，Python当前版本读取会cache miss，不能把历史回执的完整性当作当前缓存热度；未触发写入式重建。证据见`results/D091.json`及`artifacts/java-migration/D091/`。协调器验收门槛接受串行推进，人工复核`pending_review`；下一项D092 `backtest_daily_cache`（`11-derived`）。
- D092 `backtest_daily_cache`：按Python readthrough owner裁决为 `retained_compatibility`，仅Java typed READ和ReadGroup。实表MONTH/WAL/DEDUP及14列、完整版本化UPSERT KEY现场确认。2026-09-21已存版本完整5,565行经Java typed repository读取，与D091已由Python原版摘要核验的切片逐字段比对77,910值全部匹配；ReadGroup一致。Java映射3项、本机只读验收1项通过；cache和coverage表行数/table_txn均未变化。当前来源指纹与已存版本不同，未触发可能写入的重建。证据见`results/D092.json`及`artifacts/java-migration/D092/`。协调器验收门槛接受串行推进，人工复核`pending_review`；下一项D093 `v_backtest_daily`（`11-derived`）。
- D093 `v_backtest_daily`：普通VIEW裁决为 `retained_compatibility`，Python `VIEW_SELECT` 保持权威DDL owner，Java提供13字段typed READ和ReadGroup。QuestDB `views()` 状态valid、SQL哈希与Python定义一致；上游 `stk_factor`、`stk_limit`、`stk_st_daily` 唯一键满足Python安装守卫。2026-09-17 Python HTTP及Java JDBC实读各200个完整键，13列共2,600值全部匹配，ReadGroup一致。视图无物理WAL/分区/DEDUP，来源表行数/table_txn读前后不变，未执行DDL或写入。映射3项、实库1项通过。证据见`results/D093.json`及`artifacts/java-migration/D093/`。协调器验收门槛接受串行推进，人工复核`pending_review`；下一项D094 `market_barometer_cache_coverage`（`11-derived`）。
