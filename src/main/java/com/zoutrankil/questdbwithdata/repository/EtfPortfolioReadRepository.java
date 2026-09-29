package com.zoutrankil.questdbwithdata.repository;

import com.zoutrankil.questdbwithdata.domain.DatasetDefinition;
import com.zoutrankil.questdbwithdata.domain.DatasetImplementation;
import com.zoutrankil.questdbwithdata.domain.DatasetReadCursor;
import com.zoutrankil.questdbwithdata.domain.DatasetReadPage;
import com.zoutrankil.questdbwithdata.domain.DatasetReadQuery;
import com.zoutrankil.questdbwithdata.domain.EtfPortfolio;
import com.zoutrankil.questdbwithdata.domain.EtfPortfolioDataset;
import com.zoutrankil.questdbwithdata.domain.EtfPortfolioKey;
import com.zoutrankil.questdbwithdata.mapper.EtfPortfolioMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bounded typed reads against a D018 isolated target, ordered by the complete business key. */
@Repository
public class EtfPortfolioReadRepository implements DatasetImplementation {
    private static final List<String> COLUMNS = EtfPortfolioDataset.DEFINITION.columns().stream()
            .map(DatasetDefinition.Column::logicalName).toList();
    private final QuestDbBoundedReader reader;
    private final String table;
    private final EtfPortfolioMapper mapper = new EtfPortfolioMapper();

    public EtfPortfolioReadRepository(QuestDbBoundedReader reader,
            @Value("${app.sync.etf-portfolio-table:java_d018_etf_portfolio_acceptance}") String table) {
        this.reader = Objects.requireNonNull(reader);
        EtfPortfolioDataset.requireIsolatedTable(table);
        this.table = table;
    }
    @Override public DatasetDefinition definition() { return EtfPortfolioDataset.definition(table); }
    public DatasetReadPage<EtfPortfolio> findByKey(EtfPortfolioKey key) {
        Objects.requireNonNull(key);
        return find(new DatasetReadQuery(COLUMNS, Map.of("ts_code", key.tsCode(), "ann_date", key.annDate(),
                "end_date", key.endDate(), "symbol", key.symbol()), null, null, null, 1, null));
    }
    public DatasetReadPage<EtfPortfolio> findAnnouncementRange(LocalDate fromInclusive, LocalDate toExclusive,
            int pageSize, DatasetReadCursor cursor) {
        Objects.requireNonNull(fromInclusive); Objects.requireNonNull(toExclusive);
        if (!fromInclusive.isBefore(toExclusive)) throw new IllegalArgumentException("Increasing half-open ann_date range required");
        return find(new DatasetReadQuery(COLUMNS, Map.of(), "ann_date", fromInclusive, toExclusive, pageSize, cursor));
    }
    public DatasetReadPage<EtfPortfolio> find(DatasetReadQuery query) {
        if (query.columns().size() != COLUMNS.size() || !new HashSet<>(query.columns()).equals(new HashSet<>(COLUMNS)))
            throw new IllegalArgumentException("Typed etf_portfolio reads require all nine frozen fields");
        return reader.read(definition(), query, null, mapper::fromValues);
    }
}
