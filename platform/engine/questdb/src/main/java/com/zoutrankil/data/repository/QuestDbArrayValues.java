package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.DoubleArrayValues;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/** JDBC 数组保持维度和对应顺序，读取后释放驱动资源，不把数组转成 JSON 字符串。 */
public final class QuestDbArrayValues {
    private QuestDbArrayValues() {}

    public static List<Double> read(ResultSet rs, String column) throws SQLException {
        var array = rs.getArray(column);
        if (array == null) return null;
        try {
            return DoubleArrayValues.normalize(array.getArray());
        } catch (IllegalArgumentException malformed) {
            throw new SQLException("物理数组不是完整的一维有限 DOUBLE 值", malformed);
        } finally {
            array.free();
        }
    }
}
