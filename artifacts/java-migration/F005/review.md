# F005 共享限流与重试预算

PacedQuota 用单调时钟和同一锁同时核算凭证/provider总额度及接口额度，均匀放行而不在分钟边界突发。SharedRequestBudget 接入 TushareClient 唯一HTTP入口，包括原stock_basic入口；每个实际重试重新获取额度，不在底层HTTP隐式重试。默认凭证20/min、接口10/min、最大并发2、排队16、最多3次尝试、总时限2分钟、指数退避加半区间抖动（1秒基数、10秒上限）。这些是可调低的工程默认值，不是账号权限证明；每个数据任务必须核对接口额度。

配置入口为 app.tushare.global-per-minute、endpoint-per-minute、endpoint-limits、concurrency、queue-capacity、max-attempts、total-timeout、retry-base、retry-max、retryable-business-codes。例如 `--app.tushare.endpoint-limits.stock_basic=5` 将该接口降为5/min；不修改现有application.yml。接口预算与全局预算同时生效，设置某接口较高值不会绕过全局预算。

HTTP429/5xx、传输失败和明确的业务限流按有限预算重试。来源msg仅在内存识别“每分钟最多/每秒最多/每小时最多/访问频率/频率超限/rate limit”，不记录原始msg；code 2002按权限错误处理，不因消息中出现限流词而重试。其他业务权限错误默认不重试，不能凭未知数字code猜限流。Retry-After支持秒数及HTTP日期；其等待仍受总时限限制。非限流业务错误、坏数据契约、超8MiB响应不重试。future取消传播至排队、退避和传输；关闭预算取消在途工作后释放锁，重复关闭安全。

## 多实例边界

首次实际请求获取用户目录 `.questdbwithdata/credential-budgets/` 下按provider+token的SHA256命名的文件锁，不保存token。相同凭证的第二个Java预算实例立即拒绝。每次放行持久化冷却时间，进程重启不清空冷却；正常运行按单调时钟，重启冷却依赖系统墙钟稳定。冷却采用最长接口间隔，允许保守多等。

这是同一用户/主机上参与该协议的Java实例保护，不是跨主机/跨用户/任意Python程序的分布式额度服务。禁止在其他主机或未接入锁的进程同时使用同一账号跑本计划；若需要此并行部署，必须先实现共享额度服务。当前只在本地串行执行。Python参考 `D:/work/fund_2/back-monitor/src/quant_platform/common/runtime/rate_limit.py` 的均匀节流；没有照搬其按装饰器分散预算和不可取消sleep。

## 验收

SharedRequestBudgetTest 5项通过：可控时钟100个并发竞争只放行1次；全局与慢接口分别约束；重试3次逐次计费；权限/契约错误只调用1次；队列满拒绝、取消未调用来源、关闭取消在途；第二owner锁拒绝及重启冷却；最大次数与总截止。

TushareHttpClientTest 7项通过，含真实本地HTTP429 + Retry-After后第二次成功、业务限流重试，共4次网络请求对应4次预算计费；另测“频率超限”和2002权限错误的分类。8MiB超限确认为CONTRACT而非空数据/可重试网络错误。TushareHttpLiveTest 1项通过：立即连续请求单股000001.SZ，实际两次非空各1行，自动间隔约6秒、计费2次，HTTP/2复用同连接。准确间隔及公开来源响应见 source-http.json。

首次开发测试发现重复close释放已关channel异常，已修复并重跑。实际计时还发现持久化耗时会缩短传输间隔，已在持久化完成后重新确定下一许可时间并重跑真实请求。未把测试中模拟的429称为真实账号触发限流。

复现：JAVA_HOME指向jdk-24.0.2，TUSHARE_HTTP_LIVE=1，执行 `.\gradlew.bat test --tests '*SharedRequestBudgetTest' --tests '*TushareHttp*Test' --rerun-tasks --console=plain`。原验收12项通过；新增分类测试单独重跑通过，当前相关测试合计13项。数据库写入、checkpoint对本预算功能N/A；没有触发全市场或批量sync。人工复核 pending_review。
