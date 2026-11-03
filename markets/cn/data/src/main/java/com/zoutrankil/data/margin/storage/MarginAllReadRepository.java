package com.zoutrankil.data.margin.storage;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetImplementation;
import com.zoutrankil.data.domain.DatasetReadCursor;
import com.zoutrankil.data.domain.DatasetReadPage;
import com.zoutrankil.data.domain.DatasetReadQuery;
import com.zoutrankil.data.domain.DatasetValues;
import com.zoutrankil.data.domain.stock.margin.MarginAll;
import com.zoutrankil.data.domain.stock.margin.MarginAllDataset;
import com.zoutrankil.data.domain.stock.margin.MarginAllKey;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import com.zoutrankil.data.margin.mapper.MarginAllMapper;
import com.zoutrankil.data.repository.DatasetPublicationReadModel;
import com.zoutrankil.data.repository.DatasetPublicationReadModel.State;
import com.zoutrankil.data.repository.QuestDbBoundedReader;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Typed D028 reads use every frozen source/storage field and reject duplicate natural keys. */
@Repository
public class MarginAllReadRepository implements DatasetImplementation {
    private static final List<String> FIELDS =
            MarginAllDataset.DEFINITION.columns().stream()
                    .map(DatasetDefinition.Column::logicalName)
                    .toList();
    private final JdbcTemplate jdbc;
    private final String table;
    private final QuestDbBoundedReader bounded;
    private final MarginAllMapper mapper = new MarginAllMapper();
    private final Path ledger;

    @Autowired
    public MarginAllReadRepository(
            JdbcTemplate jdbc,
            @Value("${app.sync.margin-all-table:margin_all}") String table,
            QuestDbBoundedReader bounded,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger) {
        this.ledger = Path.of(ledger);
        this.jdbc = queryJdbc(jdbc);
        DatasetDefinition.identifier(table);
        this.table = table;
        this.bounded = Objects.requireNonNull(bounded);
    }

    public MarginAllReadRepository(JdbcTemplate jdbc, String table, QuestDbBoundedReader bounded) {
        this(jdbc, table, bounded, "var/sync-ledger.sqlite3");
    }

    /** 页面前后固定物理代和WAL版本，发布或原锁未决时拒绝新读取。 */
    public String sourceVersion() {
        if (!"margin_all".equals(table)) return null;
        try {
            if (DatasetPublicationReadModel.read(ledger, "margin_all") != State.CLEAR)
                throw new IllegalStateException("汇总仍有原锁或未决发布");
            var identity = new MarginAllStorage(jdbc, table).preflight();
            return MarginAllStorage.physicalTargetId(jdbc, table, identity)
                    + ":"
                    + identity.writerTxn();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("汇总读取版本不可用", e);
        }
    }

    @Override
    public DatasetDefinition definition() {
        return MarginAllDataset.definition(table);
    }

    public DatasetReadPage<MarginAll> find(DatasetReadQuery query) {
        if (query.columns().size() != FIELDS.size()
                || !new HashSet<>(query.columns()).equals(new HashSet<>(FIELDS)))
            throw new IllegalArgumentException("D028 reads require all nine columns");
        String before = sourceVersion();
        var result = bounded.read(definition(), query, before, mapper::fromValues);
        if (!Objects.equals(before, sourceVersion()))
            throw new IllegalStateException("汇总分页期间物理代或WAL版本改变");
        return result;
    }

    public DatasetReadPage<MarginAll> findByKey(MarginAllKey key) {
        Objects.requireNonNull(key);
        return find(
                new DatasetReadQuery(
                        FIELDS,
                        Map.of("trade_date", key.tradeDate(), "exchange_id", key.exchangeId()),
                        null,
                        null,
                        null,
                        2,
                        null));
    }

    public DatasetReadPage<MarginAll> findRange(
            LocalDate from, LocalDate to, int pageSize, DatasetReadCursor cursor) {
        Objects.requireNonNull(from);
        Objects.requireNonNull(to);
        if (!from.isBefore(to))
            throw new IllegalArgumentException("D028 range must increase and be half-open");
        return find(
                new DatasetReadQuery(FIELDS, Map.of(), "trade_date", from, to, pageSize, cursor));
    }

    private String select() {
        return "SELECT cast(trade_date AS long) AS trade_micros,exchange_id,rzye,rzmre,rzche,rqye,rqmcl,rzrqye,rqyl FROM \""
                + table
                + "\"";
    }

    private MarginAll physical(ResultSet rs, int n) throws SQLException {
        Object raw = rs.getObject("trade_micros");
        if (!(raw instanceof Number micros)) throw new SQLException("D028 trade_date missing");
        var v = new LinkedHashMap<String, Object>();
        v.put(
                "trade_date",
                TemporalValues.CalendarTimestamp.fromStorageEpoch(
                                micros.longValue(), TemporalValues.EpochUnit.MICROS)
                        .date());
        v.put("exchange_id", rs.getString("exchange_id"));
        for (String f : List.of("rzye", "rzmre", "rzche", "rqye", "rqmcl", "rzrqye", "rqyl")) {
            Object value = rs.getObject(f);
            if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue()))
                throw new SQLException("D028 required finite field " + f);
            v.put(f, number.doubleValue());
        }
        try {
            return mapper.fromValues(new DatasetValues(v));
        } catch (RuntimeException invalid) {
            throw new SQLException("Invalid physical D028 row", invalid);
        }
    }

    private static JdbcTemplate queryJdbc(JdbcTemplate source) {
        var jdbc = new JdbcTemplate(Objects.requireNonNull(source).getDataSource());
        jdbc.setQueryTimeout(20);
        jdbc.setMaxRows(10001);
        return jdbc;
    }
}
