package com.zoutrankil.data.domain;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.*;

/** Frozen pre-extraction records characterize fields, JSON and the four distinct snapshot policies. */
class MaterializationSnapshotCompatibilityTest {
    static Stream<Arguments> monthlyTypes() {
        return Stream.of(
                Arguments.of(EquityStyleMonthlyTargetSnapshot.class, LegacyEquitySnapshot.class),
                Arguments.of(MacroCoreMonthlyTargetSnapshot.class, LegacyMacroSnapshot.class));
    }
    static Stream<Arguments> dailyTypes() {
        return Stream.of(
                Arguments.of(MarketBreadthDailyV1Snapshot.class, LegacyBreadthSnapshot.class),
                Arguments.of(RetailSentimentDailyV1Snapshot.class, LegacyRetailSnapshot.class));
    }

    @ParameterizedTest @MethodSource("monthlyTypes")
    void monthlyFieldsJsonAndSettlementKeepTheirOriginalContract(Class<? extends Record> current,
                                                                 Class<? extends Record> legacy) throws Exception {
        assertComponents(current, legacy);
        var fixture = monthly();
        compareMonthly(current, legacy, fixture);
        for (int field : new int[] {4, 5, 12}) {
            var nullable = fixture.clone(); nullable[field] = null;
            compareMonthly(current, legacy, nullable);
        }
        var empty = new Object[] {"target", 0L, "dir", "schema", null, null, 0L, 0L, 0L, 0L, false, 0L, null};
        compareMonthly(current, legacy, empty);
        for (int field : new int[] {6, 7, 8, 9}) {
            var unsettled = empty.clone(); unsettled[field] = 1L;
            compareMonthly(current, legacy, unsettled);
        }
        var suspended = fixture.clone(); suspended[10] = true;
        compareMonthly(current, legacy, suspended);
    }

    @ParameterizedTest @MethodSource("monthlyTypes")
    void monthlyConstructorKeepsNullAndNonnegativeValidation(Class<? extends Record> current,
                                                              Class<? extends Record> legacy) {
        for (int field : new int[] {0, 2, 3}) {
            var invalid = monthly(); invalid[field] = null;
            assertSameFailure(current, legacy, invalid);
        }
        var blank = monthly(); blank[2] = " ";
        assertSameFailure(current, legacy, blank);
        for (int field : new int[] {1, 4, 5, 6, 7, 8, 9, 11, 12}) {
            var invalid = monthly(); invalid[field] = -1L;
            assertSameFailure(current, legacy, invalid);
        }
    }

    @ParameterizedTest @MethodSource("dailyTypes")
    void dailyFieldsJsonVersionsAndSourceEqualityKeepTheirOriginalContract(Class<? extends Record> current,
                                                                          Class<? extends Record> legacy) throws Exception {
        assertComponents(current, legacy);
        var fixture = new Object[] {1L, "source~1", 2L, 3L, 3L, true, 4L, "mv~4", 5L, 6L,
                6L, true, true, true, "definition", null, "finished", 3L, 3L, "DAY", "valid"};
        Record actual = construct(current, fixture), expected = construct(legacy, fixture);
        assertJson(expected, actual);
        assertEquals(call(expected, "sourceVersion"), call(actual, "sourceVersion"));
        assertEquals(call(expected, "stableVersion"), call(actual, "stableVersion"));
        assertEquals(sourceUnchanged(expected, null), sourceUnchanged(actual, null));
        assertEquals(sourceUnchanged(expected, expected), sourceUnchanged(actual, actual));
        for (int field = 0; field < fixture.length; field++) {
            var changed = fixture.clone(); Object value = changed[field];
            changed[field] = value instanceof Long number ? number + 1
                    : value instanceof Boolean flag ? !flag : Objects.toString(value, "") + "-changed";
            Record actualChange = construct(current, changed), expectedChange = construct(legacy, changed);
            assertJson(expectedChange, actualChange);
            assertEquals(call(expectedChange, "sourceVersion"), call(actualChange, "sourceVersion"));
            assertEquals(call(expectedChange, "stableVersion"), call(actualChange, "stableVersion"));
            assertEquals(sourceUnchanged(expected, expectedChange), sourceUnchanged(actual, actualChange),
                    "source equality changed for field " + current.getRecordComponents()[field].getName());
        }
    }

    @Test void monthlyPoliciesRemainDistinctWhenOnlyWalFrontierIsMissing() {
        assertTrue(new EquityStyleMonthlyTargetSnapshot("target", 1, "dir", "schema", 2L, null,
                2, 2, 0, 0, false, 5, 5L).settled());
        assertFalse(new MacroCoreMonthlyTargetSnapshot("target", 1, "dir", "schema", 2L, null,
                2, 2, 0, 0, false, 5, 5L).settled());
    }

    private static Object[] monthly() {
        return new Object[] {"target", 1L, "dir", "schema", 2L, 2L, 2L, 2L, 0L, 0L, false, 5L, 5L};
    }
    private static void compareMonthly(Class<? extends Record> current, Class<? extends Record> legacy,
                                       Object[] values) throws Exception {
        Record actual = construct(current, values), expected = construct(legacy, values);
        assertJson(expected, actual);
        assertEquals(call(expected, "identity"), call(actual, "identity"));
        assertEquals(call(expected, "settled"), call(actual, "settled"));
    }
    private static void assertComponents(Class<?> current, Class<?> legacy) {
        assertEquals(Arrays.stream(legacy.getRecordComponents()).map(RecordComponent::getName).toList(),
                Arrays.stream(current.getRecordComponents()).map(RecordComponent::getName).toList());
        assertEquals(Arrays.stream(legacy.getRecordComponents()).map(RecordComponent::getType).toList(),
                Arrays.stream(current.getRecordComponents()).map(RecordComponent::getType).toList());
    }
    private static void assertJson(Object expected, Object actual) throws Exception {
        assertArrayEquals(JobDefinitionJson.mapper().writeValueAsBytes(expected),
                JobDefinitionJson.mapper().writeValueAsBytes(actual));
        assertArrayEquals(JobDefinitionJson.canonicalMapper().writeValueAsBytes(expected),
                JobDefinitionJson.canonicalMapper().writeValueAsBytes(actual));
    }
    private static Record construct(Class<? extends Record> type, Object[] values) throws Exception {
        var constructor = type.getDeclaredConstructor(Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getType).toArray(Class<?>[]::new));
        constructor.setAccessible(true);
        return constructor.newInstance(values);
    }
    private static Object call(Record value, String name) throws Exception {
        var method = value.getClass().getDeclaredMethod(name); method.setAccessible(true);
        return method.invoke(value);
    }
    private static Object sourceUnchanged(Record value, Record other) throws Exception {
        var method = value.getClass().getDeclaredMethod("sourceUnchanged", value.getClass());
        method.setAccessible(true); return method.invoke(value, new Object[] {other});
    }
    private static void assertSameFailure(Class<? extends Record> current, Class<? extends Record> legacy,
                                          Object[] values) {
        Throwable expected = assertThrows(InvocationTargetException.class, () -> construct(legacy, values)).getCause();
        Throwable actual = assertThrows(InvocationTargetException.class, () -> construct(current, values)).getCause();
        assertEquals(expected.getClass(), actual.getClass()); assertEquals(expected.getMessage(), actual.getMessage());
    }

    /** Raw nullable metadata is preserved; only an independently counted empty table permits uninitialized txn. */
    private record LegacyEquitySnapshot(String targetId, long tableId, String directory, String schemaHash,
                           Long physicalTxn, Long walTxn, long sequenceTxn, long writerTxn,
                           long pendingRows, long bufferedTxns, boolean suspended, long rowCount,
                           Long metadataRowCount) {
        public LegacyEquitySnapshot {
            Objects.requireNonNull(targetId); Objects.requireNonNull(directory); Objects.requireNonNull(schemaHash);
            if (tableId < 0 || directory.isBlank() || sequenceTxn < 0 || writerTxn < 0 || pendingRows < 0
                    || bufferedTxns < 0 || rowCount < 0 || physicalTxn != null && physicalTxn < 0
                    || walTxn != null && walTxn < 0 || metadataRowCount != null && metadataRowCount < 0)
                throw new IllegalArgumentException("Complete nonnegative target metadata required");
        }
        public String identity() { return targetId; }
        public boolean settled() {
            if (suspended || pendingRows != 0 || bufferedTxns != 0 || sequenceTxn != writerTxn) return false;
            if (physicalTxn == null) return rowCount == 0 && sequenceTxn == 0 && writerTxn == 0
                    && (metadataRowCount == null || metadataRowCount == 0) && (walTxn == null || walTxn == 0);
            return walTxn == null || walTxn == writerTxn;
        }
    }

    /** Raw nullable metadata is preserved; absent physical/WAL frontiers require an independently counted empty table. */
    private record LegacyMacroSnapshot(String targetId, long tableId, String directory, String schemaHash,
                           Long physicalTxn, Long walTxn, long sequenceTxn, long writerTxn,
                           long pendingRows, long bufferedTxns, boolean suspended, long rowCount,
                           Long metadataRowCount) {
        public LegacyMacroSnapshot {
            Objects.requireNonNull(targetId); Objects.requireNonNull(directory); Objects.requireNonNull(schemaHash);
            if (tableId < 0 || directory.isBlank() || sequenceTxn < 0 || writerTxn < 0 || pendingRows < 0
                    || bufferedTxns < 0 || rowCount < 0 || physicalTxn != null && physicalTxn < 0
                    || walTxn != null && walTxn < 0 || metadataRowCount != null && metadataRowCount < 0)
                throw new IllegalArgumentException("Complete nonnegative target metadata required");
        }
        public String identity() { return targetId; }
        public boolean settled() {
            if (suspended || pendingRows != 0 || bufferedTxns != 0 || sequenceTxn != writerTxn) return false;
            if (physicalTxn == null || walTxn == null || metadataRowCount == null) return rowCount == 0 && sequenceTxn == 0 && writerTxn == 0
                    && (physicalTxn == null || physicalTxn == 0)
                    && (metadataRowCount == null || metadataRowCount == 0) && (walTxn == null || walTxn == 0);
            return walTxn == writerTxn;
        }
    }

    /** Captures both source and output versions: RANGE may change the output without changing its base checkpoint. */
    private record LegacyBreadthSnapshot(long sourceId, String sourceDirectory, long sourceTableTxn,
                           long sourceSeqTxn, long sourceWriterTxn, boolean sourceSettled,
                           long mvId, String mvDirectory, long mvTxn, long mvSeqTxn,
                           long mvWriterTxn, boolean mvSettled, boolean valid, boolean caughtUp,
                           String definitionSha, String refreshStarted, String refreshFinished,
                           long refreshBaseTxn, long reportedBaseTxn, String sourcePartition, String viewStatus) {
        public String sourceVersion() { return sourceId + ":" + sourceSeqTxn; }
        public boolean sourceUnchanged(LegacyBreadthSnapshot other) {
            return other != null && sourceId == other.sourceId
                    && Objects.equals(sourceDirectory, other.sourceDirectory)
                    && Objects.equals(sourcePartition, other.sourcePartition)
                    && sourceTableTxn == other.sourceTableTxn && sourceSeqTxn == other.sourceSeqTxn
                    && sourceWriterTxn == other.sourceWriterTxn && sourceSettled && other.sourceSettled;
        }
        public String stableVersion() {
            return sourceVersion() + ":" + sourceTableTxn + ":" + mvId + ":" + mvTxn
                    + ":" + mvSeqTxn + ":" + refreshBaseTxn + ":" + refreshStarted + ":" + refreshFinished;
        }
    }

    /** Captures both source and output versions: RANGE may change the output without changing its base checkpoint. */
    private record LegacyRetailSnapshot(long sourceId, String sourceDirectory, long sourceTableTxn,
                           long sourceSeqTxn, long sourceWriterTxn, boolean sourceSettled,
                           long mvId, String mvDirectory, long mvTxn, long mvSeqTxn,
                           long mvWriterTxn, boolean mvSettled, boolean valid, boolean caughtUp,
                           String definitionSha, String refreshStarted, String refreshFinished,
                           long refreshBaseTxn, long reportedBaseTxn, String sourcePartition, String viewStatus) {
        public String sourceVersion() { return sourceId + ":" + sourceSeqTxn; }
        public boolean sourceUnchanged(LegacyRetailSnapshot other) {
            return other != null && sourceId == other.sourceId
                    && Objects.equals(sourceDirectory, other.sourceDirectory)
                    && Objects.equals(sourcePartition, other.sourcePartition)
                    && sourceTableTxn == other.sourceTableTxn && sourceSeqTxn == other.sourceSeqTxn
                    && sourceWriterTxn == other.sourceWriterTxn && sourceSettled && other.sourceSettled;
        }
        public String stableVersion() {
            return sourceVersion() + ":" + sourceTableTxn + ":" + mvId + ":" + mvTxn
                    + ":" + mvSeqTxn + ":" + refreshBaseTxn + ":" + refreshStarted + ":" + refreshFinished;
        }
    }

}
