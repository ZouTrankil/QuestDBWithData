# 逐任务与逐表完成登记

范围切换前的历史计划曾覆盖 F001–F017、D001–D084；此前各项状态继续保留在本全局登记表中，当前执行范围以用户最新指令为准。

当前执行范围：用户指定 D085–D184（10-l2、11-derived、12-factor、13-strategy、14-runtime，共100项）由本会话按目录顺序串行完成，从 D085 开始。此顺序覆盖任务卡中的 D084 跨范围前置；D007–D084 状态保持不变。下表继续保留全局状态，任务只有按证据验收后才更新为 verified。

当前执行范围 D085–D184 按指定目录顺序逐项串行；每项验收后更新本表、任务卡和对应results文件。D007–D084保持已有状态，人工复核由用户完成。

状态：planned / running / implemented_not_verified / verified / blocked / conditional；正常无新增运行记verified_empty在结果明细中，首次0行不自动通过数据任务。

| ID | 单个数据/功能 | 状态 | 目标库/表 | sync模式/窗口 | 源有效行/写入提交行 | QuestDB回读匹配/不匹配 | checkpoint前→后 | 容错证据 | 完成时间/runId | 证据 | 人工比对 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| F001 | 迁移范围与生成模型基线 | verified | 配置QuestDB/184对象 | N/A只读 | N/A | 184对象已读，182非空；结构差异0 | N/A | 首次超时/逐对象记录/全量重跑成功 | local-F001-20260929 | [核对报告](../../artifacts/java-migration/F001/review.md) | pending_review |
| F002 | 统一日期与时刻转换 | verified | daily/etf_daily/exchange_calendar | N/A转换 | N/A | 9/0，日期往返一致 | N/A | 非法日期/时区歧义/精度丢失拒绝 | local-F002-20260929 | [验收报告](../../artifacts/java-migration/F002/review.md) | pending_review |
| F003 | DatasetDefinition注册与校验 | verified | daily只读投影/stock_basic定义 | N/A契约 | N/A | 3/0，字段与样本键验证 | N/A | 重复ID/键缺失/依赖环/View直写拒绝 | local-F003-20260929 | [验收报告](../../artifacts/java-migration/F003/review.md) | pending_review |
| F004 | 共享HTTP客户端与连接复用 | verified | N/A HTTP功能 | 单股诊断2次 | 2/N/A | N/A无数据库写入 | N/A | 缺字段/坏行/HTTP429/业务错误/取消/超时 | local-F004-20260929 | [验收报告](../../artifacts/java-migration/F004/review.md) | pending_review |
| F005 | 共享限流与重试预算 | verified | N/A来源预算功能 | 单股连续2次，自动节流 | 2/N/A | N/A无数据库写入 | N/A | 并发预算/逐次重试/429/取消/进程锁/重启冷却 | local-F005-20260929 | [验收报告](../../artifacts/java-migration/F005/review.md) | pending_review |
| F006 | 日期分片与分页执行器 | verified | N/A来源取数功能 | 两个代码片，逐页消费 | 2/N/A | N/A无数据库写入 | N/A | 截断/重复页/游标停滞/失败/取消/最小窗口 | local-F006-20260929 | [验收报告](../../artifacts/java-migration/F006/review.md) | pending_review |
| F007 | 单数据有界读取接口 | verified | QuestDB daily/etf_daily只读 | 日期范围+代码/键游标 | N/A只读 | 14/0，独立SQL比对 | N/A | 类型/范围/游标绑定/重复键/精度/schema | local-F007-20260929 | [验收报告](../../artifacts/java-migration/F007/review.md) | pending_review |
| F008 | 单数据分批写入与完成验证 | verified | 真实stock_basic 2行 | 完整键/7字段 | 初写2+重跑2 | 匹配2/重复0 | N/A：F010/F012 | 65通过/9跳过/0失败；真实隔离表清理 | local-F008-20260929 | [验收记录](results/F008.json) | pending_review |
| F009 | SyncJobDefinition与版本管理 | verified | N/A定义功能 | 版本/模式/参数/依赖 | N/A | N/A | N/A | 8项通过及实际CLI启动 | local-F009-20260929 | [验收记录](results/F009.json) | pending_review |
| F010 | 同步运行账本与状态查询 | verified | 真实stock_basic 1行 | run/attempt/slice及完整快照 | 提交1 | 匹配1 | N/A：F012 | SQL事务/恢复/状态CLI通过 | local-F010-20260929 | [验收记录](results/F010.json) | pending_review |
| F011 | 单任务执行与互斥 | verified | 真实2代码/2行 | v2手动快照/冻结日期 | 提交2 | 全字段匹配2 | N/A：F012 | 互斥/超时未知/取消通过 | local-F011-20260929 | [验收记录](results/F011.json) | pending_review |
| F012 | 断点恢复与任务取消 | verified | 真实2代码/2行，第二页注入失败后恢复 | v2快照/源指纹/目标身份 | 首片复用1，补写1 | 7字段匹配2，WAL settled | checkpoint：已验证片1→2 | 113项104通过9跳过0失败；未知写入保锁 | run-6ab8a992772c438ea661dc25bb1b72c7 | [验收记录](results/F012.json) | pending_review |
| F013 | 批量同步组合定义与串行运行 | verified | group v1 / child v2 | 有序组合、冻结参数、串行失败停止 | 2条业务源行 | 2条写入并回读 | 已完成项重新复核后复用 | 正常恢复/真实漂移拒绝 | 7字段2行一致 | [验收记录](results/F013.json) | pending_review |
| F014 | 批量读取组合 | verified | ReadGroupRequest v1 | 独立filter/projection/pageSize/cursor | N/A只读功能 | QuestDB 8行 | 独立游标含JSON续页 | 核心/错误/取消/入口通过 | 两表8行全值一致 | [验收记录](results/F014.json) | pending_review |
| F015 | 批量写入组合 | verified | WriteGroupRequest/Plan v1 | 批次/目标/完整定义/行指纹 | Tushare 2行 | 2表2行完整回读 | 原片重核验后复用 | 故障恢复/重复提交/未知写入保护 | 7字段一致，send各1次 | [验收记录](results/F015.json) | pending_review |
| F016 | 同步计划管理 | verified | java_f016_21766a9c38434835abd627e069706fcd（隔离，已清理） | SNAPSHOT/1显式code | 1/1 | 1/0（7字段） | 无→VERIFIED槽 | DST/停用/misfire/并发/未知提交/重开 | 2026-09-29T04:33:54.263639+00:00 / schedule-run-21766a9c38434835abd627e069706fcd | [结果](results/F016.json) | pending_review |
| F017 | 同步任务管理CLI | verified | SQLite控制账本；复用F013/F015/F016真实QDB凭据 | 无新增sync；按冻结job路由 | 0/0（本项控制层） | 复用前置真实回读，非本项新写 | N/A；取消请求持久化 | 子进程0/1/2/3、JSON、只读分页、恢复路由 | 2026-09-29T04:57:13.218973+00:00 | [结果](results/F017.json) | pending_review |
| D001 | exchange_calendar | verified | 隔离 java_d001_owner_f9a4ed4db14a447e9e1e0ce2a0877b92 | INCREMENTAL 09-25..09-28 SSE/SZSE | 8/8（第二次owner运行） | 8/0；0重复 | 各所09-26→09-28 | 限流、完整覆盖、取消、IN_DOUBT、恢复回读 | 2026-09-29T05:55:45.724632+00:00 / calendar-93bc01ea-ce1e-46b3-b881-5f4060e73c06 | [结果](results/D001.json) | pending_review |
| D002 | stock_detail_info | verified | 隔离 java_d002_full_14df7eb616ee475fbcdf979193fd5e9f（已清理）；生产5908行只读 | INCREMENTAL，L/D/P × SSE/SZSE/BSE九片，09-29观察日 | 来源5910；整表暂存5910；新增2、修订16、未变5892 | 5910键/17来源字段0差异；18物理列全表回读一致 | 已验证克隆指纹9f6506a4…→3f78b957…；不用update_time水位 | 截断/失败/空/取消/锁冲突/安全重放/发布恢复及CLI、读写组合通过 | 2026-09-29T07:46:20Z / stock-detail-b778065e-10af-4558-9901-bd2de9e3cca4 | [结果](results/D002.json)、[最终核对](../../artifacts/java-migration/D002/final-review.md) | pending_review |
| D003 | index | verified | 隔离MONTH/WAL；正式index只读 | 文件内容增量；观察时间非行情日 | 源2343；新增529/修订1814/保留460；暂存2803 | 18列全值一致；独立17来源列0差异 | 已验证内容指纹与物理身份；重跑不换表 | 空源/漂移/取消/锁/三级账本与各发布阶段恢复通过 | index-catalog-f0532119-bf33-415c-996c-7665c9ba2cec | [结果](results/D003.json)、[验收](../../artifacts/java-migration/D003/final-review.md) | pending_review |
| D004 | ths_index | verified | 正式2517行只读；隔离MONTH/WAL/dedup | 单次有界目录观察；内容增量保留缺席代码 | 真实首次2517；暂存1→2517新增2516 | 全7字段一致；独立6来源列0差异 | owner/组合重跑及恢复通过；typed写2行一致 | 空源/失败/取消/锁、早期stage及发布后恢复、prepared漂移拒绝通过 | local-D004-final-regression-1015：226通过/80条件跳过/0失败；1016实际增量专项通过 | [最终核对](../../artifacts/java-migration/D004/final-review.md) | pending_review |
| D005 | index_member | verified | 正式5902行只读、ID1760；隔离YEAR/WAL无dedup | 分类134行SHA冻结→逐L2显式Y/N；期间键增量 | 单行业真实7行写7、重跑7行写0、空行业0；stage5905；跨数据sync2517→6；prepared写2行 | 全14列回读；独立9来源列缺键0/差异0；正式表不变 | 单行业/批次/组合复用、早期协调中断及准备写入暂存/已发布恢复通过 | 46项D005真实来源与隔离写入通过、2项有界读取补跑通过；项目351项244通过/107条件跳过/0失败 | [结果](results/D005.json)、[最终核对](../../artifacts/java-migration/D005/final-review.md)、[准备写入](../../artifacts/java-migration/D005/prepared-owner-review.md)、[早期恢复](../../artifacts/java-migration/D005/batch-early-recovery-review.md) | pending_review |
| D006 | ths_member | verified | 正式416612行只读、ID1779/WAL txn3；隔离MONTH/WAL/dedup | 同花顺按板块单次有界请求；内容增量，观察时间不是来源水位 | 真实885800.TI首次506行替换、另5565行原值复制；重跑506行0写入 | 8字段506键全值回读0差异；另5565行流式指纹一致 | 目标完整指纹1fa6087e…→4a7d092c…；重跑不换表 | 触顶/重复键/越界/取消拒绝；发布后、改名前与暂存后恢复；prepared及sync组合复用 | 项目371项249通过/122条件跳过/0失败；正式表最终扫描通过 | [结果](results/D006.json)、[最终核对](../../artifacts/java-migration/D006/final-review.md) | pending_review |
| D007 | daily | verified | 隔离 java_d007_daily_c494e9686f614e6ebe0a13323d3dc48f | 首次09-28；增量至09-29 | 首次5557；增量11116 | 11116/0；0重复 | 09-28→09-29 | 取源前/页后取消、5557行恢复0发送、丢ACK回读 | 2026-09-29T17:36:52.208478800Z | [结果](results/D007.json)、[验收](../../artifacts/java-migration/D007/final-review.md) | pending_review |
| D008 | daily_basic | verified | 隔离 java_d008_basic_9b4cbc43e2224ae28b1d522fec0f38b3 | 首次09-28；增量至09-29 | 首次5557；增量116572 | 116572/0；0重复 | 09-28→09-29 | 取源前/页后取消、5557行恢复0发送、丢ACK回读 | 2026-09-29T17:37:36.648185100Z | [结果](results/D008.json)、[验收](../../artifacts/java-migration/D008/final-review.md) | pending_review |
| D009 | stk_factor | verified | 隔离 java_d009_factor_bcf7b1ef91bc42928a647214f19a01d8 | 首次09-28；增量至09-29 | 首次5557；增量11114 | 11114/0；0重复 | 09-28→09-29 | 取消、5557行恢复0发送、丢ACK回读 | 2026-09-29T18:06:48.383543100Z | [结果](results/D009.json)、[验收](../../artifacts/java-migration/D009/final-review.md) | pending_review |
| D010 | stk_limit | verified | 隔离 java_d010_stk_limit_6f4fe7d2d30e465e903ad6d510c2df7c | 首次09-28；增量至09-29 | 首次5648；增量11298 | 11298/0；0重复 | 09-28→09-29 | 取消、5648行恢复0发送、丢ACK回读 | 2026-09-29T18:11:24.892294600Z | [结果](results/D010.json)、[验收](../../artifacts/java-migration/D010/final-review.md) | pending_review |
| D011 | stk_suspend | verified | 隔离 java_d011_stk_suspend_00f1a5c441d14abe877cd13a99139970 | 09-28至09-29 | 首次12；增量25 | 25/0；0重复 | 09-28→09-29 | 取消、恢复12行、撤回、空/非空发布恢复 | 2026-09-29T19:50:54.819680Z | [结果](results/D011.json)、[验收](../../artifacts/java-migration/D011/final-review.md) | pending_review |
| D012 | stk_st_daily | verified | 隔离 java_d012_stk_st_daily_96fe6e81659b405e8431d8324dfeac24 | 09-28至09-29 | 首次280；增量559 | 559/0；0重复 | 09-28→09-29 | 取消、恢复280行、撤回、空/非空发布恢复 | 2026-09-29T19:36:15.588024200Z | [结果](results/D012.json)、[验收](../../artifacts/java-migration/D012/final-review.md) | pending_review |
| D013 | etf_basic | verified | 隔离 java_d013_etf_basic_c6e3b1cdbe864651a2d34d035a14a9ee | market=E完整SNAPSHOT | 每次2961×4 | 2961/0；0重复；27字段 | 无日期游标；新观察时间刷新 | 取消、2961行恢复0发送、CLI精确恢复、丢ACK回读 | 2026-09-29T18:34:31.097185600Z | [结果](results/D013.json)、[验收](../../artifacts/java-migration/D013/final-review.md) | pending_review |
| D014 | etf_daily | verified | 隔离 java_d014_etf_daily_57f2a9e58977462b90e1a258cb77c626 | 首次09-28；增量至09-29 | 首次2163；增量4299 | 4299/0；0重复 | 09-28→09-29 | 取消、2163行恢复0发送、丢ACK回读 | 2026-09-29T18:24:43.180538700Z | [结果](results/D014.json)、[验收](../../artifacts/java-migration/D014/final-review.md) | pending_review |
| D015 | etf_adj | verified | 隔离 java_d015_etf_adj_0e27dd96a0934279b0f84c830bca2d60 | 首次09-28；增量至09-29 | 首次2177；增量4355 | 4355/0；0重复 | 09-28→09-29 | 取消、2177行恢复0发送、丢ACK回读 | 2026-09-29T19:39:18.458532300Z | [结果](results/D015.json)、[验收](../../artifacts/java-migration/D015/final-review.md) | pending_review |
| D016 | etf_share | verified | 隔离 java_d016_etf_share_03920d258719454e8da565b45ebb0c2f | 首次09-17；增量至09-18 | 首次1774；增量3566 | 3566/0；0重复 | 09-17→09-18 | 三市场、取消/CLI恢复0发送、丢ACK、空值修订及回补 | 2026-09-29T20:13:46.822309600Z | [结果](results/D016.json)、[验收](../../artifacts/java-migration/D016/final-review.md) | pending_review |
| D017 | etf_factor | verified | 隔离 java_d017_etf_factor_779e998396d74251b53b0104914f49fe | 首次09-28；增量至09-29 | 首次2163；增量4299 | 4299/0；0重复 | 09-28→09-29 | 89字段、取消恢复0发送、CLI、丢ACK回读 | 2026-09-29T20:35:07.195822800Z | [结果](results/D017.json)、[验收](../../artifacts/java-migration/D017/final-review.md) | pending_review |
| D018 | etf_portfolio | verified | 隔离 java_d018_etf_portfolio_921d13f6c4294134a69ad37cf3feb69e | 首次08-26；增量至08-27 | 首次3651；增量22369 | 22369/0；0重复 | 08-26→08-27 | 分页、多chunk复用10000补8718、CLI、丢ACK；08-28源冲突拒绝 | 2026-09-29T20:45:28.283991Z | [结果](results/D018.json)、[验收](../../artifacts/java-migration/D018/final-review.md) | pending_review |
| D019 | index_daily_market | verified | 隔离 java_d019_index_daily_market_8e5078c8551b4b2ba086f80b6ed85bc1 | 两路线09-24..29 | 各首次2；增量各3 | 6/0；0重复 | 各09-28→09-29 | 12字段、取消恢复0发送、CLI、回补及空窗 | 2026-09-29T21:05:24.727155600Z | [结果](results/D019.json)、[验收](../../artifacts/java-migration/D019/final-review.md) | pending_review |
| D020 | index_daily_basic | verified | 隔离 java_d020_index_daily_basic_5e8d6b3b491c4cedb12fcd1d45b0bf35 | 五代码09-24..29 | 各首次2；增量各3 | 15/0；0重复 | 各09-28→09-29 | 12字段、取消恢复、CLI、回补及空窗 | 2026-09-29T21:14:07.557839700Z | [结果](results/D020.json)、[验收](../../artifacts/java-migration/D020/final-review.md) | pending_review |
| D021 | index_weight | verified | 隔离 java_d021_index_weight_28b6bdc1d5144ce9b90e11142380138c | 八指数快照/08-31回补 | 五轮各4450 | 4450/0；0重复 | 七天门控09-30→10-07 | 11字段、300复用/4150补写、CLI、空窗 | 2026-09-29T21:50:51.876937300Z | [结果](results/D021.json)、[验收](../../artifacts/java-migration/D021/final-review.md) | pending_review |
| D022 | index_monthly | verified | 隔离 java_d022_index_monthly_b2c209f9df7d49f192b6d90a97586970 | 三代码06–08月 | 27/27主验收 | 9/0；0重复 | 按代码两月修订/回补不推进 | 14列、CLI、stage/发布后恢复、空窗 | 2026-09-29T22:17:19.514658400Z | [结果](results/D022.json)、[验收](../../artifacts/java-migration/D022/final-review.md) | pending_review |
| D023 | dc_index | verified | 隔离 java_d023_dc_index_a875999b9f9748b1baf7c2dfcf42b613 | 09-17/18 | 5155/5155主验收 | 2062/0；0重复 | 两日修订/回补不推进 | 11列、CLI、stage/发布后恢复 | 2026-09-29T23:03:27.442102600Z | [结果](results/D023.json)、[验收](../../artifacts/java-migration/D023/final-review.md) | pending_review |
| D024 | moneyflow | verified | 隔离 java_d024_moneyflow_e87794d99a684e6fbffcdd483d24a33e | 09-17/18 | 27765/27765主验收 | 11106/0；0重复 | receipt链/回补不推进 | 20列、CLI、复用/丢ACK | 2026-09-29T23:08:57.883820Z | [结果](results/D024.json)、[验收](../../artifacts/java-migration/D024/final-review.md) | pending_review |
| D025 | moneyflow_ths | verified | 隔离 java_d025_moneyflow_ths_8b7caadbf37548ea8a90ddd9a849709e | 09-17/18 | 26080/26080主验收 | 10432/0；0重复 | receipt链/回补不推进 | 13列、CLI、复用/丢ACK | 2026-09-29T23:12:46.697546800Z | [结果](results/D025.json)、[验收](../../artifacts/java-migration/D025/final-review.md) | pending_review |
| D026 | moneyflow_dc | verified | 隔离 java_d026_moneyflow_dc_865c042f5f534677a8755f084aabb45d | 09-17/18 | 30090/30090主验收 | 12036/0；0重复 | receipt链/回补不推进 | 15列、CLI、复用/丢ACK | 2026-09-29T23:25:20.461934500Z | [结果](results/D026.json)、[验收](../../artifacts/java-migration/D026/final-review.md) | pending_review |
| D027 | moneyflow_hsgt | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](06-flows/D027-moneyflow_hsgt.md) | pending_review |
| D028 | margin_all | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](06-flows/D028-margin_all.md) | pending_review |
| D029 | margin_detail | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](06-flows/D029-margin_detail.md) | pending_review |
| D030 | margin_secs | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](06-flows/D030-margin_secs.md) | pending_review |
| D031 | margin_zrz | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](06-flows/D031-margin_zrz.md) | pending_review |
| D032 | margin_trading | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](06-flows/D032-margin_trading.md) | pending_review |
| D033 | block_trade | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](06-flows/D033-block_trade.md) | pending_review |
| D034 | stk_shock | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](06-flows/D034-stk_shock.md) | pending_review |
| D035 | stk_high_shock | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](06-flows/D035-stk_high_shock.md) | pending_review |
| D036 | stk_alert | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](06-flows/D036-stk_alert.md) | pending_review |
| D037 | repurchase | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](06-flows/D037-repurchase.md) | pending_review |
| D038 | share_float | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](06-flows/D038-share_float.md) | pending_review |
| D039 | stk_holdernumber | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](06-flows/D039-stk_holdernumber.md) | pending_review |
| D040 | stk_holdertrade | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](06-flows/D040-stk_holdertrade.md) | pending_review |
| D041 | pledge_stat | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](06-flows/D041-pledge_stat.md) | pending_review |
| D042 | broker_recommend | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](06-flows/D042-broker_recommend.md) | pending_review |
| D043 | cyq_perf | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](06-flows/D043-cyq_perf.md) | pending_review |
| D044 | cyq_chips | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](06-flows/D044-cyq_chips.md) | pending_review |
| D045 | disclosure_date | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](07-financial/D045-disclosure_date.md) | pending_review |
| D046 | income_period_meta | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](07-financial/D046-income_period_meta.md) | pending_review |
| D047 | income | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](07-financial/D047-income.md) | pending_review |
| D048 | cashflow_period_meta | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](07-financial/D048-cashflow_period_meta.md) | pending_review |
| D049 | cashflow | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](07-financial/D049-cashflow.md) | pending_review |
| D050 | balance | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](07-financial/D050-balance.md) | pending_review |
| D051 | fina_indicator_period_meta | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](07-financial/D051-fina_indicator_period_meta.md) | pending_review |
| D052 | fina_indicator | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](07-financial/D052-fina_indicator.md) | pending_review |
| D053 | express_period_meta | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](07-financial/D053-express_period_meta.md) | pending_review |
| D054 | express | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](07-financial/D054-express.md) | pending_review |
| D055 | forecast_period_meta | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](07-financial/D055-forecast_period_meta.md) | pending_review |
| D056 | forecast | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](07-financial/D056-forecast.md) | pending_review |
| D057 | fina_audit | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](07-financial/D057-fina_audit.md) | pending_review |
| D058 | dividend | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](07-financial/D058-dividend.md) | pending_review |
| D059 | fina_mainbz | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](07-financial/D059-fina_mainbz.md) | pending_review |
| D060 | cn_bond_yield_curve | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](08-macro/D060-cn_bond_yield_curve.md) | pending_review |
| D061 | shibor | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](08-macro/D061-shibor.md) | pending_review |
| D062 | shibor_lpr | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](08-macro/D062-shibor_lpr.md) | pending_review |
| D063 | hibor | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](08-macro/D063-hibor.md) | pending_review |
| D064 | rmb_index_daily | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](08-macro/D064-rmb_index_daily.md) | pending_review |
| D065 | cn_cpi | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](08-macro/D065-cn_cpi.md) | pending_review |
| D066 | cn_ppi | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](08-macro/D066-cn_ppi.md) | pending_review |
| D067 | cn_pmi | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](08-macro/D067-cn_pmi.md) | pending_review |
| D068 | cn_m | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](08-macro/D068-cn_m.md) | pending_review |
| D069 | sf_month | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](08-macro/D069-sf_month.md) | pending_review |
| D070 | cn_gdp | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](08-macro/D070-cn_gdp.md) | pending_review |
| D071 | eco_cal | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](08-macro/D071-eco_cal.md) | pending_review |
| D072 | sge_daily | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](08-macro/D072-sge_daily.md) | pending_review |
| D073 | us_tbr | implemented_not_verified | us_tbr-v1 / 13 fields / exact-date query | isolated table contract: YEAR partition, date key (snapshot audit only; physical deployment pending) | fixture: 1 normalized row | 1 planned; live write not run | fixture readback only | no real QuestDB query | [Java fixture evidence](../java-batch-20260929/evidence/us-tbr-fixture.json) | pending_review |
| D074 | us_tltr | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](08-macro/D074-us_tltr.md) | pending_review |
| D075 | us_trltr | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](08-macro/D075-us_trltr.md) | pending_review |
| D076 | us_trycr | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](08-macro/D076-us_trycr.md) | pending_review |
| D077 | us_tycr | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](08-macro/D077-us_tycr.md) | pending_review |
| D078 | us_market_daily | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](08-macro/D078-us_market_daily.md) | pending_review |
| D079 | fut_basic | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](09-futures/D079-fut_basic.md) | pending_review |
| D080 | fut_mapping | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](09-futures/D080-fut_mapping.md) | pending_review |
| D081 | fut_daily | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](09-futures/D081-fut_daily.md) | pending_review |
| D082 | fut_settle | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](09-futures/D082-fut_settle.md) | pending_review |
| D083 | ft_limit | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](09-futures/D083-ft_limit.md) | pending_review |
| D084 | fut_holding | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](09-futures/D084-fut_holding.md) | pending_review |
| D085 | l2_dataset_manifest | verified | 本地QuestDB / `java_d085_l2_dataset_manifest_acceptance_20260930`（隔离） | BACKFILL 2026-09-21..23；重复回填；INCREMENTAL 2026-09-21..24（三日重叠） | 三次运行合计10行 | 4 / 0，16列逐字段一致 | 无→2026-09-23→2026-09-24 | 三次VERIFIED；相同范围重跑；取消边界测试；定向测试通过 | 2026-09-29T18:19:21Z / 三个runId见结果文件 | [D085结果](results/D085.json) | pending_review |
| D086 | l2_daily_features | verified | 本地QuestDB / `java_d086_l2_daily_features_acceptance_final8_20260930`（隔离） | BACKFILL 09-21..23；幂等重跑；INCREMENTAL 09-21..24（三日重叠）；精确resume | 新写10行；resume复用4行；最终4行 | 独立Parquet样例4/0，110列×4=440字段一致 | 无→09-23→09-24 | 4页；取消/源指纹漂移拒绝；resume VERIFIED；job/run/slice管理入口通过 | 2026-09-29T19:45Z / 四个runId见结果 | [D086结果](results/D086.json) | pending_review |
| D087 | l2_intraday_bar_features | verified | 本地QuestDB / `java_d087_l2_intraday_bar_features_20260930044414_f3a0942e`（隔离；正式表未修改） | BACKFILL 09-21..23；幂等重跑；INCREMENTAL 09-21..24（三日重叠）；RESUME；write-group 09-21 | 759+759+1014；resume提交0并复用1014；write-group提交253 | 1014/0，60列×1014=60,840值一致；D085正式样例15,180/15,180一致 | 09-23→09-24 | 8页；取消/过期指纹/不完整写组拒绝；精确resume；管理入口通过 | 2026-09-30 04:45 +08 / 四个runId见结果 | [D087结果](results/D087.json) | pending_review |
| D088 | l2_event_response_features | verified | 本地QuestDB / `java_d088_l2_event_response_features_20260930053712_9ced7f2a`（隔离；正式表未改） | BACKFILL 09-21..23；幂等重跑；INCREMENTAL 09-21..24（三日重叠）；RESUME；write-group 09-21 | backfill 268；incremental 354；resume复用354；write-group 101 | 354/0，73列×354=25,842值一致 | 09-23→09-24 | 4页；取消/过期指纹/不完整写组拒绝；管理入口通过；正式表114,123,728行未变 | 2026-09-30 05:37 +08 / 四个runId见结果 | [D088结果](results/D088.json) | pending_review |
| D089 | l2_t0_training_labels | verified | 本地QuestDB / `java_d089_l2_t0_training_labels_804a7fe8`（隔离；正式表未改） | BACKFILL 09-21..23；幂等重跑；INCREMENTAL 09-21..24（三日重叠）；RESUME | 759+759+1014；resume复用1014 | 1014/0，62列×1014=62,868值一致；独立Parquet全字段对照 | 09-23→09-24 | 8页；取消/过期指纹拒绝；job registry/owner status与slice可见；正式表181,049,686行前后未变；写入组合分支已接入但未端到端运行 | 2026-09-30 06:36 +08 / 四个runId见结果 | [D089结果](results/D089.json) | pending_review |
| D090 | backtest_daily | verified | 本机QuestDB正式表 `backtest_daily`（只读兼容；未建隔离写表） | BOUNDED_READ_COMPATIBILITY 2026-09-17 | formal 200 + `v_backtest_daily` 200；写入0 | 200/0；13列×200=2,600值全匹配，typed ReadGroup一致 | checkpoint N/A（无Java sync owner） | 表行数10,355,884及table_txn=17读前后不变；旧Python物化路径保留，Java无WRITE/job | 2026-09-30 07:08 +08 / 见结果与live证据 | [D090结果](results/D090.json)；[任务卡](11-derived/D090-backtest_daily.md) | pending_review |
| D091 | backtest_daily_cache_coverage | verified（retained_compatibility） | 本机QuestDB正式回执表及对应cache切片（只读） | 2026-09-21已存source_version回执 | 回执1、cache 5,565；Java写入0 | 5,565/5,565唯一键；13字段Python原版摘要匹配；typed ReadGroup一致 | checkpoint N/A（Python owner的完整性回执） | coverage 1,367行/txn=4、cache 7,531,793行/txn=48前后不变；当前source_version不同，当前版本cache miss待Python owner重建 | 2026-09-30 17:07 +08 / [结果](results/D091.json)及D091实库证据 | [D091结果](results/D091.json)；[任务卡](11-derived/D091-backtest_daily_cache_coverage.md) | pending_review |
| D092 | backtest_daily_cache | verified（retained_compatibility） | 本机QuestDB正式cache表及D091已存版本回执（只读） | 2026-09-21已存source_version切片 | Java写入0；实读5,565 | 5,565行×14列=77,910值与Python摘要核验切片全匹配；ReadGroup一致 | checkpoint N/A（Python readthrough owner） | cache 7,531,793行/txn=48、coverage 1,367行/txn=4前后不变；当前版本cache miss待Python owner重建 | 2026-09-30 17:27 +08 / [结果](results/D092.json)及D092实库证据 | [D092结果](results/D092.json)；[任务卡](11-derived/D092-backtest_daily_cache.md) | pending_review |
| D093 | v_backtest_daily | verified（retained_compatibility） | 本机QuestDB正式普通VIEW（只读） | 2026-09-17有界实读200键 | Python 200 + Java 200；Java写入0 | 13列×200=2,600值全匹配；Python VIEW_SELECT与实视图SQL哈希一致；ReadGroup一致 | View无checkpoint/独立刷新 | 视图valid，无物理WAL/分区/DEDUP；三张上游唯一键匹配；四张上游表行数/txn前后不变 | 2026-09-30 17:46 +08 / [结果](results/D093.json)及D093实库证据 | [D093结果](results/D093.json)；[任务卡](11-derived/D093-v_backtest_daily.md) | pending_review |
| D094 | market_barometer_cache_coverage | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](11-derived/D094-market_barometer_cache_coverage.md) | pending_review |
| D095 | mv_market_breadth_daily_v1 | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](11-derived/D095-mv_market_breadth_daily_v1.md) | pending_review |
| D096 | v_market_breadth_daily | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](11-derived/D096-v_market_breadth_daily.md) | pending_review |
| D097 | market_breadth_daily_cache | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](11-derived/D097-market_breadth_daily_cache.md) | pending_review |
| D098 | mv_retail_sentiment_daily_v1 | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](11-derived/D098-mv_retail_sentiment_daily_v1.md) | pending_review |
| D099 | v_retail_sentiment_daily | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](11-derived/D099-v_retail_sentiment_daily.md) | pending_review |
| D100 | retail_sentiment_daily_cache | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](11-derived/D100-retail_sentiment_daily_cache.md) | pending_review |
| D101 | etf_market_overview_daily_cache | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](11-derived/D101-etf_market_overview_daily_cache.md) | pending_review |
| D102 | v_etf_market_overview_daily | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](11-derived/D102-v_etf_market_overview_daily.md) | pending_review |
| D103 | equity_style_monthly | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](11-derived/D103-equity_style_monthly.md) | pending_review |
| D104 | macro_core_monthly | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](11-derived/D104-macro_core_monthly.md) | pending_review |
| D105 | v_macro_core_monthly | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](11-derived/D105-v_macro_core_monthly.md) | pending_review |
| D106 | macro_liquidity_credit_monthly | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](11-derived/D106-macro_liquidity_credit_monthly.md) | pending_review |
| D107 | v_macro_liquidity_credit_monthly | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](11-derived/D107-v_macro_liquidity_credit_monthly.md) | pending_review |
| D108 | market_breadth_monthly | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](11-derived/D108-market_breadth_monthly.md) | pending_review |
| D109 | v_market_breadth_monthly | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](11-derived/D109-v_market_breadth_monthly.md) | pending_review |
| D110 | regime_market_monthly | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](11-derived/D110-regime_market_monthly.md) | pending_review |
| D111 | v_regime_market_monthly | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](11-derived/D111-v_regime_market_monthly.md) | pending_review |
| D112 | regime_features_monthly | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](11-derived/D112-regime_features_monthly.md) | pending_review |
| D113 | v_regime_features_monthly | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](11-derived/D113-v_regime_features_monthly.md) | pending_review |
| D114 | regime_features_monitor_daily | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](11-derived/D114-regime_features_monitor_daily.md) | pending_review |
| D115 | v_regime_features_monitor_daily | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](11-derived/D115-v_regime_features_monitor_daily.md) | pending_review |
| D116 | regime_display_materialization_run | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](11-derived/D116-regime_display_materialization_run.md) | pending_review |
| D117 | regime_erp_source_status_daily | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](11-derived/D117-regime_erp_source_status_daily.md) | pending_review |
| D118 | regime_ma200_breadth_daily | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](11-derived/D118-regime_ma200_breadth_daily.md) | pending_review |
| D119 | regime_margin_leverage_daily | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](11-derived/D119-regime_margin_leverage_daily.md) | pending_review |
| D120 | regime_stock_return_distribution_daily | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](11-derived/D120-regime_stock_return_distribution_daily.md) | pending_review |
| D121 | market_sentiment_daily | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](11-derived/D121-market_sentiment_daily.md) | pending_review |
| D122 | fut_index_daily_snapshot | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](11-derived/D122-fut_index_daily_snapshot.md) | pending_review |
| D123 | fut_index_signal_daily | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](11-derived/D123-fut_index_signal_daily.md) | pending_review |
| D124 | fut_index_conclusion_daily | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](11-derived/D124-fut_index_conclusion_daily.md) | pending_review |
| D125 | daily_stock_analysis | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](11-derived/D125-daily_stock_analysis.md) | pending_review |
| D126 | factor_catalog | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](12-factor/D126-factor_catalog.md) | pending_review |
| D127 | factor_registry | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](12-factor/D127-factor_registry.md) | pending_review |
| D128 | factor_definition | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](12-factor/D128-factor_definition.md) | pending_review |
| D129 | factor_dependency | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](12-factor/D129-factor_dependency.md) | pending_review |
| D130 | prediction_definition_v1 | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](12-factor/D130-prediction_definition_v1.md) | pending_review |
| D131 | factor_platform_run | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](12-factor/D131-factor_platform_run.md) | pending_review |
| D132 | factor_materialization_run | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](12-factor/D132-factor_materialization_run.md) | pending_review |
| D133 | factor_observation | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](12-factor/D133-factor_observation.md) | pending_review |
| D134 | factor_daily_metric | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](12-factor/D134-factor_daily_metric.md) | pending_review |
| D135 | factor_ic_daily | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](12-factor/D135-factor_ic_daily.md) | pending_review |
| D136 | factor_regime_daily | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](12-factor/D136-factor_regime_daily.md) | pending_review |
| D137 | factor_validation_daily | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](12-factor/D137-factor_validation_daily.md) | pending_review |
| D138 | factor_corr_snapshot | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](12-factor/D138-factor_corr_snapshot.md) | pending_review |
| D139 | factor_monitor_daily | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](12-factor/D139-factor_monitor_daily.md) | pending_review |
| D140 | factor_monitor_summary | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](12-factor/D140-factor_monitor_summary.md) | pending_review |
| D141 | factor_usage | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](12-factor/D141-factor_usage.md) | pending_review |
| D142 | industry_valuation_route_assignment | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](12-factor/D142-industry_valuation_route_assignment.md) | pending_review |
| D143 | industry_valuation_materialization_run | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](12-factor/D143-industry_valuation_materialization_run.md) | pending_review |
| D144 | industry_valuation_membership_conflict | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](12-factor/D144-industry_valuation_membership_conflict.md) | pending_review |
| D145 | company_intrinsic_valuation_model | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](12-factor/D145-company_intrinsic_valuation_model.md) | pending_review |
| D146 | company_intrinsic_valuation_consensus | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](12-factor/D146-company_intrinsic_valuation_consensus.md) | pending_review |
| D147 | industry_intrinsic_valuation_state | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](12-factor/D147-industry_intrinsic_valuation_state.md) | pending_review |
| D148 | strategy_templates | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](13-strategy/D148-strategy_templates.md) | pending_review |
| D149 | strategy_instances | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](13-strategy/D149-strategy_instances.md) | pending_review |
| D150 | strategy_backtest_runs | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](13-strategy/D150-strategy_backtest_runs.md) | pending_review |
| D151 | strategy_daily_runs | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](13-strategy/D151-strategy_daily_runs.md) | pending_review |
| D152 | strategy_backtest_equity_daily | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](13-strategy/D152-strategy_backtest_equity_daily.md) | pending_review |
| D153 | strategy_backtest_positions | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](13-strategy/D153-strategy_backtest_positions.md) | pending_review |
| D154 | strategy_backtest_trades | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](13-strategy/D154-strategy_backtest_trades.md) | pending_review |
| D155 | strategy_target_positions | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](13-strategy/D155-strategy_target_positions.md) | pending_review |
| D156 | strategy_recommended_orders | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](13-strategy/D156-strategy_recommended_orders.md) | pending_review |
| D157 | strategy_run_artifact_manifest | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](13-strategy/D157-strategy_run_artifact_manifest.md) | pending_review |
| D158 | strategy_production_runs | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](13-strategy/D158-strategy_production_runs.md) | pending_review |
| D159 | strategy_production_equity_daily | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](13-strategy/D159-strategy_production_equity_daily.md) | pending_review |
| D160 | strategy_production_positions | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](13-strategy/D160-strategy_production_positions.md) | pending_review |
| D161 | strategy_production_orders | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](13-strategy/D161-strategy_production_orders.md) | pending_review |
| D162 | strategy_production_state_daily | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](13-strategy/D162-strategy_production_state_daily.md) | pending_review |
| D163 | strategy_production_pnl_attribution_daily | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](13-strategy/D163-strategy_production_pnl_attribution_daily.md) | pending_review |
| D164 | tracked_symbols | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](14-runtime/D164-tracked_symbols.md) | pending_review |
| D165 | l2_live_subscription_intents | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](14-runtime/D165-l2_live_subscription_intents.md) | pending_review |
| D166 | l2_live_subscription_events | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](14-runtime/D166-l2_live_subscription_events.md) | pending_review |
| D167 | l2_live_normalized_events | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](14-runtime/D167-l2_live_normalized_events.md) | pending_review |
| D168 | l2_live_micro_state_1m | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](14-runtime/D168-l2_live_micro_state_1m.md) | pending_review |
| D169 | l2_live_gateway_stats | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](14-runtime/D169-l2_live_gateway_stats.md) | pending_review |
| D170 | qmt_tick_data | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](14-runtime/D170-qmt_tick_data.md) | pending_review |
| D171 | qmt_1m_bars | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](14-runtime/D171-qmt_1m_bars.md) | pending_review |
| D172 | stock_minute_bars | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](14-runtime/D172-stock_minute_bars.md) | pending_review |
| D173 | qmt_assets | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](14-runtime/D173-qmt_assets.md) | pending_review |
| D174 | qmt_positions | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](14-runtime/D174-qmt_positions.md) | pending_review |
| D175 | qmt_orders | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](14-runtime/D175-qmt_orders.md) | pending_review |
| D176 | qmt_trades | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](14-runtime/D176-qmt_trades.md) | pending_review |
| D177 | trade_signals | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](14-runtime/D177-trade_signals.md) | pending_review |
| D178 | paired_execution_plans | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](14-runtime/D178-paired_execution_plans.md) | pending_review |
| D179 | orders | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](14-runtime/D179-orders.md) | pending_review |
| D180 | order_records | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](14-runtime/D180-order_records.md) | pending_review |
| D181 | order_fills | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](14-runtime/D181-order_fills.md) | pending_review |
| D182 | trade_records | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](14-runtime/D182-trade_records.md) | pending_review |
| D183 | broker_statement_deliveries | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](14-runtime/D183-broker_statement_deliveries.md) | pending_review |
| D184 | broker_statement_cashflows | planned | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](14-runtime/D184-broker_statement_cashflows.md) | pending_review |
| Q001 | alpha_research_admission_v1 | conditional | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](15-conditional/Q001-alpha_research_admission_v1.md) | pending_review |
| Q002 | alpha_source_definition_v1 | conditional | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](15-conditional/Q002-alpha_source_definition_v1.md) | pending_review |
| Q003 | alpha_source_member_v1 | conditional | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](15-conditional/Q003-alpha_source_member_v1.md) | pending_review |
| Q004 | eco_cal_daily_agg | retired_with_evidence | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](15-conditional/Q004-eco_cal_daily_agg.md) | pending_review |
| Q005 | eco_cal_quantified | retired_with_evidence | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](15-conditional/Q005-eco_cal_quantified.md) | pending_review |
| Q006 | factor_exposure_snapshot_daily | retired_with_evidence | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](15-conditional/Q006-factor_exposure_snapshot_daily.md) | pending_review |
| Q007 | factor_lifecycle_event | retired_with_evidence | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](15-conditional/Q007-factor_lifecycle_event.md) | pending_review |
| Q008 | index_daily | retired_with_evidence | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](15-conditional/Q008-index_daily.md) | pending_review |
| Q009 | macro_bond_yield | retired_with_evidence | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](15-conditional/Q009-macro_bond_yield.md) | pending_review |
| Q010 | macro_news | retired_with_evidence | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](15-conditional/Q010-macro_news.md) | pending_review |
| Q011 | prediction_observation_v1 | retired_with_evidence | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](15-conditional/Q011-prediction_observation_v1.md) | pending_review |
| Q012 | report_rc | retired_with_evidence | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](15-conditional/Q012-report_rc.md) | pending_review |
| Q013 | stock_news | retired_with_evidence | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](15-conditional/Q013-stock_news.md) | pending_review |
| Q014 | swan_industry | retired_with_evidence | 待填 | 待填 | 未执行 | 未执行 | 待填 | 未执行 | 未执行 | [任务卡](15-conditional/Q014-swan_industry.md) | pending_review |
