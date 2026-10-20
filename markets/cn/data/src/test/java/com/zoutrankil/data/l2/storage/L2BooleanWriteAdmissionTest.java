package com.zoutrankil.data.l2.storage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.zoutrankil.data.domain.l2.L2DailyFeatureField;
import com.zoutrankil.data.domain.l2.L2DailyFeatures;
import com.zoutrankil.data.domain.l2.L2DailyFeaturesDataset;
import com.zoutrankil.data.domain.l2.L2DailyFeaturesKey;
import com.zoutrankil.data.domain.l2.L2DatasetManifest;
import com.zoutrankil.data.domain.l2.L2DatasetManifestKey;
import com.zoutrankil.data.l2.mapper.L2DailyFeaturesMapper;
import com.zoutrankil.data.l2.port.L2DailyFeaturesWriteSession;
import com.zoutrankil.data.l2.port.L2DatasetManifestWriteSession;
import com.zoutrankil.data.repository.DatasetWritePreparation;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

import io.questdb.client.QuestDB;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;

import javax.sql.DataSource;

class L2BooleanWriteAdmissionTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void manifestUnknownQualityCannotCreateAnUnresolvedSend(boolean dailyUnknown) {
        var date = LocalDate.of(2026, 9, 17);
        var row =
                new L2DatasetManifest(
                        date,
                        "000001.SZ",
                        "CN",
                        "SZ",
                        null,
                        null,
                        null,
                        dailyUnknown ? null : false,
                        dailyUnknown ? false : null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        1L,
                        date);
        var data = mock(DataSource.class);
        var qdb = mock(QuestDB.class);
        var writer =
                new L2DatasetManifestWritePort(
                        "java_d085_l2_dataset_manifest_boolean", new JdbcTemplate(data), qdb);
        assertThrows(IllegalArgumentException.class, () -> writer.send(List.of(row)));
        verifyNoInteractions(data, qdb);
        @SuppressWarnings("unchecked")
        VerifiedBatchExecutor.Port<L2DatasetManifest, L2DatasetManifestKey> port =
                mock(VerifiedBatchExecutor.Port.class);
        var executor =
                new VerifiedBatchExecutor<>(
                        new VerifiedBatchExecutor.Policy(
                                10, 1024 * 1024, 1, Duration.ofSeconds(1), Duration.ofMillis(1)),
                        L2DatasetManifestWriteSession.CODEC,
                        port);
        var result = executor.execute(List.of(row).iterator());
        assertEquals(0, result.submittedRows());
        assertEquals(VerifiedBatchExecutor.Status.PARTIAL, result.status());
        verifyNoInteractions(port);
        assertDoesNotThrow(() -> L2DatasetManifestWriteSession.CODEC.canonicalBytes(row));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void eachUnknownBooleanIsRejectedBeforeAnySenderOrDatabaseIoWithoutRelabelingSource(
            int version) {
        var layout = CnTradeDateLayout.fromVersion(version);
        for (var field :
                List.of(
                        L2DailyFeatureField.HAS_DEAL,
                        L2DailyFeatureField.HAS_ORDER,
                        L2DailyFeatureField.HAS_SNAPSHOT)) {
            var original = L2DailyFeaturesLayoutTest.row(false);
            var values = new EnumMap<L2DailyFeatureField, Object>(original.features());
            values.put(field, null);
            var row = new L2DailyFeatures(original.tradeDate(), original.symbol(), values);
            var data = mock(DataSource.class);
            var questdb = mock(QuestDB.class);
            var writer =
                    new L2DailyFeaturesWritePort(
                            "java_d086_l2_daily_features_boolean",
                            new JdbcTemplate(data),
                            questdb,
                            layout);
            assertTrue(
                    assertThrows(IllegalArgumentException.class, () -> writer.send(List.of(row)))
                            .getMessage()
                            .contains(field.fieldName()));
            verifyNoInteractions(data, questdb);
            assertNull(row.features().get(field));
            // 来源与旧 canonical 编码仍可表达未知；只是禁止写进不能保真的 BOOLEAN 列。
            var bytes = L2DailyFeaturesWriteSession.codec(layout).canonicalBytes(row);
            assertTrue(
                    new String(bytes, StandardCharsets.UTF_8)
                            .contains("\"" + field.fieldName() + "\":null"));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void verifiedExecutorRejectsUnknownBooleanBeforePreflightOrSubmissionIntent(int version) {
        var codec = L2DailyFeaturesWriteSession.codec(CnTradeDateLayout.fromVersion(version));
        @SuppressWarnings("unchecked")
        VerifiedBatchExecutor.Port<L2DailyFeatures, L2DailyFeaturesKey> port =
                mock(VerifiedBatchExecutor.Port.class);
        var executor =
                new VerifiedBatchExecutor<>(
                        new VerifiedBatchExecutor.Policy(
                                10, 1024 * 1024, 1, Duration.ofSeconds(1), Duration.ofMillis(1)),
                        codec,
                        port);
        var result = executor.execute(List.of(L2DailyFeaturesLayoutTest.row(true)).iterator());
        assertEquals(VerifiedBatchExecutor.Status.PARTIAL, result.status());
        assertEquals(0, result.submittedRows());
        assertTrue(result.receipts().isEmpty());
        verifyNoInteractions(port);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void genericPreparationRejectsUnrepresentableBooleanButPreservesOtherNullsAndKnownFalse(
            int version) {
        var layout = CnTradeDateLayout.fromVersion(version);
        var definition =
                L2DailyFeaturesDataset.definition("java_d086_l2_daily_features_boolean", layout);
        var mapper = new L2DailyFeaturesMapper();
        var limits = new DatasetWritePreparation.Limits(10, 1024 * 1024);
        var unknown = L2DailyFeaturesLayoutTest.row(true);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        DatasetWritePreparation.prepare(
                                definition,
                                List.of(unknown),
                                row -> mapper.values(row, layout),
                                limits));
        var values = new EnumMap<L2DailyFeatureField, Object>(unknown.features());
        for (var f :
                List.of(
                        L2DailyFeatureField.HAS_DEAL,
                        L2DailyFeatureField.HAS_ORDER,
                        L2DailyFeatureField.HAS_SNAPSHOT)) values.put(f, false);
        var known = new L2DailyFeatures(unknown.tradeDate(), unknown.symbol(), values);
        var batch =
                DatasetWritePreparation.prepare(
                        definition, List.of(known), row -> mapper.values(row, layout), limits);
        assertEquals(false, batch.rows().getFirst().get("has_deal", Boolean.class));
        assertNull(batch.rows().getFirst().get("gmm_retail_ratio", Double.class));
        assertDoesNotThrow(() -> L2DailyFeaturesWriteSession.codec(layout).validateForWrite(known));
    }
}
