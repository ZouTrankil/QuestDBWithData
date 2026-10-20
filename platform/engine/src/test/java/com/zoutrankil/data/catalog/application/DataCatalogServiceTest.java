package com.zoutrankil.data.catalog.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zoutrankil.data.catalog.domain.CatalogTag;
import com.zoutrankil.data.catalog.domain.DataCatalog;
import com.zoutrankil.data.catalog.domain.DataObjectDescription;
import com.zoutrankil.data.catalog.port.DataCatalogMetadataPort;
import com.zoutrankil.data.domain.DatasetDefinition;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

class DataCatalogServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-17T02:00:00Z");

    private static final class Metadata implements DataCatalogMetadataPort {
        List<DataCatalog.PhysicalObject> objects = List.of();
        int calls;

        public DataCatalog.Snapshot snapshot() {
            calls++;
            return new DataCatalog.Snapshot("instance", NOW, objects);
        }

        public List<DataCatalog.Column> columns(String name) {
            return List.of(
                    new DataCatalog.Column("code", "symbol", false, "", null),
                    new DataCatalog.Column("new_field", "double", null, "", null));
        }
    }

    private static DataCatalog.PhysicalObject physical(String name) {
        return new DataCatalog.PhysicalObject(
                name, DatasetDefinition.ObjectKind.TABLE, null, null, null, null, null, null);
    }

    private static DataObjectDescription description(String name, CatalogTag... tags) {
        return new DataObjectDescription(
                name,
                "ETF行情",
                "基金的日行情",
                Set.of(DataObjectDescription.Market.CN),
                "ETF",
                DataObjectDescription.SourceType.COLLECTED,
                "来源",
                List.of(),
                "来源采集",
                "",
                "代码登记",
                Set.of(tags));
    }

    private static DatasetDefinition definition(
            String id, String object, DatasetDefinition.ObjectKind kind) {
        return new DatasetDefinition(
                id,
                1,
                "source",
                "owner",
                object,
                kind,
                List.of(
                        new DatasetDefinition.Column(
                                "code",
                                "code",
                                "code",
                                DatasetDefinition.StorageType.SYMBOL,
                                false,
                                "证券代码",
                                null)),
                List.of("code"),
                List.of(),
                null,
                DatasetDefinition.Partition.NONE,
                false,
                Set.of(DatasetDefinition.Capability.READ),
                List.of(),
                "测试只读契约");
    }

    @Test
    void newAndDeletedObjectsFollowEachLiveSnapshot() {
        var metadata = new Metadata();
        metadata.objects = List.of(physical("fresh-table"), physical("etf"));
        var service =
                new DataCatalogService(
                        metadata,
                        List.of(description("etf", CatalogTag.CN, CatalogTag.ETF)),
                        List.of());
        var first = service.list(DataCatalog.Filter.all());
        assertEquals(
                List.of("etf", "fresh-table"),
                first.items().stream().map(DataCatalog.Entry::objectName).toList());
        assertEquals(
                DataCatalog.ClassificationStatus.UNCLASSIFIED,
                first.items().get(1).classificationStatus());
        assertNull(first.items().get(1).rowCount());
        assertFalse(first.items().get(0).readSupported());
        metadata.objects = List.of(physical("newly_created"));
        assertEquals(
                List.of("newly_created"),
                service.list(DataCatalog.Filter.all()).items().stream()
                        .map(DataCatalog.Entry::objectName)
                        .toList());
        var all = service.list(new DataCatalog.Filter(null, null, null, Set.of(), "", true));
        assertEquals(DataCatalog.Presence.MISSING, all.items().getFirst().presence());
        assertEquals(3, metadata.calls);
    }

    @Test
    void multipleTagsAreAnIntersectionAndPaginationDoesNotChangeTotal() {
        var metadata = new Metadata();
        metadata.objects = List.of(physical("b"), physical("a"), physical("c"));
        var service =
                new DataCatalogService(
                        metadata,
                        List.of(
                                description("a", CatalogTag.CN, CatalogTag.ETF),
                                description("b", CatalogTag.CN, CatalogTag.ETF),
                                description("c", CatalogTag.CN)),
                        List.of());
        var filter =
                new DataCatalog.Filter(
                        DataObjectDescription.Market.CN,
                        DataObjectDescription.SourceType.COLLECTED,
                        DatasetDefinition.ObjectKind.TABLE,
                        Set.of(CatalogTag.CN, CatalogTag.ETF),
                        "基金",
                        false);
        var page = service.page(filter, 2, 1);
        assertEquals(2, page.total());
        assertEquals("b", page.items().getFirst().objectName());
        assertEquals(
                List.of("a", "b"),
                service.list(filter).items().stream().map(DataCatalog.Entry::objectName).toList());
        assertEquals(NOW, page.observedAt());
    }

    @Test
    void invalidPaginationDoesNotQueryTheDatabaseAndLargePagesDoNotOverflow() {
        var metadata = new Metadata();
        metadata.objects = List.of(physical("a"));
        var service = new DataCatalogService(metadata, List.of(), List.of());
        assertThrows(
                IllegalArgumentException.class, () -> service.page(DataCatalog.Filter.all(), 0, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.page(DataCatalog.Filter.all(), 1, 501));
        assertEquals(0, metadata.calls);
        assertTrue(
                service.page(DataCatalog.Filter.all(), Integer.MAX_VALUE, 500).items().isEmpty());
    }

    @Test
    void nativeMvWinsOverDuplicateTableMetadata() {
        var metadata = new Metadata();
        metadata.objects =
                List.of(
                        physical("mv"),
                        new DataCatalog.PhysicalObject(
                                "mv",
                                DatasetDefinition.ObjectKind.MATERIALIZED_VIEW,
                                "date",
                                9L,
                                null,
                                null,
                                "valid",
                                null));
        var result =
                new DataCatalogService(metadata, List.of(), List.of())
                        .list(DataCatalog.Filter.all());
        assertEquals(1, result.items().size());
        assertEquals(
                DatasetDefinition.ObjectKind.MATERIALIZED_VIEW,
                result.items().getFirst().objectKind());
        assertEquals(9L, result.items().getFirst().rowCount());
    }

    @Test
    void readableContractsMustMatchTheLivePhysicalType() {
        var metadata = new Metadata();
        metadata.objects = List.of(physical("matches"), physical("changed"));
        var service =
                new DataCatalogService(
                        metadata,
                        List.of(),
                        List.of(
                                definition("one", "matches", DatasetDefinition.ObjectKind.TABLE),
                                definition("two", "changed", DatasetDefinition.ObjectKind.VIEW)));
        var entries = service.list(DataCatalog.Filter.all()).items();
        assertFalse(entries.get(0).readSupported());
        assertTrue(entries.get(1).readSupported());
    }

    @Test
    void detailsReuseContractMeaningsButKeepNewColumnMeaningUnknown() {
        var metadata = new Metadata();
        metadata.objects = List.of(physical("etf"));
        var service =
                new DataCatalogService(
                        metadata,
                        List.of(),
                        List.of(definition("etf_id", "etf", DatasetDefinition.ObjectKind.TABLE)));
        var detail = service.detail("etf");
        assertEquals("证券代码", detail.columns().getFirst().meaning());
        assertEquals("待补充", detail.columns().get(1).meaning());
        assertNull(detail.columns().get(1).nullable());
        assertEquals(List.of("code"), detail.businessKey());
        assertThrows(
                DataCatalogService.ObjectNotFoundException.class, () -> service.detail("unknown"));
    }

    @Test
    void sourceDocumentationAddsFactsWithoutOverridingLocalContractsOrPhysicalTypes() {
        var metadata = new Metadata();
        metadata.objects = List.of(physical("etf"));
        var source =
                new DataCatalog.SourceField(
                        "Tushare",
                        "raw_code",
                        "str",
                        "来源证券编号",
                        "",
                        "",
                        "https://tushare.pro/document/2?doc_id=27",
                        LocalDate.parse("2026-10-19"),
                        DataCatalog.DocumentationStatus.VERIFIED_SOURCE,
                        "来源说明不改变本地证券代码契约");
        var extra =
                new DataCatalog.SourceField(
                        "Tushare",
                        "vol",
                        "float",
                        "成交量",
                        "手",
                        "",
                        source.documentationUrl(),
                        source.reviewedOn(),
                        source.status(),
                        "");
        var service =
                new DataCatalogService(
                        metadata,
                        List.of(),
                        List.of(definition("etf_id", "etf", DatasetDefinition.ObjectKind.TABLE)),
                        Map.of("etf", Map.of("code", source, "new_field", extra)));
        var detail = service.detail("etf");
        assertEquals("证券代码", detail.columns().getFirst().meaning());
        assertEquals(source, detail.columns().getFirst().sourceDocumentation());
        assertEquals("成交量", detail.columns().get(1).meaning());
        assertEquals("double", detail.columns().get(1).storageType());
        assertNull(detail.columns().get(1).nullable());
        assertEquals("手", detail.columns().get(1).sourceDocumentation().unit());
        assertEquals(List.of("code"), detail.businessKey());
    }

    @Test
    void unverifiedSourceFactsRemainExplicitAndCannotFillUnknownMeanings() {
        var metadata = new Metadata();
        metadata.objects = List.of(physical("etf"));
        var uncertain =
                new DataCatalog.SourceField(
                        "待确认",
                        "new_field",
                        "",
                        "尚未核实",
                        "",
                        "",
                        "",
                        LocalDate.parse("2026-10-19"),
                        DataCatalog.DocumentationStatus.UNVERIFIED,
                        "文档不可用");
        var service =
                new DataCatalogService(
                        metadata,
                        List.of(),
                        List.of(),
                        Map.of("etf", Map.of("new_field", uncertain)));
        var column = service.detail("etf").columns().get(1);
        assertEquals("待补充", column.meaning());
        assertEquals(
                DataCatalog.DocumentationStatus.UNVERIFIED, column.sourceDocumentation().status());
    }

    @Test
    void registeredMissingObjectHasNoInventedColumnsOrRowCount() {
        var service =
                new DataCatalogService(
                        new Metadata(), List.of(description("gone", CatalogTag.CN)), List.of());
        var detail = service.detail("gone");
        assertEquals(DataCatalog.Presence.MISSING, detail.object().presence());
        assertNull(detail.object().rowCount());
        assertTrue(detail.columns().isEmpty());
    }

    @Test
    void tagsCountPresentObjectsOnlyAndIncludeUnusedBtcTag() {
        var metadata = new Metadata();
        metadata.objects = List.of(physical("present"));
        var service =
                new DataCatalogService(
                        metadata,
                        List.of(
                                description("present", CatalogTag.CN),
                                description("missing", CatalogTag.CN)),
                        List.of());
        var counts = service.tags().items();
        assertEquals(
                1,
                counts.stream()
                        .filter(tag -> tag.id() == CatalogTag.CN)
                        .findFirst()
                        .orElseThrow()
                        .objectCount());
        assertEquals(
                0,
                counts.stream()
                        .filter(tag -> tag.id() == CatalogTag.BTC)
                        .findFirst()
                        .orElseThrow()
                        .objectCount());
    }

    @Test
    void duplicateDescriptionsAndRegistrationsAreRejectedAtAssembly() {
        var description = description("a", CatalogTag.CN);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new DataCatalogService(
                                new Metadata(), List.of(description, description), List.of()));
        var definition = definition("one", "a", DatasetDefinition.ObjectKind.TABLE);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new DataCatalogService(
                                new Metadata(), List.of(), List.of(definition, definition)));
    }

    @Test
    void unavailableMetadataIsNotReportedAsAnEmptyDatabase() {
        DataCatalogMetadataPort metadata =
                new DataCatalogMetadataPort() {
                    public DataCatalog.Snapshot snapshot() {
                        throw new IllegalStateException("unavailable");
                    }

                    public List<DataCatalog.Column> columns(String name) {
                        return List.of();
                    }
                };
        assertThrows(
                IllegalStateException.class,
                () ->
                        new DataCatalogService(metadata, List.of(), List.of())
                                .list(DataCatalog.Filter.all()));
    }
}
