package com.zoutrankil.data.catalog.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.catalog.application.DataCatalogService;
import com.zoutrankil.data.catalog.domain.CatalogTag;
import com.zoutrankil.data.catalog.domain.DataCatalog;
import com.zoutrankil.data.catalog.domain.DataObjectDescription;
import com.zoutrankil.data.catalog.port.DataCatalogMetadataPort;
import com.zoutrankil.data.config.WebApiConfiguration;
import com.zoutrankil.data.domain.DatasetDefinition;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import static org.junit.jupiter.api.Assertions.*;

class DataCatalogHttpTest {
    private static final String TOKEN = "test-data-catalog-token-0123456789";
    private static final Instant NOW = Instant.parse("2026-10-17T02:00:00Z");
    @Configuration(proxyBeanMethods=false) @EnableWebFlux static class WebConfiguration {}
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private AnnotationConfigApplicationContext context;
    private DisposableServer server;
    private boolean unavailable;
    private Thread metadataThread;

    @BeforeEach void start() {
        DataCatalogMetadataPort metadata = new DataCatalogMetadataPort() {
            public DataCatalog.Snapshot snapshot() {
                metadataThread = Thread.currentThread();
                if (unavailable) throw new DataAccessResourceFailureException("private backend detail");
                return new DataCatalog.Snapshot("fixture-instance", NOW, List.of(
                        new DataCatalog.PhysicalObject("mv_etf", DatasetDefinition.ObjectKind.MATERIALIZED_VIEW,
                                "trade_date", 7L, null, null, "valid", null),
                        new DataCatalog.PhysicalObject("new_table", DatasetDefinition.ObjectKind.TABLE,
                                null, null, null, null, null, null)));
            }
            public List<DataCatalog.Column> columns(String name) { return List.of(new DataCatalog.Column("price", "double", true, "", null)); }
        };
        var description = new DataObjectDescription("mv_etf", "ETF概览", "用途包含逗号,引号\"与换行\n第二行",
                Set.of(DataObjectDescription.Market.CN), "ETF", DataObjectDescription.SourceType.DERIVED,
                "来源", List.of("etf_daily"), "即时刷新", "", "代码登记", Set.of(CatalogTag.CN, CatalogTag.ETF, CatalogTag.DERIVED));
        var service = new DataCatalogService(metadata, List.of(description), List.of());
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of("app.web.api-token", TOKEN)));
        context.register(WebConfiguration.class, WebApiConfiguration.class);
        context.registerBean(DataCatalogService.class, () -> service);
        context.registerBean(DataCatalogController.class);
        context.refresh();
        server = HttpServer.create().host("127.0.0.1").port(0)
                .handle(new ReactorHttpHandlerAdapter(WebHttpHandlerBuilder.applicationContext(context).build())).bindNow();
    }
    @AfterEach void stop() { if (server != null) server.disposeNow(); if (context != null) context.close(); }
    private HttpResponse<String> get(String path, boolean auth) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path)).timeout(Duration.ofSeconds(10));
        if (auth) request.header("Authorization", "Bearer " + TOKEN);
        return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
    }
    @Test void actualHttpListFiltersMultipleTagsAndDefersJdbcOffEventLoop() throws Exception {
        var response = get("/api/v1/data-catalog?tag=CN&tag=ETF&pageSize=1", true);
        assertEquals(200, response.statusCode(), response.body());
        var body = json.readTree(response.body());
        assertEquals(1, body.path("total").asInt());
        assertEquals("mv_etf", body.path("items").get(0).path("objectName").asText());
        assertEquals("MATERIALIZED_VIEW", body.path("items").get(0).path("objectKind").asText());
        assertTrue(metadataThread.getName().startsWith("boundedElastic"), metadataThread.getName());
    }
    @Test void httpExportSharesFiltersAndPreservesCsvQuotingAndBom() throws Exception {
        var response = get("/api/v1/data-catalog/export?format=csv&tag=CN&tag=ETF", true);
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().contains("text/csv"));
        assertTrue(response.headers().firstValue("Content-Disposition").orElseThrow().contains("questdb-data-catalog-20261017-020000.csv"));
        assertTrue(response.body().startsWith("\uFEFF市场分组,"));
        assertTrue(response.body().contains("\"用途包含逗号,引号\"\"与换行\n第二行\""));
        assertTrue(response.body().contains("CN;DERIVED;ETF"));
        assertFalse(response.body().contains("new_table"));
    }
    @Test void unclassifiedObjectsKeepUnknownCountsAndDetailsExposeColumns() throws Exception {
        var list = json.readTree(get("/api/v1/data-catalog?keyword=new_table", true).body());
        assertEquals("UNCLASSIFIED", list.path("items").get(0).path("classificationStatus").asText());
        assertTrue(list.path("items").get(0).path("rowCount").isNull());
        var detail = get("/api/v1/data-catalog/objects/new_table", true);
        assertEquals(200, detail.statusCode());
        assertEquals("price", json.readTree(detail.body()).path("columns").get(0).path("name").asText());
        assertEquals(404, get("/api/v1/data-catalog/objects/unknown", true).statusCode());
    }
    @Test void tagDictionaryIncludesBtcWithZeroObjects() throws Exception {
        var response = get("/api/v1/data-catalog/tags", true);
        assertEquals(200, response.statusCode());
        var tags = json.readTree(response.body()).path("items");
        boolean found = false;
        for (var tag : tags) if (tag.path("id").asText().equals("BTC")) {
            found = true; assertEquals(0, tag.path("objectCount").asLong());
        }
        assertTrue(found);
    }
    @Test void existingBearerAuthenticationProtectsListAndDownload() throws Exception {
        assertEquals(401, get("/api/v1/data-catalog", false).statusCode());
        assertEquals(401, get("/api/v1/data-catalog/export?format=csv", false).statusCode());
        assertNull(metadataThread);
    }
    @Test void invalidFiltersFormatsAndPagesReturn400() throws Exception {
        for (String suffix : List.of("?tag=NOT_A_TAG", "?market=MARS", "?sourceType=bad", "?objectKind=bad",
                "?page=0", "?pageSize=501", "/export?format=xlsx")) {
            assertEquals(400, get("/api/v1/data-catalog" + suffix, true).statusCode(), suffix);
        }
        assertNull(metadataThread);
    }
    @Test void backendFailureReturns503ForJsonAndDownload() throws Exception {
        unavailable = true;
        for (String suffix : List.of("", "/export?format=csv")) {
            var response = get("/api/v1/data-catalog" + suffix, true);
            assertEquals(503, response.statusCode());
            assertFalse(response.body().contains("private backend detail"));
        }
    }
}
