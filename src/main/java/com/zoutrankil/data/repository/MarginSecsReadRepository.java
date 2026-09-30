package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetImplementation;
import com.zoutrankil.data.domain.DatasetReadCursor;
import com.zoutrankil.data.domain.DatasetReadPage;
import com.zoutrankil.data.domain.DatasetReadQuery;
import com.zoutrankil.data.domain.DatasetValues;
import com.zoutrankil.data.domain.MarginSecs;
import com.zoutrankil.data.domain.MarginSecsDataset;
import com.zoutrankil.data.domain.MarginSecsKey;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import com.zoutrankil.data.mapper.MarginSecsMapper;
import com.zoutrankil.data.service.MarginSecsSource;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Typed D030 reads project every source/storage field and retain the composite natural key. */
@Repository
public class MarginSecsReadRepository implements DatasetImplementation {
    private static final List<String> FIELDS = MarginSecsDataset.DEFINITION.columns().stream()
            .map(DatasetDefinition.Column::logicalName).toList();
    private final JdbcTemplate jdbc;
    private final String table;
    private final QuestDbBoundedReader bounded;
    private final MarginSecsMapper mapper = new MarginSecsMapper();

    public MarginSecsReadRepository(JdbcTemplate jdbc,
            @Value("${app.sync.margin-secs-table:margin_secs}") String table, QuestDbBoundedReader bounded) {
        this.jdbc = queryJdbc(jdbc);
        DatasetDefinition.identifier(table);
        this.table = table;
        this.bounded = Objects.requireNonNull(bounded);
    }

    @Override public DatasetDefinition definition() { return MarginSecsDataset.definition(table); }

    public DatasetReadPage<MarginSecs> find(DatasetReadQuery query) {
        if (query.columns().size() != FIELDS.size() || !new HashSet<>(query.columns()).equals(new HashSet<>(FIELDS)))
            throw new IllegalArgumentException("D030 reads require all four frozen fields");
        return bounded.read(definition(), query, null, mapper::fromValues);
    }

    public DatasetReadPage<MarginSecs> findByKey(MarginSecsKey key) {
        Objects.requireNonNull(key);
        return find(new DatasetReadQuery(FIELDS, Map.of("trade_date", key.tradeDate(), "ts_code", key.tsCode()),
                null, null, null, 2, null));
    }

    public DatasetReadPage<MarginSecs> findRange(LocalDate fromInclusive, LocalDate toExclusive,
            int pageSize, DatasetReadCursor cursor) {
        Objects.requireNonNull(fromInclusive);
        Objects.requireNonNull(toExclusive);
        if (!fromInclusive.isBefore(toExclusive)) throw new IllegalArgumentException("D030 range must be increasing and half-open");
        return find(new DatasetReadQuery(FIELDS, Map.of(), "trade_date", fromInclusive, toExclusive, pageSize, cursor));
    }

    public List<MarginSecs> findDate(LocalDate date, int limit) {
        Objects.requireNonNull(date);
        if (limit < 1 || limit > MarginSecsSource.API_ROW_CAP)
            throw new IllegalArgumentException("D030 date read must stay within the source cap");
        String sql = "SELECT cast(trade_date AS long) AS trade_micros,ts_code,name,exchange FROM \"" + table
                + "\" WHERE trade_date=cast(? AS TIMESTAMP) ORDER BY ts_code LIMIT " + (limit + 1);
        var rows = jdbc.query(sql, this::physical, micros(date));
        if (rows.size() > limit) throw new IllegalStateException("D030 physical date exceeds its bounded key count");
        return List.copyOf(rows);
    }

    private MarginSecs physical(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        Object raw = rs.getObject("trade_micros");
        if (!(raw instanceof Number micros)) throw new java.sql.SQLException("D030 trade_date required");
        var values = new LinkedHashMap<String, Object>();
        values.put("trade_date", TemporalValues.CalendarTimestamp.fromStorageEpoch(micros.longValue(), TemporalValues.EpochUnit.MICROS).date());
        values.put("ts_code", rs.getString("ts_code"));
        values.put("name", rs.getString("name"));
        values.put("exchange", rs.getString("exchange"));
        try { return mapper.fromValues(new DatasetValues(values)); }
        catch (RuntimeException invalid) { throw new java.sql.SQLException("Invalid physical D030 row", invalid); }
    }

    private static JdbcTemplate queryJdbc(JdbcTemplate source) {
        var jdbc = new JdbcTemplate(Objects.requireNonNull(source).getDataSource());
        jdbc.setQueryTimeout(20);
        jdbc.setMaxRows(500_001);
        return jdbc;
    }

    private static long micros(LocalDate date) {
        return new TemporalValues.CalendarTimestamp(date).storageEpoch(TemporalValues.EpochUnit.MICROS);
    }
}
