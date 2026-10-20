-- 只在写入与消费者退役部署完成，且原账本、锁、恢复归属核清后执行。
-- 本文件没有自动执行。不要当作应用启动迁移。
-- 先运行清理前查询并保存结果；存在相关视图或未决写入时停止。
SELECT * FROM tables() WHERE table_name IN ('margin_zrz', 'moneyflow_dc', 'qmt_1m_bars');
SELECT * FROM materialized_views();

-- 以下三条只删除明确退役的正式表。不要扩展为前缀批量删除。
-- 必要恢复材料应已按原备份策略保留；DDL 不能假定事务回滚。
DROP TABLE IF EXISTS margin_zrz;
DROP TABLE IF EXISTS moneyflow_dc;
DROP TABLE IF EXISTS qmt_1m_bars;

-- 必须返回零行。stage、backup 及本地数据另按归属逐项列名处理。
SELECT * FROM tables() WHERE table_name IN ('margin_zrz', 'moneyflow_dc', 'qmt_1m_bars');
