# D005 批次失败、取消及协调任务中断

状态：D005 running，人工 pending_review。

`local-D005-batch-boundary-1049`：正常、第二行业失败、首行业完成后取消，共3项实际测试通过，0跳过。

- 第二行业来源调用受控IOException：首行业7条已验证，第三行业slot仍PENDING；父批次PARTIAL。恢复复用首行业，只执行第二、第三行业。后两行业实际Y/N均0，不改变已落地7条。
- 首行业完成后通过账本请求取消：父批次CANCELLED，后两行业均未取数。恢复仍复用首行业，继续剩余行业。
- 最终再恢复完整批次：所有行业均复用，0新增来源请求，实际目标身份及14列全值快照完全一致，父锁全部释放。

代码同时修复子任务返回CANCELLED时父批次误判普通失败的问题，并在最终回读阶段检查父取消和总时限。本轮取消注入发生在两个行业之间，不将其描述为网络请求中取消的专项证明。

证据：[失败恢复](batch-0e7499910b6b4602a71473c8c202c0dc/batch-readback.json)、[取消恢复](batch-c6fb485f2a5e49fb85cc072b08534734/batch-readback.json)。`requestOrder`包含受控失败尝试，该次IOException未实际发送HTTP请求；完整批次的`resumeSourceRequests=0`不表示恢复未完成行业时也零请求。

`local-D005-batch-interrupt-1051`：协调任务中断专项1项通过、0跳过。首行业完成后注入Error，原父run保持RUNNING；没有停止确认时拒绝续跑。`resumeStopped`确认全部已创建子任务已终结且不持数据锁后，将旧批次记PARTIAL，再建新批次继续。恢复中再次在attempt记PARTIAL后注入Error，父run仍RUNNING；再次恢复可跳过已持久的状态更新，不重复请求首行业。

证据：[协调任务及恢复过程再次中断](batch-adab1e4d12034e8cac1bd345d05c8f5d/batch-readback.json)。对应ledger保存两次中断之间及之后的不可变状态事件。新批次最终VERIFIED，目标7条实际回读一致。Error注入不是OS杀进程。

剩余：多个非空行业连续更换整表后复用、子run尚未创建/父slot尚未完整创建等更早中断、CLI与owner注册、跨数据sync/typed-write组合及最终回归。正式index_member未写入；本轮来源仍为一个非空行业和两个真实空行业，不能据此声称所有行业均已迁移。
