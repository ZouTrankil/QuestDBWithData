package com.zoutrankil.data.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

class QuestDbArrayValuesTest {
    @Test
    void successfulAndRejectedArraysBothReleaseJdbcResources() throws Exception {
        var rs = mock(ResultSet.class);
        var array = mock(Array.class);
        when(rs.getArray("price")).thenReturn(array);
        when(array.getArray()).thenReturn(new Double[] {1.25, 2.5});
        assertEquals(List.of(1.25, 2.5), QuestDbArrayValues.read(rs, "price"));
        verify(array).free();
        when(array.getArray()).thenReturn(new Double[][] {{1d}, {2d}});
        assertThrows(SQLException.class, () -> QuestDbArrayValues.read(rs, "price"));
        verify(array, times(2)).free();
        when(array.getArray()).thenReturn(new Double[] {1d, Double.NaN});
        assertThrows(SQLException.class, () -> QuestDbArrayValues.read(rs, "price"));
        verify(array, times(3)).free();
        when(rs.getArray("price")).thenReturn(null);
        assertNull(QuestDbArrayValues.read(rs, "price"));
        verify(array, times(3)).free();
    }
}
