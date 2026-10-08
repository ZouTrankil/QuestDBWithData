package com.zoutrankil.data.web;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetReadPage;
import com.zoutrankil.data.domain.DatasetReadQuery;
import com.zoutrankil.data.domain.DatasetValues;
import com.zoutrankil.data.domain.StockBasicDataset;
import com.zoutrankil.data.service.DatasetReadService;
import com.zoutrankil.data.service.DatasetRegistry;
import com.zoutrankil.data.stock.application.StockBasicSyncService;
import com.zoutrankil.data.service.SyncJobRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExternalApiControllerTest {
    private final DatasetRegistry datasets = mock(DatasetRegistry.class);
    private final DatasetReadService reader = mock(DatasetReadService.class);
    private final ExternalApiController controller = new ExternalApiController(datasets,
            mock(SyncJobRegistry.class), mock(StockBasicSyncService.class), reader);

    @Test void sampleDefersReadingUntilSubscriptionAndPassesTheOriginalBoundedQuery() {
        var definition = StockBasicDataset.DEFINITION;
        when(datasets.require(definition.datasetId())).thenReturn(() -> definition);
        var page = new DatasetReadPage<>(definition.datasetId(), definition.schemaVersion(), null,
                Instant.parse("2026-10-07T00:00:00Z"), List.of(new DatasetValues(Map.of("ts_code", "000001.SZ"))), null);
        var calledThread = new AtomicReference<Thread>();
        var receivedQuery = new AtomicReference<DatasetReadQuery>();
        when(reader.read(same(definition), any())).thenAnswer(invocation -> {
            calledThread.set(Thread.currentThread());
            receivedQuery.set(invocation.getArgument(1));
            return page;
        });

        var response = controller.datasetSample(definition.datasetId(), 17);
        verifyNoInteractions(reader);
        assertSame(page, response.block(Duration.ofSeconds(5)));
        assertNotSame(Thread.currentThread(), calledThread.get());
        assertEquals(new DatasetReadQuery(definition.columns().stream().map(DatasetDefinition.Column::logicalName).toList(),
                Map.of(), null, null, null, 17, null), receivedQuery.get());
        verify(reader).read(same(definition), same(receivedQuery.get()));
        verifyNoMoreInteractions(reader);
    }

    @Test void invalidLimitsFailBeforeRegistryOrBackendAccess() {
        for (int limit : new int[]{0, 101}) {
            var failure = assertThrows(ResponseStatusException.class, () -> controller.datasetSample("unknown", limit));
            assertEquals(HttpStatus.BAD_REQUEST, failure.getStatusCode());
            assertEquals("limit must be between 1 and 100", failure.getReason());
        }
        verifyNoInteractions(datasets, reader);
    }

    @Test void unknownAndNonReadableDefinitionsKeepTheirOriginalHttpFailures() {
        when(datasets.require("unknown")).thenThrow(new IllegalArgumentException("unknown"));
        var missing = assertThrows(ResponseStatusException.class, () -> controller.datasetSample("unknown", 1));
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
        assertEquals("Unknown dataset", missing.getReason());

        var writeOnly = mock(DatasetDefinition.class);
        when(writeOnly.capabilities()).thenReturn(Set.of(DatasetDefinition.Capability.WRITE));
        when(datasets.require("write-only")).thenReturn(() -> writeOnly);
        var rejected = assertThrows(ResponseStatusException.class, () -> controller.datasetSample("write-only", 100));
        assertEquals(HttpStatus.BAD_REQUEST, rejected.getStatusCode());
        assertEquals("Dataset does not support reading", rejected.getReason());
        verifyNoInteractions(reader);
    }

    @Test void dataAccessFailureIsMappedOnlyAfterSubscription() {
        var definition = StockBasicDataset.DEFINITION;
        when(datasets.require(definition.datasetId())).thenReturn(() -> definition);
        when(reader.read(same(definition), any())).thenThrow(new DataRetrievalFailureException("private backend detail"));
        var response = controller.datasetSample(definition.datasetId(), 20);
        verifyNoInteractions(reader);
        var failure = assertThrows(ResponseStatusException.class, () -> response.block(Duration.ofSeconds(5)));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.getStatusCode());
        assertEquals("QuestDB unavailable", failure.getReason());
    }

    @Test void unrelatedRuntimeFailuresAreNotConvertedToUnavailable() {
        var definition = StockBasicDataset.DEFINITION;
        when(datasets.require(definition.datasetId())).thenReturn(() -> definition);
        var failure = new IllegalStateException("invalid data contract");
        when(reader.read(same(definition), any())).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> controller.datasetSample(definition.datasetId(), 20).block(Duration.ofSeconds(5))));
    }
}
