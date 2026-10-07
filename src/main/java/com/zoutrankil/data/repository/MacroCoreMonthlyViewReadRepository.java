package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.mapper.MacroCoreMonthlyViewMapper;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

/** Bounded read-only monthly alias, pinned by the shared view/base physical-snapshot guard. */
@Repository
public class MacroCoreMonthlyViewReadRepository implements DatasetImplementation {
    public static final int MAX_WINDOW_MONTHS = 12;
    public static final int MAX_PAGE_SIZE = 12;
    private final QuestDbBoundedReader reader;
    private final DatasetDefinition definition;
    private final MacroCoreMonthlyViewMapper mapper = new MacroCoreMonthlyViewMapper();
    public MacroCoreMonthlyViewReadRepository(QuestDbBoundedReader reader) {
        this(reader, MacroCoreMonthlyViewDataset.FORMAL_OBJECT);
    }
    @Autowired
    public MacroCoreMonthlyViewReadRepository(QuestDbBoundedReader reader,
            @Value("${app.sync.macro-core-monthly-view.target-view:v_macro_core_monthly}") String objectName) {
        this.reader = Objects.requireNonNull(reader);
        this.definition = MacroCoreMonthlyViewDataset.definition(objectName);
    }
    @Override public DatasetDefinition definition() { return definition; }
    public DatasetReadPage<MacroCoreMonthlyView> findPage(DatasetReadQuery query) {
        Objects.requireNonNull(query, "bounded monthly view query required");
        requireQuery(query);
        return reader.read(definition(), query, null, mapper::fromValues);
    }
    public DatasetReadPage<MacroCoreMonthlyView> findKey(MacroCoreMonthlyViewKey key) {
        Objects.requireNonNull(key, "monthly view key required");
        return findPage(new DatasetReadQuery(definition().storageColumns(), Map.of("month", key.storageDate()),
                null, null, null, 1, null));
    }
    public DatasetReadPage<MacroCoreMonthlyView> findForMonth(YearMonth month) {
        return findKey(new MacroCoreMonthlyViewKey(month));
    }
    public DatasetReadPage<MacroCoreMonthlyView> findRange(YearMonth fromInclusive, YearMonth toExclusive,
            int pageSize, DatasetReadCursor cursor) {
        var from = new MacroCoreMonthlyViewKey(fromInclusive); var to = new MacroCoreMonthlyViewKey(toExclusive);
        return findPage(new DatasetReadQuery(definition().storageColumns(), Map.of(), "month",
                from.storageDate(), to.storageDate(), pageSize, cursor));
    }
    public static void requireQuery(DatasetReadQuery query) {
        Objects.requireNonNull(query, "bounded monthly view query required");
        if (!query.columns().equals(MacroCoreMonthlyViewDataset.STORAGE_COLUMNS) || query.pageSize() > MAX_PAGE_SIZE)
            throw new IllegalArgumentException("Full nine columns and a page of at most twelve months required");
        if (query.rangeColumn() == null) {
            if (!query.equalities().keySet().equals(java.util.Set.of("month"))
                    || !(query.equalities().get("month") instanceof LocalDate date))
                throw new IllegalArgumentException("Exact first-day month equality required");
            MacroCoreMonthlyViewKey.fromDate(date);
            return;
        }
        if (!"month".equals(query.rangeColumn()) || !query.equalities().isEmpty()
                || !(query.fromInclusive() instanceof LocalDate from) || !(query.toExclusive() instanceof LocalDate to))
            throw new IllegalArgumentException("Bounded first-day month range required");
        var first = MacroCoreMonthlyViewKey.fromDate(from).month(); var end = MacroCoreMonthlyViewKey.fromDate(to).month();
        long months = ChronoUnit.MONTHS.between(first, end);
        if (months < 1 || months > MAX_WINDOW_MONTHS)
            throw new IllegalArgumentException("Increasing monthly range of at most twelve months required");
    }
}
