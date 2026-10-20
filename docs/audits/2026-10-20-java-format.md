# 全项目 Java 格式整理核对

本轮处理 Gradle 实际源码集中的 1966 个既有 Java 文件，另包含新 Java import 工具，共 1967 个。退役代码已先删除，未计入格式范围。

使用 javac 实际编译 classpath 解析符号；普通与静态通配符 import 全部展开。必要同名限定保留，详见[规则和例外](../java-format.md)。SQL、JSON、错误消息、命令与配置键没有修改。

逐文件将 `08b995e4` 的原始源码与整理结果交给 javac 解析，比较字面量类型及实际值的有序摘要。1966 个文件全部一致，包括 text block 的实际值；不以肉眼 diff 代替这项检查。原始与当前摘要保存在本地 `.gradle/java-format/*-literals.tsv`，历史源码仅解包到忽略目录。

| 源码集 | 检查文件 | 实际改变 |
| --- | ---: | ---: |
| `::test` | 1 | 1 |
| `:markets:btc:main` | 5 | 5 |
| `:markets:btc:test` | 1 | 1 |
| `:markets:cn:main` | 260 | 156 |
| `:markets:cn:test` | 3 | 3 |
| `:markets:us:main` | 23 | 20 |
| `:markets:us:test` | 12 | 12 |
| `:platform:app-support:main` | 9 | 8 |
| `:platform:app-support:test` | 3 | 3 |
| `:platform:core:main` | 28 | 27 |
| `:platform:core:test` | 1 | 1 |
| `:platform:engine:main` | 17 | 17 |
| `:platform:engine:test` | 5 | 5 |
| `:platform:runtime-support:main` | 6 | 6 |
| `:platform:runtime-support:test` | 1 | 1 |
| `:markets:btc:data-app:main` | 2 | 2 |
| `:markets:btc:data-app:test` | 1 | 1 |
| `:markets:cn:batch:main` | 59 | 58 |
| `:markets:cn:batch:test` | 43 | 43 |
| `:markets:cn:data:main` | 846 | 841 |
| `:markets:cn:data:test` | 428 | 426 |
| `:markets:cn:data-app:main` | 2 | 2 |
| `:markets:cn:data-app:test` | 2 | 2 |
| `:markets:cn:l2-live-app:main` | 23 | 23 |
| `:markets:cn:l2-live-app:test` | 17 | 17 |
| `:markets:cn:main-strategy-runtime:main` | 41 | 41 |
| `:markets:cn:main-strategy-runtime:test` | 16 | 16 |
| `:markets:cn:source:main` | 19 | 19 |
| `:markets:us:data-app:main` | 9 | 9 |
| `:markets:us:data-app:test` | 8 | 8 |
| `:platform:engine:batch:main` | 9 | 9 |
| `:platform:engine:questdb:main` | 40 | 39 |
| `:platform:engine:questdb:test` | 4 | 4 |
| `:platform:engine:source:main` | 21 | 21 |
| `:platform:engine:source:test` | 1 | 1 |

未改变的文件已检查，未人为制造 diff。根 build、日志、历史审计和第三方库不在整理范围。模型生成器在写入前复用同一格式器，生成器离线回归 24 项通过。

## 验证结果

`format_java.py --imports --check` 通过，1967 文件排版稳定、字面量不变。离线检查 `20261020T112633571797Z`：4617 项，4 项跳过，零失败。架构、模块边界、依赖兼容和 Batch 驱动检查通过；根/CN 数据、主策略、实时 L2、US、BTC 发行包构建通过。发行包中的应用与 CN jar 未残留 `MarginZrz`、`MoneyflowDc` 类型。

没有启动正式同步、策略发布或生产表迁移。Gradle 原有弃用提示及 JDK 原生访问提示仍存在，不属于此次纯格式变更。
