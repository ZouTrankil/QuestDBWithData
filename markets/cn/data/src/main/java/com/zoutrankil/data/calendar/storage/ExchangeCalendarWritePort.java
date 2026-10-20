package com.zoutrankil.data.calendar.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.calendar.mapper.ExchangeCalendarMapper;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.stock.basic.ExchangeCalendar;
import com.zoutrankil.data.domain.stock.basic.ExchangeCalendarDataset;
import com.zoutrankil.data.domain.stock.basic.table.ExchangeCalendarRow;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import com.zoutrankil.data.repository.DatasetWritePreparation;
import com.zoutrankil.data.repository.QuestDbWriteChecks;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;

import io.questdb.client.QuestDB;
import io.questdb.client.Sender;

import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** D001 finite QWP writes and exact four-column readback. Caller owns run/lock/verification. */
public final class ExchangeCalendarWritePort
        implements VerifiedWriteSession<ExchangeCalendar, ExchangeCalendar.Key> {
    private static final ObjectMapper CODEC_MAPPER = JobDefinitionJson.mapper();
    private final String table;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;
    private final ExchangeCalendarMapper mapper = new ExchangeCalendarMapper();
    public static final VerifiedBatchExecutor.Codec<ExchangeCalendar, ExchangeCalendar.Key> CODEC =
            new VerifiedBatchExecutor.Codec<>() {
                public ExchangeCalendar.Key key(ExchangeCalendar row) {
                    return row.key();
                }

                public byte[] canonicalBytes(ExchangeCalendar row) {
                    try {
                        return CODEC_MAPPER.writeValueAsBytes(row);
                    } catch (IOException e) {
                        throw new IllegalArgumentException("Cannot encode calendar row", e);
                    }
                }

                public int estimatedTransportBytes(ExchangeCalendar row, byte[] bytes) {
                    return Math.addExact(Math.multiplyExact(bytes.length, 4), 96);
                }
            };

    public ExchangeCalendarWritePort(String table, JdbcTemplate jdbc, QuestDB questdb) {
        DatasetDefinition.identifier(table);
        this.table = table;
        this.questdb = Objects.requireNonNull(questdb);
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));
        this.jdbc.setQueryTimeout(20);
        this.jdbc.setMaxRows(367);
    }

    public void preflight() {
        QuestDbWriteChecks.preflight(jdbc, table, ExchangeCalendarDataset.DEFINITION);
    }

    public void send(List<ExchangeCalendar> rows) {
        if (rows.isEmpty())
            throw new IllegalArgumentException("Empty calendar batch must not reach writer");
        DatasetWritePreparation.prepare(
                ExchangeCalendarDataset.DEFINITION,
                rows,
                mapper::values,
                new DatasetWritePreparation.Limits(366, 1024 * 1024));
        long bytes =
                rows.stream()
                        .mapToLong(r -> CODEC.estimatedTransportBytes(r, CODEC.canonicalBytes(r)))
                        .sum();
        if (bytes > 1024 * 1024)
            throw new IllegalArgumentException("Calendar transport budget exceeded");
        var current = new HashMap<ExchangeCalendar.Key, ExchangeCalendar>();
        for (var row : readback(rows.stream().map(ExchangeCalendar::key).toList()))
            if (current.putIfAbsent(row.key(), row) != null)
                throw new IllegalStateException(
                        "Duplicate calendar target key before incremental write");
        var delta = rows.stream().filter(row -> !row.equals(current.get(row.key()))).toList();
        if (delta.isEmpty()) return;
        try (Sender sender = questdb.borrowSender()) {
            for (var value : delta) {
                var stored = mapper.toStorage(value);
                var row =
                        sender.table(table)
                                .symbol("exchange", stored.exchange())
                                .longColumn("is_open", stored.isOpen());
                if (stored.pretradeDate() != null)
                    row.stringColumn("pretrade_date", stored.pretradeDate());
                row.at(stored.calDate());
            }
            long sequence = sender.flushAndGetSequence();
            if (sequence < 0 || !sender.awaitAckedFsn(sequence, 10000))
                throw new IllegalStateException(
                        "Calendar acknowledgement unknown; reconcile before replay");
        }
    }

    public List<ExchangeCalendar> readback(List<ExchangeCalendar.Key> keys) {
        if (keys.isEmpty()) return List.of();
        if (keys.size() > 366 || new HashSet<>(keys).size() != keys.size())
            throw new IllegalArgumentException("Finite unique readback keys required");
        var clauses = new ArrayList<String>();
        var params = new ArrayList<Object>();
        for (var key : keys) {
            clauses.add("(exchange=? AND cal_date=cast(? as TIMESTAMP))");
            params.add(key.exchange());
            params.add(
                    new TemporalValues.CalendarTimestamp(key.calendarDate())
                            .storageEpoch(TemporalValues.EpochUnit.MICROS));
        }
        return jdbc.query(
                "SELECT exchange,cast(cal_date as long) AS micros,is_open,pretrade_date FROM "
                        + table
                        + " WHERE "
                        + String.join(" OR ", clauses)
                        + " ORDER BY exchange,cal_date LIMIT "
                        + (keys.size() + 1),
                (rs, n) -> {
                    Object flag = rs.getObject("is_open");
                    return mapper.fromStorage(
                            new ExchangeCalendarRow(
                                    rs.getString("exchange"),
                                    TemporalValues.epoch(
                                            rs.getLong("micros"),
                                            TemporalValues.EpochUnit.MICROS,
                                            TemporalValues.Precision.MICROS),
                                    flag == null ? null : ((Number) flag).intValue(),
                                    rs.getString("pretrade_date")));
                },
                params.toArray());
    }

    public boolean walSettled() {
        return QuestDbWriteChecks.walSettled(jdbc, table);
    }

    @Override
    public VerifiedBatchExecutor.Codec<ExchangeCalendar, ExchangeCalendar.Key> codec() {
        return CODEC;
    }
}
