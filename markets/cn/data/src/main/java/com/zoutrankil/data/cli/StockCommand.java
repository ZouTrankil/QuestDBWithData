package com.zoutrankil.data.cli;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 股票 CLI 的唯一命令目录；外部命令字符串与持久化任务 ID 分开维护。 */
public enum StockCommand {
    /** 规划股票日线范围，不执行同步。 */
    PLAN_DAILY_JOB("plan-daily-job", "规划股票日线范围，不执行同步"),
    /** 执行股票日线同步或按原运行恢复。 */
    RUN_DAILY_JOB("run-daily-job", "执行股票日线同步或按原运行恢复"),
    /** 规划每日指标范围，不执行同步。 */
    PLAN_DAILY_BASIC_JOB("plan-daily-basic-job", "规划每日指标范围，不执行同步"),
    /** 执行每日指标同步或按原运行恢复。 */
    RUN_DAILY_BASIC_JOB("run-daily-basic-job", "执行每日指标同步或按原运行恢复"),
    /** 规划股票因子范围，不执行同步。 */
    PLAN_STK_FACTOR_JOB("plan-stk-factor-job", "规划股票因子范围，不执行同步"),
    /** 执行股票因子同步或按原运行恢复。 */
    RUN_STK_FACTOR_JOB("run-stk-factor-job", "执行股票因子同步或按原运行恢复"),
    /** 按原运行与写入日志核对股票因子恢复。 */
    RECOVER_STK_FACTOR_JOB("recover-stk-factor-job", "按原运行与写入日志核对股票因子恢复"),
    /** 规划涨跌停范围，不执行同步。 */
    PLAN_STK_LIMIT_JOB("plan-stk-limit-job", "规划涨跌停范围，不执行同步"),
    /** 执行涨跌停同步或按原运行恢复。 */
    RUN_STK_LIMIT_JOB("run-stk-limit-job", "执行涨跌停同步或按原运行恢复"),
    /** 规划ST日状态范围，不执行同步。 */
    PLAN_STK_ST_DAILY_JOB("plan-stk-st-daily-job", "规划ST日状态范围，不执行同步"),
    /** 执行ST日状态同步或按原运行恢复。 */
    RUN_STK_ST_DAILY_JOB("run-stk-st-daily-job", "执行ST日状态同步或按原运行恢复"),
    /** 规划停复牌范围，不执行同步。 */
    PLAN_STK_SUSPEND_JOB("plan-stk-suspend-job", "规划停复牌范围，不执行同步"),
    /** 执行停复牌同步或按原运行恢复。 */
    RUN_STK_SUSPEND_JOB("run-stk-suspend-job", "执行停复牌同步或按原运行恢复"),
    /** 核验原停复牌运行并完成中断发布。 */
    FINISH_STK_SUSPEND_PUBLICATION("finish-stk-suspend-publication", "核验原停复牌运行并完成中断发布"),
    /** 核验原ST日状态运行并完成中断发布。 */
    FINISH_STK_ST_DAILY_PUBLICATION("finish-stk-st-daily-publication", "核验原ST日状态运行并完成中断发布"),
    /** 执行指定证券基础资料同步。 */
    RUN_STOCK_BASIC_JOB("run-stock-basic-job", "执行指定证券基础资料同步"),
    /** 规划证券资料范围，不执行同步。 */
    PLAN_STOCK_DETAIL_JOB("plan-stock-detail-job", "规划证券资料范围，不执行同步"),
    /** 执行证券资料同步。 */
    RUN_STOCK_DETAIL_JOB("run-stock-detail-job", "执行证券资料同步"),
    /** 核验已发布证券资料的原运行。 */
    RECONCILE_STOCK_DETAIL_RUN("reconcile-stock-detail-run", "核验已发布证券资料的原运行"),
    /** 完成证券资料原运行的中断发布。 */
    FINISH_STOCK_DETAIL_PUBLICATION("finish-stock-detail-publication", "完成证券资料原运行的中断发布");

    private final String command;
    private final String desc;
    private static final Map<String, StockCommand> BY_COMMAND;

    static {
        var index = new LinkedHashMap<String, StockCommand>();
        for (var value : values()) {
            if (index.putIfAbsent(value.command, value) != null) {
                throw new IllegalStateException("重复股票命令：" + value.command);
            }
        }
        BY_COMMAND = Collections.unmodifiableMap(index);
    }

    StockCommand(String command, String desc) {
        this.command = command;
        this.desc = desc;
    }

    public String command() { return command; }
    public String desc() { return desc; }
    public static Set<String> commands() { return BY_COMMAND.keySet(); }

    /** 只按原命令精确解析，不把枚举名或大小写变体当作兼容别名。 */
    public static StockCommand require(String command) {
        var value = BY_COMMAND.get(command);
        if (value == null) throw new IllegalArgumentException(CliUsage.text());
        return value;
    }

    public static String help() {
        return Arrays.stream(values()).map(value -> value.command + "：" + value.desc)
                .collect(Collectors.joining("\n"));
    }
}
