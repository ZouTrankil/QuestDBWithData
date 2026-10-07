package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.mapper.EquityStyleMonthlyMapper;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

/** Bounded monthly typed reads. The shared reader binds the registered table's physical snapshot. */
@Repository
public class EquityStyleMonthlyReadRepository implements DatasetImplementation {
    public static final int MAX_WINDOW_MONTHS = 12;
    public static final int MAX_PAGE_SIZE = 12;
    private final QuestDbBoundedReader reader;
    private final DatasetDefinition definition;
    private final EquityStyleMonthlyMapper mapper = new EquityStyleMonthlyMapper();
    public EquityStyleMonthlyReadRepository(QuestDbBoundedReader reader) { this(reader, "equity_style_monthly"); }
    @Autowired
    public EquityStyleMonthlyReadRepository(QuestDbBoundedReader reader,
            @Value("${app.sync.equity-style-monthly.target-table:equity_style_monthly}") String table) {
        this.reader = Objects.requireNonNull(reader);
        if (!"equity_style_monthly".equals(table)) EquityStyleMonthlyWritePort.requireIsolatedTable(table);
        this.definition = EquityStyleMonthlyDataset.definition(table);
    }
    @Override public DatasetDefinition definition() { return definition; }
    public DatasetReadPage<EquityStyleMonthly> findPage(DatasetReadQuery query) {
        Objects.requireNonNull(query, "bounded monthly query required");
        requireQuery(query);
        return reader.read(definition(), query, null, mapper::fromValues);
    }
    public DatasetReadPage<EquityStyleMonthly> findKey(EquityStyleMonthlyKey key) {
        Objects.requireNonNull(key, "monthly key required");
        return findPage(new DatasetReadQuery(definition().storageColumns(), Map.of("month", key.storageDate()),
                null, null, null, 1, null));
    }
    public DatasetReadPage<EquityStyleMonthly> findForMonth(YearMonth month) { return findKey(new EquityStyleMonthlyKey(month)); }
    public DatasetReadPage<EquityStyleMonthly> findRange(YearMonth fromInclusive, YearMonth toExclusive,
                                                       int pageSize, DatasetReadCursor cursor) {
        var from = new EquityStyleMonthlyKey(fromInclusive); var to = new EquityStyleMonthlyKey(toExclusive);
        return findPage(new DatasetReadQuery(definition().storageColumns(), Map.of(), "month",
                from.storageDate(), to.storageDate(), pageSize, cursor));
    }
    public static void requireQuery(DatasetReadQuery query) {
        if (!query.columns().equals(EquityStyleMonthlyDataset.STORAGE_COLUMNS) || query.pageSize() > MAX_PAGE_SIZE)
            throw new IllegalArgumentException("Full 30 columns and a page of at most 12 months required");
        if (query.rangeColumn() == null) {
            if (!query.equalities().keySet().equals(java.util.Set.of("month"))
                    || !(query.equalities().get("month") instanceof LocalDate date))
                throw new IllegalArgumentException("Exact first-day month equality required");
            EquityStyleMonthlyKey.fromDate(date);
            return;
        }
        if (!"month".equals(query.rangeColumn()) || !query.equalities().isEmpty()
                || !(query.fromInclusive() instanceof LocalDate from) || !(query.toExclusive() instanceof LocalDate to))
            throw new IllegalArgumentException("Bounded first-day month range required");
        var first = EquityStyleMonthlyKey.fromDate(from).month(); var end = EquityStyleMonthlyKey.fromDate(to).month();
        long months = ChronoUnit.MONTHS.between(first, end);
        if (months < 1 || months > MAX_WINDOW_MONTHS)
            throw new IllegalArgumentException("Increasing monthly range of at most 12 months required");
    }
}

