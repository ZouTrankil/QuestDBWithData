# D005 SW2021 分类与成员来源绑定

IndexMembershipClassificationSource 固定 index_classify(level=L2,src=SW2021)，只执行一次有界分类观察。保存原始7字段、参数、完成信息及SHA回执，分类证据上限1MiB、行数本地上限2000；本地预算不冒充官方接口限额或历史完整性。

分类实体验证代码、行业名、父分类、级别、版本和is_pub；不把is_pub=0作为剔除条件。select要求1..32个明确且唯一的L2代码，禁止空列表隐式全选；缺席代码拒绝。返回成员请求scope使用分类回执中的名称，调用方不再凭空拼接名称。以后大批组合可拆为多个有界计划，逐行业验证。

local-D005-discovery-1026全部3项通过：

- 两项边界：未发布行业可选择；空/重复选择或未知代码拒绝；空分类、重复身份、错版本及取消不生成已接受回执。
- 实际链路：读取134个SW2021 L2分类，冻结801011.SI/林业Ⅱ，接着通过成员adapter串行Y/N读取共7条，行业名逐条一致。没有QuestDB写入。

证据：discovery-8303c5c1-b6ec-4472-9a0d-0d9e2486ea66/discovery-readback.json，关联原始分类与成员完整回执。

当前只是来源和有界scope选择。正式job尚需把分类SHA/成员范围绑定到账本，逐行业推进已验证checkpoint；写入、组合任务和恢复尚未完成。D005仍running，累计21项verified。
