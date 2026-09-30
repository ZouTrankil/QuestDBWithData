package com.zoutrankil.data.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.client.TushareClient;
import com.zoutrankil.data.client.dto.TusharePage;
import com.zoutrankil.data.domain.PageContract;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.*;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TushareSliceAcceptanceTest {
    private PageContract contract() {
        return new PageContract("stock_basic", TushareClient.STOCK_FIELDS, List.of("ts_code"),
                Set.of("ts_code", "list_status"), PageContract.Paging.NONE, PageContract.Completion.SHORT_PAGE,
                null, null, 2, 2, 1, 2,
                "Existing single ts_code + list_status=L filter: one expected identity; safety cap=2, not global provider limit");
    }

    @Test void cancellationCancelsActualSourceFuture() throws Exception {
        var client = mock(TushareClient.class);
        var source = new CompletableFuture<TusharePage>();
        var started = new CountDownLatch(1);
        when(client.requestAsync(any())).thenAnswer(invocation -> { started.countDown(); return source; });
        var cancelled = new AtomicBoolean();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var execution = executor.submit(() -> new TusharePageService(client).execute(contract(), Map.of("ts_code", "000001.SZ"),
                    (p, receipt) -> fail("Cancelled request cannot consume rows"), row -> {}, cancelled::get));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            cancelled.set(true);
            assertThrows(ExecutionException.class, () -> execution.get(2, TimeUnit.SECONDS));
            assertTrue(source.isCancelled());
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "TUSHARE_PAGE_LIVE", matches = "1")
    void realCodeSlicesUseSharedSourceAndConsumePagesSerially() throws Exception {
        var app = new SpringApplication(QuestDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c -> c.addBeanFactoryPostProcessor(f ->
                ((BeanDefinitionRegistry) f).removeBeanDefinition("commandLineRunner")));
        var evidence = new ArrayList<Map<String, Object>>();
        try (var ctx = app.run()) {
            var service = ctx.getBean(TusharePageService.class);
            var day = LocalDate.now(ZoneOffset.UTC);
            for (var slice : SlicePlanner.windows(day, day, 1, List.of("000001.SZ", "600000.SH"), List.of(), 2)) {
                var params = Map.<String, Object>of("ts_code", slice.code(), "list_status", "L");
                var result = service.execute(contract(), params, (page, receipt) ->
                        evidence.add(Map.of("params", params, "source_rows", page.rows(), "receipt", receipt)), row -> {
                    assertEquals(slice.code(), row.get("ts_code").asText());
                    TemporalValues.businessDate(row.get("list_date").asText(), TemporalValues.DateFormat.BASIC);
                }, () -> false);
                assertEquals(1, result.pages());
                assertEquals(1, result.rows());
            }
        }
        assertEquals(2, evidence.size());
        var out = Path.of("artifacts/java-migration/F006");
        Files.createDirectories(out);
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(out.resolve("source-pages.json").toFile(),
                Map.of("observed_at", Instant.now().toString(), "contract", contract(), "slices", evidence,
                        "scope", "Two bounded current snapshots; offset/cursor semantics tested separately, no database writes"));
    }
}
