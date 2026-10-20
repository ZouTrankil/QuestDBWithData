package com.zoutrankil.market.us.marketdata;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetDefinition.Capability;
import com.zoutrankil.data.domain.DatasetDefinition.Column;
import com.zoutrankil.data.domain.DatasetDefinition.ObjectKind;
import com.zoutrankil.data.domain.DatasetDefinition.Partition;
import com.zoutrankil.data.domain.DatasetDefinition.StorageType;
import com.zoutrankil.data.domain.DatasetDefinition.TemporalContract;
import com.zoutrankil.data.domain.DatasetDefinition.TemporalKind;

import java.util.List;
import java.util.Set;

/** 完整 ITCH 消息的局部归档；保留原字节及序号，不能用于宣称完整订单簿。 */
public final class NasdaqItchDataset {
    private NasdaqItchDataset() {}

    public static final DatasetDefinition MESSAGES =
            new DatasetDefinition(
                    "us_nasdaq_itch_messages",
                    1,
                    "nasdaq.itch5",
                    "java.market.us",
                    "us_nasdaq_itch_messages",
                    ObjectKind.TABLE,
                    List.of(
                            field(
                                    "ts",
                                    StorageType.TIMESTAMP_NS,
                                    false,
                                    "纽约当日午夜加原始纳秒偏移后对应的真实 UTC 时刻"),
                            field("message_index", StorageType.LONG, false, "原压缩文件解压消息序号，从 1 开始"),
                            field("message_type", StorageType.SYMBOL, false, "ITCH 5 消息类型"),
                            field("stock_locate", StorageType.INT, false, "当日证券定位号，不能跨日期当证券标识"),
                            field("tracking_number", StorageType.INT, false, "原始跟踪号"),
                            field("symbol", StorageType.SYMBOL, true, "通过同文件先前证券目录解析的代码"),
                            field("order_ref", StorageType.VARCHAR, true, "无符号 64 位订单编号，十进制原文"),
                            field("new_order_ref", StorageType.VARCHAR, true, "替换消息的新订单编号"),
                            field("side", StorageType.SYMBOL, true, "原消息中的 B 或 S，未知保留空值"),
                            field("shares", StorageType.LONG, true, "消息原始股数，按消息类型解释"),
                            field(
                                    "price_4",
                                    StorageType.LONG,
                                    true,
                                    "原始整数价格，USD 需除以 10000；删除/执行消息可能没有价格"),
                            field(
                                    "raw_hex",
                                    StorageType.VARCHAR,
                                    false,
                                    "包含类型字节的完整消息 payload 十六进制"),
                            field(
                                    "decompressed_offset",
                                    StorageType.LONG,
                                    false,
                                    "原文件解压后两字节消息长度头的字节偏移"),
                            field(
                                    "source_sha256",
                                    StorageType.SYMBOL,
                                    false,
                                    "保留的 gzip 前缀 SHA-256，不能当完整文件摘要"),
                            field(
                                    "source_complete",
                                    StorageType.BOOLEAN,
                                    false,
                                    "本次固定为 false，只有明确的部分原始文件"),
                            field("observed_at", StorageType.TIMESTAMP, false, "本次部分原始归档取得时间")),
                    List.of("ts", "source_sha256", "message_index"),
                    List.of("ts", "source_sha256", "message_index"),
                    "ts",
                    Partition.MONTH,
                    true,
                    Set.of(Capability.READ, Capability.WRITE),
                    List.of(),
                    "纳秒时间不截断，原始 payload 和全文件消息序号一同保留；只能核验明确的完整消息范围，不能推断全日订单流。");

    private static Column field(String name, StorageType type, boolean nullable, String meaning) {
        return new Column(
                name,
                name,
                name,
                type,
                nullable,
                meaning,
                type == StorageType.TIMESTAMP || type == StorageType.TIMESTAMP_NS
                        ? new TemporalContract(
                                TemporalKind.INSTANT,
                                "ISO-8601",
                                "UTC",
                                type == StorageType.TIMESTAMP_NS ? "NANOS" : "MICROS",
                                meaning)
                        : null);
    }
}
