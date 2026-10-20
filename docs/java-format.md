# Java 格式与 import

全项目使用 4 空格排版。整理命令覆盖 Gradle 实际源码集中的主代码、测试及 Java 工具，不处理 build、日志或历史快照。

```sh
# 默认只检查；同时按真实 classpath 检查限定名和通配符 import
python3 tools/format_java.py --imports --check

# 应用整理
python3 tools/format_java.py --imports --apply
```

需要 Python 3 和 JDK 24。脚本先编译实际源码集，再用 javac 解析类型和静态成员；不按文本猜包名。无歧义时使用简单类型名，展开普通和静态通配符 import。局部类、类型参数、变量或同名类型可能遮蔽名字时，保留必要限定名。

排版使用固定的 [google-java-format 1.27.0](https://github.com/google/google-java-format/releases/tag/v1.27.0)，AOSP 的 4 空格规则。首次从 Maven Central 下载到被忽略的 `.gradle/java-format/`，随后核对脚本内固定 SHA-256。它不是应用依赖，不进入发行包，也不改变 Gradle 依赖锁。

关闭长字符串重排和 Javadoc 重排；SQL、JSON、正则、错误消息和配置键保持实际值。应用整理后再排一遍，消除移除无用 import 后留下的空行。每次检查按 javac 解码后的字面量值比较，包括 text block；发现变化就失败，不继续提交。

`generate_domain_models.py` 在临时目录使用同一排版器整理完整生成计划，通过验证后才写入目标目录。来源注释仍来自原维护资源，旧结构快照不会变成新的写入依据。

## 保留的限定名

本轮剩余 8 处类型限定，均是同名类型：兼容入口 `ScheduledLaunch`、`MainStrategyDailyScheduledLaunch` 指向另一包同名实现；ETF 视图 mapper 和测试同时使用业务值与生成投影的 `EtfMarketOverviewDailyView`；日志装配测试同时使用两个 `Logger`。保留这些限定可以直接看出差别，不能强行 import 成同一个简单名字。

全项目整理还应运行 `python3 tools/run_offline_tests.py`、架构与模块边界检查、依赖兼容及受影响发行包。纯格式整理不启动真实来源或正式写入；字面量一致也不能代替真实数据任务验收。
