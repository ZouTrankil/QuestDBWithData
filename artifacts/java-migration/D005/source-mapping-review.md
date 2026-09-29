# D005 单行业来源与日期映射

`local-D005-source-preflight-1017`：通过共享 Java TusharePageService 发出两个明确请求，l2_code=801011.SI，分别 is_new=Y/N；Y返回4条，N返回3条，单次上限2000均未触顶，没有QuestDB写入。

完整来源回执与SHA：source-preflight-e26420e4-9eda-41a7-9a91-2329ce57419f/{Y-response.json,N-response.json,source-review.json}。历史含000663.SZ早期成员关系，当前含其新的纳入日期。7条采用(index_code,ts_code,in_date)没有键冲突；不能据一个行业证明所有行业历史完整。

已新增 TushareIndexMembershipDto（保留三级代码）、IndexMembership、IndexMembershipMapper。领域自然键不包含out_date或is_new；退出日期和是否最新可修订。源weight/con_code未提供，保持null；行业名参数需由后续分类接口冻结，当前实际单行业样例使用林业Ⅱ。

时间定义：membershipStartDate / membershipEndDate 为严格YYYYMMDD映射的LocalDate，不自行声明退出日包含关系；observedAt为UTC微秒Instant，不参与成员期间身份。实际旧表out_date字符串None只在fromStorage兼容；toStorage输出null。旧原始物理值是否保留须在后续增量merge/stage中处理，不能把规范化本身伪称来源修订。

映射测试使用7条真实来源与100条实际QuestDB样本，验证多个纳入期间、nullable字段、非法日期、越界行业和往返。第一次1018失败源于测试预期错误异常类型（日期解析实际抛DateTimeParseException）；local-D005-mapping-1019修正断言后3项全部通过。

当前尚未注册可执行Dataset owner；分类来源、限流上限、完整有界读取/写入、增量任务及恢复待实现。D005保持running，累计21项verified。
