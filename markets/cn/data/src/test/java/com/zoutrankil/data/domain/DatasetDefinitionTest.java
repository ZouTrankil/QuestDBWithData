package com.zoutrankil.data.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.zoutrankil.data.domain.DatasetDefinition.Capability;
import com.zoutrankil.data.domain.DatasetDefinition.Column;
import com.zoutrankil.data.domain.DatasetDefinition.ObjectKind;
import com.zoutrankil.data.domain.DatasetDefinition.Partition;
import com.zoutrankil.data.domain.DatasetDefinition.StorageType;
import com.zoutrankil.data.domain.stock.basic.StockBasicDataset;
import com.zoutrankil.data.service.DatasetRegistry;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

class DatasetDefinitionTest {
    private final DatasetDefinition sample = StockBasicDataset.DEFINITION;

    private DatasetDefinition copy(
            String id,
            ObjectKind kind,
            List<String> keys,
            List<String> dedup,
            String ts,
            Partition partition,
            boolean wal,
            Set<Capability> caps,
            List<String> dependencies) {
        return new DatasetDefinition(
                id,
                1,
                sample.provider(),
                sample.owner(),
                id,
                kind,
                sample.columns(),
                keys,
                dedup,
                ts,
                partition,
                wal,
                caps,
                dependencies,
                "Test storage contract");
    }

    @Test
    void duplicateIdentityAndDependencyErrorsAreRejected() {
        DatasetImplementation adapter = () -> sample;
        assertThrows(
                IllegalArgumentException.class,
                () -> new DatasetRegistry(List.of(adapter, adapter)));
        var missing =
                copy(
                        "a",
                        ObjectKind.TABLE,
                        sample.businessKey(),
                        sample.dedupKey(),
                        "snapshot_ts",
                        Partition.DAY,
                        true,
                        sample.capabilities(),
                        List.of("missing"));
        assertThrows(
                IllegalArgumentException.class, () -> new DatasetRegistry(List.of(() -> missing)));
        var a =
                copy(
                        "a",
                        ObjectKind.TABLE,
                        sample.businessKey(),
                        sample.dedupKey(),
                        "snapshot_ts",
                        Partition.DAY,
                        true,
                        sample.capabilities(),
                        List.of("b"));
        var b =
                copy(
                        "b",
                        ObjectKind.TABLE,
                        sample.businessKey(),
                        sample.dedupKey(),
                        "snapshot_ts",
                        Partition.DAY,
                        true,
                        sample.capabilities(),
                        List.of("a"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DatasetRegistry(List.of(() -> a, () -> b)));
        assertSame(adapter, new DatasetRegistry(List.of(adapter)).require(sample.datasetId()));
    }

    @Test
    void unknownKeysAndIdentityCollapsingDedupAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        copy(
                                "bad",
                                ObjectKind.TABLE,
                                List.of("unknown"),
                                sample.dedupKey(),
                                "snapshot_ts",
                                Partition.DAY,
                                true,
                                sample.capabilities(),
                                List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        copy(
                                "bad",
                                ObjectKind.TABLE,
                                sample.businessKey(),
                                List.of("snapshot_ts"),
                                "snapshot_ts",
                                Partition.DAY,
                                true,
                                sample.capabilities(),
                                List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        copy(
                                "bad",
                                ObjectKind.TABLE,
                                sample.businessKey(),
                                List.of("ts_code"),
                                "snapshot_ts",
                                Partition.DAY,
                                true,
                                sample.capabilities(),
                                List.of()));
    }

    @Test
    void timeContractIsMandatoryAndViewsCannotBeWritten() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new Column("t", "t", "t", StorageType.TIMESTAMP, false, "event time", null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        copy(
                                "bad",
                                ObjectKind.TABLE,
                                sample.businessKey(),
                                sample.dedupKey(),
                                "name",
                                Partition.DAY,
                                true,
                                sample.capabilities(),
                                List.of()));
        for (var kind : List.of(ObjectKind.VIEW, ObjectKind.MATERIALIZED_VIEW)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            copy(
                                    "bad",
                                    kind,
                                    sample.businessKey(),
                                    List.of(),
                                    null,
                                    Partition.NONE,
                                    false,
                                    Set.of(Capability.WRITE),
                                    List.of()));
        }
        var view =
                copy(
                        "good_view",
                        ObjectKind.VIEW,
                        sample.businessKey(),
                        List.of(),
                        null,
                        Partition.NONE,
                        false,
                        Set.of(Capability.READ),
                        List.of());
        assertThrows(
                IllegalArgumentException.class, () -> view.requireCapability(Capability.WRITE));
        assertEquals(sample.storageColumns(), view.storageColumns());
    }
}
