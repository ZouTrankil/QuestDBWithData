# D005 全表 typed read 与字段定义

已新增 IndexMembershipDataset（仅 READ）、IndexMembershipReadRepository，映射接入 DatasetValues 与统一 read group。当前没有准入同步owner或writer。

local-D005-read-1021 实际通过：5902 条分24页读取，每页最多256，完整自然键为(index_code,ts_code,membership_start_date)。独立SQL按物理(index_code,ts_code,in_date)排序逐条对照14列，0差异；读取组合按明确行业筛选并投影日期字段通过。完整证据 read-live.json，QuestDB writes=0。

首轮1020全表读取发现真实历史标识 T00018.SH，不符合六位纯数字规则。查询所有存量唯一股票代码确认该例外后，领域校验明确兼容T加五位数字的.SH代码，原值不重写、不丢弃。复测与3项映射测试均通过。

全部5902条物理out_date为旧字符串None；标准字段membership_end_date返回null。日期为空兼容在DatasetDefinition显式声明，不影响其他来源或文本字段；observed_at按epoch微秒精确对照。此读取规范化不会触发写入。

映射：index_code、ts_code不改名；update_time→observed_at，con_code→constituent_code，con_name→constituent_name，in_date→membership_start_date，out_date→membership_end_date，is_new→latest_flag；index_name、weight、level、l1_name、l2_name、l3_name保留语义。weight仅保留存量，来源未提供时不造0。

兼容性需遵守 consumer-compatibility-review.md：现生产表全Y，有调用方没有is_new筛选。后续Y/N历史写入仅隔离验收，不能把含N的表直接替换生产当前成员语义。自然键与读能力验证完成，不代表D005整体验收完成；来源adapter、增量merge/write、job/组合管理及恢复仍待完成。
