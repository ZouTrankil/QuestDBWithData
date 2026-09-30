package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetImplementation;
import com.zoutrankil.data.domain.DatasetReadCursor;
import com.zoutrankil.data.domain.DatasetReadPage;
import com.zoutrankil.data.domain.DatasetReadQuery;
import com.zoutrankil.data.domain.MoneyflowHsgt;
import com.zoutrankil.data.domain.MoneyflowHsgtDataset;
import com.zoutrankil.data.domain.MoneyflowHsgtKey;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import com.zoutrankil.data.mapper.MoneyflowHsgtMapper;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** D027 explicit full-field typed reads. Duplicate physical dates are rejected by the bounded reader. */
@Repository
public class MoneyflowHsgtReadRepository implements DatasetImplementation {
    private static final List<String> FIELDS = MoneyflowHsgtDataset.DEFINITION.columns().stream()
            .map(DatasetDefinition.Column::logicalName).toList();
    private final JdbcTemplate jdbc;
    private final String table;
    private final QuestDbBoundedReader bounded;
    private final MoneyflowHsgtMapper mapper = new MoneyflowHsgtMapper();
    public MoneyflowHsgtReadRepository(JdbcTemplate jdbc,
            @Value("${app.sync.moneyflow-hsgt-table:moneyflow_hsgt}") String table,
            QuestDbBoundedReader bounded) {
        this.jdbc = queryJdbc(jdbc); DatasetDefinition.identifier(table); this.table = table;
        this.bounded = Objects.requireNonNull(bounded);
    }
    @Override public DatasetDefinition definition() { return MoneyflowHsgtDataset.definition(table); }
    public DatasetReadPage<MoneyflowHsgt> find(DatasetReadQuery query) {
        if (query.columns().size() != FIELDS.size() || !new HashSet<>(query.columns()).equals(new HashSet<>(FIELDS)))
            throw new IllegalArgumentException("D027 reads require all seven frozen business fields");
        return bounded.read(definition(), query, null, mapper::fromValues);
    }
    public DatasetReadPage<MoneyflowHsgt> findByKey(MoneyflowHsgtKey key) {
        Objects.requireNonNull(key);
        return find(new DatasetReadQuery(FIELDS, Map.of("trade_date", key.tradeDate()), null, null, null, 2, null));
    }
    public DatasetReadPage<MoneyflowHsgt> findRange(LocalDate fromInclusive, LocalDate toExclusive,
            int pageSize, DatasetReadCursor cursor) {
        Objects.requireNonNull(fromInclusive); Objects.requireNonNull(toExclusive);
        if (!fromInclusive.isBefore(toExclusive)) throw new IllegalArgumentException("D027 range must be increasing and half-open");
        return find(new DatasetReadQuery(FIELDS, Map.of(), "trade_date", fromInclusive, toExclusive, pageSize, cursor));
    }
    public List<MoneyflowHsgt> findDate(LocalDate date, int limit) {
        Objects.requireNonNull(date);
        if (limit < 1 || limit > 2) throw new IllegalArgumentException("D027 natural-date read allows 1..2 rows to detect duplicates");
        var rows = jdbc.query(select() + " WHERE trade_date=cast(? AS TIMESTAMP) LIMIT " + (limit + 1), this::physical, micros(date));
        if (rows.size() > limit) throw new IllegalStateException("D027 physical natural date is duplicated");
        return List.copyOf(rows);
    }
    private String select() { return "SELECT cast(trade_date AS long) AS trade_micros,ggt_ss,ggt_sz,hgt,sgt,north_money,south_money FROM \"" + table + "\""; }
    private MoneyflowHsgt physical(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        Object raw = rs.getObject("trade_micros");
        if (!(raw instanceof Number micros)) throw new java.sql.SQLException("D027 trade_date is required");
        var v = new java.util.LinkedHashMap<String,Object>();
        v.put("trade_date", TemporalValues.CalendarTimestamp.fromStorageEpoch(micros.longValue(), TemporalValues.EpochUnit.MICROS).date());
        for (String f : List.of("ggt_ss","ggt_sz","hgt","sgt","north_money","south_money")) {
            Object value = rs.getObject(f);
            if (value != null && (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())))
                throw new java.sql.SQLException("D027 invalid physical metric " + f);
            v.put(f, value == null ? null : ((Number)value).doubleValue());
        }
        try { return mapper.fromValues(new com.zoutrankil.data.domain.DatasetValues(v)); }
        catch (RuntimeException invalid) { throw new java.sql.SQLException("Invalid physical D027 row", invalid); }
    }
    private static JdbcTemplate queryJdbc(JdbcTemplate source) {
        var jdbc = new JdbcTemplate(Objects.requireNonNull(source).getDataSource()); jdbc.setQueryTimeout(20); jdbc.setMaxRows(367); return jdbc;
    }
    private static long micros(LocalDate date) { return new TemporalValues.CalendarTimestamp(date).storageEpoch(TemporalValues.EpochUnit.MICROS); }
}
