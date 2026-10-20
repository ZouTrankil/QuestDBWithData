package com.zoutrankil.data.repository;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.zoutrankil.data.domain.stock.basic.StockDetailInfo;
import com.zoutrankil.data.stock.domain.StockDetailState;
import com.zoutrankil.data.stock.mapper.StockDetailInfoMapper;
import com.zoutrankil.data.stock.storage.StockDetailInfoStaging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CancellationException;

import javax.sql.DataSource;

class StockDetailInfoStagingTest {
    @TempDir Path root;

    private StockDetailInfo row() {
        return new StockDetailInfo(
                "000001.SZ",
                Instant.EPOCH,
                "000001",
                "sample",
                null,
                null,
                "L",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    @Test
    void preflightCancellationDoesNotCreateTableOrConnect() throws Exception {
        var data = mock(DataSource.class);
        var writer = new StockDetailInfoStaging(new JdbcTemplate(data));
        var before =
                new StockDetailState.Snapshot(
                        new StockDetailState.Identity(1, "test"), List.of(), "test", 0);
        var prepared = StockDetailInfoStaging.prepare(before, List.of(row()));
        assertThrows(CancellationException.class, () -> writer.write(prepared, root, () -> true));
        verify(data, never()).getConnection();
    }

    @Test
    void unchangedRowsPreserveOriginalPhysicalObjectsAndDoNotConnect() throws Exception {
        var data = mock(DataSource.class);
        var writer = new StockDetailInfoStaging(new JdbcTemplate(data));
        var original = new StockDetailInfoMapper().toStorage(row());
        var before =
                new StockDetailState.Snapshot(
                        new StockDetailState.Identity(1, "test"), List.of(original), "test", 0);
        var prepared = StockDetailInfoStaging.prepare(before, List.of(row()));
        assertSame(original, prepared.rows().getFirst());
        assertFalse(prepared.merge().requiresPublication());
        assertThrows(IllegalArgumentException.class, () -> writer.write(prepared, root));
        verify(data, never()).getConnection();
    }
}
