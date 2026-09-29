# D005 物理快照与实际暂存写入

IndexMembershipStorage 显式读取14列，保留旧字符串None、null、采集epoch微秒与完整自然键。限制100000行/32MiB/SQL20秒；检查schema、YEAR/WAL/无dedup、物理身份、WAL收敛及读取前后writerTxn相同。local-D005-storage-1027实际5902条完整物理快照与typed回读一致，两次快照相同，证据storage-readback.json。

IndexMembershipStaging 使用已验证before和一个L2来源片重算增量，旧未变化行采用原始物理record，不因日期规范化改写。新增/修订行才转新物理值。写前保存完整意图；新建独立YEAR/WAL无dedup stage，每批最多250行、估算256KiB，参数化SQL背压串行写入。取消保留stage；WAL最多20秒等待后必须实际读回全部14列相等。没有业务变化时拒绝创建stage。

local-D005-stage-1028通过：保存的真实Y4/N3来源与实际5902行合并，新增3、修订0、未变4，24批写入隔离stage，实际5905条全字段回读相等。原5902条物理record（含5902个None）全部保留，重跑无变化不写；正式index_member前后完全相同。成功测试stage已清理。

证据：stage-a0825343-aaab-429d-96e3-a6301a605c7f/stage-readback.json。Python独立脚本verify_index_membership_stage.py验证完整与分响应SHA，按源9个可落地字段比对7条来源、0差异，并逐条核对5902旧原始行保持不变，输出同目录independent-source-values.json。

L1/L3代码仅在完整来源回执保存，旧物理表只有对应名称；不能声称物理表保存了这些源代码。当前只证明暂存写入，不包含改名发布、正式owner/checkpoint、组合写入和恢复；D005保持running，人工pending_review。
