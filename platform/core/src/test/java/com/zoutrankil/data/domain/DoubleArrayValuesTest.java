package com.zoutrankil.data.domain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

class DoubleArrayValuesTest {
    @Test
    void normalizedValuesAreOneDimensionalFiniteBoundedAndDetachedFromInput() {
        double[] input = {1.25, 2.5};
        var values = DoubleArrayValues.normalize(input);
        input[0] = 99;
        assertEquals(List.of(1.25, 2.5), values);
        assertThrows(UnsupportedOperationException.class, () -> values.add(3d));
        assertArrayEquals(new double[] {1.25, 2.5}, DoubleArrayValues.primitive(values));
        assertThrows(
                IllegalArgumentException.class,
                () -> DoubleArrayValues.normalize(new Double[] {1d, null}));
        assertThrows(
                IllegalArgumentException.class,
                () -> DoubleArrayValues.normalize(new double[] {Double.NaN}));
        assertThrows(
                IllegalArgumentException.class,
                () -> DoubleArrayValues.normalize(List.of(Double.POSITIVE_INFINITY)));
        assertThrows(
                IllegalArgumentException.class,
                () -> DoubleArrayValues.normalize(new Double[][] {{1d}}));
        assertThrows(
                IllegalArgumentException.class,
                () -> DoubleArrayValues.normalize(List.of(List.of(1d))));
        assertThrows(
                IllegalArgumentException.class,
                () -> DoubleArrayValues.normalize(new double[DoubleArrayValues.MAX_ELEMENTS + 1]));
    }

    @Test
    void storageTypeKeepsProtocolNameAndUsesTheNativeSqlArrayType() {
        assertEquals("DOUBLE_ARRAY", DatasetDefinition.StorageType.DOUBLE_ARRAY.name());
        assertEquals("DOUBLE[]", DatasetDefinition.StorageType.DOUBLE_ARRAY.sqlType());
        assertEquals("DOUBLE", DatasetDefinition.StorageType.DOUBLE.sqlType());
        var column =
                new DatasetDefinition.Column(
                        "price",
                        "price",
                        "price",
                        DatasetDefinition.StorageType.DOUBLE_ARRAY,
                        false,
                        "完整价格档位",
                        null);
        assertEquals(
                List.of(1d, 2d), DatasetStorageValues.storageValue(column, new double[] {1d, 2d}));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new DatasetDefinition(
                                "array_key",
                                1,
                                "test",
                                "test",
                                "array_key",
                                DatasetDefinition.ObjectKind.TABLE,
                                List.of(column),
                                List.of("price"),
                                List.of("price"),
                                null,
                                DatasetDefinition.Partition.NONE,
                                true,
                                Set.of(DatasetDefinition.Capability.READ),
                                List.of(),
                                "数组不作为身份"));
    }
}
