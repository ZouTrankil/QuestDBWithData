# D005 有界成员来源适配器

IndexMembershipSource 按一个明确L2代码、冻结行业名称和CURRENT/HISTORICAL/BOTH选择取数。BOTH固定顺序Y→N；每个请求最多一页、2000行，触顶拒绝，不发送不存在的offset/limit参数，不聚合所有行业到内存。

每个成功响应保存原始11字段、参数、完成信息及SHA256，完整slice回执包含两次响应和标准化行；只有所有请求成功且自然键没有冲突才生成。单响应证据4MiB、完整slice8MiB；三级源代码保留在原始回执，投影后撞自然键拒绝。

共用TusharePageService的HTTP重试、并发队列、全局和endpoint限流。index_classify上限100/min、index_member_all上限5000/min取自Python配置事实，仍取默认/显式配置中更严格的值，不声称该账号获得这些额度。

local-D005-source-boundaries-1024四项通过：显式Y/N空响应、第二请求失败（保留部分证据且无完整回执）、跨Y/N同一期间冲突、2000触顶、错误flag、预取消、保留更严限流配置。空slice是来源完成证据，不是写入成功或历史完整性证明。

local-D005-source-adapter-1025通过真实来源适配器：当前4条、历史3条，完整回执SHA匹配、自然键无冲突、统一UTC微秒观察时间，无QuestDB写入。证据source-adapter-7ec60463-be52-45b5-bcf2-bebdee34fea3/source-review.json。

其接收的行业名称目前是显式冻结参数。分类发现尚需正式adapter和job计划绑定，不能仅凭传入名称证明分类版本。任务owner、隔离写入、组合与恢复仍待完成。
