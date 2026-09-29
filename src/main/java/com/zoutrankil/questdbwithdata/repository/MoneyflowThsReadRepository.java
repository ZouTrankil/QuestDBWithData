package com.zoutrankil.questdbwithdata.repository;

import com.zoutrankil.questdbwithdata.domain.DatasetDefinition;
import com.zoutrankil.questdbwithdata.domain.DatasetImplementation;
import com.zoutrankil.questdbwithdata.domain.DatasetReadCursor;
import com.zoutrankil.questdbwithdata.domain.DatasetReadPage;
import com.zoutrankil.questdbwithdata.domain.DatasetReadQuery;
import com.zoutrankil.questdbwithdata.domain.MoneyflowThs;
import com.zoutrankil.questdbwithdata.domain.MoneyflowThsDataset;
import com.zoutrankil.questdbwithdata.domain.MoneyflowThsKey;
import com.zoutrankil.questdbwithdata.domain.temporal.TemporalValues;
import com.zoutrankil.questdbwithdata.mapper.MoneyflowThsMapper;
import com.zoutrankil.questdbwithdata.service.MoneyflowThsSource;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** D025 explicit full-row typed reads with bounded pages and inclusive/exclusive ranges. */
@Repository
public class MoneyflowThsReadRepository implements DatasetImplementation {
    private static final List<String> COLUMNS = MoneyflowThsDataset.DEFINITION.columns().stream()
            .map(DatasetDefinition.Column::logicalName).toList();
    private final JdbcTemplate jdbc;
    private final String table;
    private final QuestDbBoundedReader bounded;
    private final MoneyflowThsMapper mapper = new MoneyflowThsMapper();

    @org.springframework.beans.factory.annotation.Autowired
    public MoneyflowThsReadRepository(JdbcTemplate jdbc,
            @Value("${app.sync.moneyflow-ths-table:moneyflow_ths}") String table,
            QuestDbBoundedReader bounded) {
        this.jdbc = queryJdbc(jdbc); DatasetDefinition.identifier(table); this.table = table;
        this.bounded = Objects.requireNonNull(bounded);
    }
    @Override public DatasetDefinition definition() { return MoneyflowThsDataset.definition(table); }
    public DatasetReadPage<MoneyflowThs> find(DatasetReadQuery query) {
        if (query.columns().size() != COLUMNS.size() || !new HashSet<>(query.columns()).equals(new HashSet<>(COLUMNS)))
            throw new IllegalArgumentException("D025 reads require every frozen business column");
        return bounded.read(definition(), query, null, mapper::fromValues);
    }
    public DatasetReadPage<MoneyflowThs> findByKey(MoneyflowThsKey key) {
        Objects.requireNonNull(key);
        return find(new DatasetReadQuery(COLUMNS, Map.of("ts_code", key.tsCode(), "trade_date", key.tradeDate()),
                null, null, null, 1, null));
    }
    public DatasetReadPage<MoneyflowThs> findRange(String tsCode, LocalDate fromInclusive, LocalDate toExclusive,
            int pageSize, DatasetReadCursor cursor) {
        Objects.requireNonNull(fromInclusive); Objects.requireNonNull(toExclusive);
        if (!fromInclusive.isBefore(toExclusive)) throw new IllegalArgumentException("D025 range must be increasing and half-open");
        Map<String,Object> equalities = tsCode == null ? Map.of() : Map.of("ts_code", checkedCode(tsCode));
        return find(new DatasetReadQuery(COLUMNS, equalities, "trade_date", fromInclusive, toExclusive, pageSize, cursor));
    }
    public List<MoneyflowThs> findDate(LocalDate date, int limit) {
        Objects.requireNonNull(date);
        if (limit < 1 || limit > MoneyflowThsSource.API_ROW_CAP)
            throw new IllegalArgumentException("D025 date read limit must be 1..6000");
        var rows = jdbc.query(select() + " WHERE trade_date=cast(? AS TIMESTAMP) ORDER BY ts_code LIMIT " + (limit + 1),
                this::physical, micros(date));
        if (rows.size() > limit) throw new IllegalStateException("D025 date read exceeded its declared row bound");
        return List.copyOf(rows);
    }
    public List<MoneyflowThs> findKeys(List<MoneyflowThsKey> keys) {
        if (keys == null || keys.isEmpty() || keys.size() > 250 || new HashSet<>(keys).size() != keys.size())
            throw new IllegalArgumentException("D025 complete-key reads require 1..250 unique keys");
        var clauses = new ArrayList<String>(); var parameters = new ArrayList<Object>();
        for (var key : keys) {
            clauses.add("(ts_code=? AND trade_date=cast(? AS TIMESTAMP))");
            parameters.add(key.tsCode()); parameters.add(micros(key.tradeDate()));
        }
        var rows = jdbc.query(select() + " WHERE " + String.join(" OR ", clauses)
                + " ORDER BY trade_date,ts_code LIMIT " + (keys.size() + 1), this::physical, parameters.toArray());
        if (rows.size() > keys.size()) throw new IllegalStateException("D025 key read returned duplicates/extras");
        return List.copyOf(rows);
    }
    private String select() {
        return "SELECT ts_code,cast(trade_date AS long) AS trade_micros,name,pct_change,latest,net_amount,net_d5_amount,"
                + "buy_lg_amount,buy_lg_amount_rate,buy_md_amount,buy_md_amount_rate,buy_sm_amount,buy_sm_amount_rate FROM \""
                + table + "\"";
    }
    private MoneyflowThs physical(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        Object rawDate = rs.getObject("trade_micros");
        if (!(rawDate instanceof Number date)) throw new java.sql.SQLException("D025 trade_date is required");
        var values = new java.util.LinkedHashMap<String,Object>();
        values.put("ts_code", rs.getString("ts_code"));
        values.put("trade_date", TemporalValues.CalendarTimestamp.fromStorageEpoch(date.longValue(), TemporalValues.EpochUnit.MICROS).date());
        values.put("name", rs.getString("name"));
        for (String field : List.of("pct_change", "latest", "net_amount", "net_d5_amount", "buy_lg_amount",
                "buy_lg_amount_rate", "buy_md_amount", "buy_md_amount_rate", "buy_sm_amount", "buy_sm_amount_rate")) {
            Object number = rs.getObject(field);
            if (number != null && (!(number instanceof Number n) || !Double.isFinite(n.doubleValue())))
                throw new java.sql.SQLException("D025 non-finite or non-numeric value in " + field);
            values.put(field, number == null ? null : ((Number) number).doubleValue());
        }
        try { return mapper.fromValues(new com.zoutrankil.questdbwithdata.domain.DatasetValues(values)); }
        catch (RuntimeException invalid) { throw new java.sql.SQLException("Invalid physical D025 row", invalid); }
    }
    private static JdbcTemplate queryJdbc(JdbcTemplate source) {
        var jdbc = new JdbcTemplate(Objects.requireNonNull(source).getDataSource());
        jdbc.setQueryTimeout(20); jdbc.setMaxRows(MoneyflowThsSource.API_ROW_CAP + 1); return jdbc;
    }
    private static String checkedCode(String code) { return new MoneyflowThsKey(code, LocalDate.of(2000, 1, 1)).tsCode(); }
    private static long micros(LocalDate date) {
        return new TemporalValues.CalendarTimestamp(date).storageEpoch(TemporalValues.EpochUnit.MICROS);
    }
}
