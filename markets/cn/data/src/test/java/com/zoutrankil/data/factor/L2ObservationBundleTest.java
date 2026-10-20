package com.zoutrankil.data.factor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zoutrankil.data.factor.application.FactorDefinitionCatalog;
import com.zoutrankil.data.factor.application.FactorObservationEvidence;
import com.zoutrankil.data.factor.application.L2ObservationBundle;
import com.zoutrankil.data.factor.application.L2SourceScope;
import com.zoutrankil.data.factor.engine.FactorPreprocessing;
import com.zoutrankil.data.factor.engine.MultiPoolFactorPipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

class L2ObservationBundleTest {
    @TempDir Path directory;

    @Test
    void splitsAtRowBudgetAndWritesVerifiableImmutableParts() throws Exception {
        var date = LocalDate.parse("2026-09-30");
        var catalog = FactorDefinitionCatalog.load();
        var symbols = new ArrayList<String>();
        for (int i = 0; i < 50_001; i++) symbols.add(String.format("%06d.SH", 600000 + i));
        double[] raw = new double[symbols.size()];
        Arrays.fill(raw, 7);
        var values =
                new FactorPreprocessing.Result(
                        raw,
                        raw,
                        raw,
                        raw,
                        raw,
                        new String[raw.length],
                        raw.length,
                        0,
                        0,
                        0,
                        "test");
        var ids = List.of("l2_deal_records_raw", "l2_deal_records_delta3");
        var sections =
                ids.stream()
                        .map(
                                id ->
                                        new MultiPoolFactorPipeline.Output(
                                                date,
                                                "all_market",
                                                catalog.require(id),
                                                symbols,
                                                values))
                        .toList();
        var receipt = new L2SourceScope.Receipt(date, "unrecorded", "", List.of());
        var decisions = List.of(L2SourceScope.assess(receipt, List.of()));
        var bundle =
                L2ObservationBundle.writeNew(
                        directory.resolve("bundle"),
                        sections,
                        decisions,
                        ids,
                        catalog,
                        date.plusDays(1).atStartOfDay(),
                        "a".repeat(64));
        assertEquals(100_002, bundle.rows());
        assertEquals(2, bundle.parts().size());
        assertFalse(bundle.sourceComplete());
        for (var part : bundle.parts()) {
            var actual =
                    FactorObservationEvidence.read(
                            directory.resolve("bundle").resolve(part.file()));
            assertEquals(part.sha256(), actual.fileSha256());
            assertEquals(50_001, actual.rows().size());
            assertEquals(part.inputFingerprint(), actual.inputFingerprint());
        }
        assertTrue(Files.exists(directory.resolve("bundle/manifest.json")));
        assertEquals(bundle, L2ObservationBundle.read(directory.resolve("bundle")).manifest());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        L2ObservationBundle.writeNew(
                                directory.resolve("incomplete"),
                                sections.subList(0, 1),
                                decisions,
                                ids,
                                catalog,
                                date.atStartOfDay(),
                                "a".repeat(64)));
        assertFalse(Files.exists(directory.resolve("incomplete")));
        Files.writeString(
                directory.resolve("bundle").resolve(bundle.parts().getFirst().file()), "{}");
        assertThrows(
                IllegalArgumentException.class,
                () -> L2ObservationBundle.read(directory.resolve("bundle")));
    }
}
