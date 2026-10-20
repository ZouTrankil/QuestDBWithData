package com.zoutrankil.data.service;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.zoutrankil.data.domain.DatasetReadPage;
import com.zoutrankil.data.domain.DatasetReadQuery;
import com.zoutrankil.data.domain.DatasetValues;
import com.zoutrankil.data.domain.stock.basic.StockBasicDataset;
import com.zoutrankil.data.repository.QuestDbBoundedReader;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataRetrievalFailureException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

class DatasetReadServiceTest {
    @Test
    void passesTheExactDefinitionAndQueryWithNullSourceVersionAndIdentityMapper() {
        var backend = mock(QuestDbBoundedReader.class);
        var service = new DatasetReadService(backend);
        var definition = StockBasicDataset.DEFINITION;
        var query =
                new DatasetReadQuery(
                        List.of("snapshot_ts", "ts_code"),
                        Map.of("ts_code", "000001.SZ"),
                        null,
                        null,
                        null,
                        7,
                        null);
        var row = new DatasetValues(Map.of("ts_code", "000001.SZ"));
        var page =
                new DatasetReadPage<>(
                        definition.datasetId(),
                        definition.schemaVersion(),
                        "backend-guarded-version",
                        Instant.parse("2026-10-07T00:00:00Z"),
                        List.of(row),
                        null);
        doAnswer(
                        invocation -> {
                            assertSame(definition, invocation.getArgument(0));
                            assertSame(query, invocation.getArgument(1));
                            assertNull(invocation.getArgument(2));
                            Function<DatasetValues, DatasetValues> mapper =
                                    invocation.getArgument(3);
                            assertSame(row, mapper.apply(row));
                            return page;
                        })
                .when(backend)
                .read(same(definition), same(query), isNull(), any());

        assertSame(page, service.read(definition, query));
        verify(backend).read(same(definition), same(query), isNull(), any());
        verifyNoMoreInteractions(backend);
    }

    @Test
    void leavesBackendFailuresForTheCallerToMap() {
        var backend = mock(QuestDbBoundedReader.class);
        var service = new DatasetReadService(backend);
        var query = new DatasetReadQuery(List.of("ts_code"), Map.of(), null, null, null, 1, null);
        var failure = new DataRetrievalFailureException("unavailable");
        doThrow(failure)
                .when(backend)
                .read(same(StockBasicDataset.DEFINITION), same(query), isNull(), any());
        assertSame(
                failure,
                assertThrows(
                        DataRetrievalFailureException.class,
                        () -> service.read(StockBasicDataset.DEFINITION, query)));
    }
}
