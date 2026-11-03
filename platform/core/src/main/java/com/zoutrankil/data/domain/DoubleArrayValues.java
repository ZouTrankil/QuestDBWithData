package com.zoutrankil.data.domain;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;

/** 一维有限 DOUBLE 数组的不可变值；拒绝嵌套、空元素和隐式 NaN/无穷值。 */
public final class DoubleArrayValues {
    public static final int MAX_ELEMENTS = 32768;

    private DoubleArrayValues() {}

    public static List<Double> normalize(Object raw) {
        if (raw == null) throw new IllegalArgumentException("数组值不能为空");
        boolean list = raw instanceof List<?>;
        if (!list && !(raw instanceof double[]) && !(raw instanceof Double[]))
            throw new IllegalArgumentException("需要一维 DOUBLE 数组");
        int length = list ? ((List<?>) raw).size() : Array.getLength(raw);
        if (length > MAX_ELEMENTS) throw new IllegalArgumentException("数组超过元素预算");
        var values = new ArrayList<Double>(length);
        for (int i = 0; i < length; i++) {
            Object value = list ? ((List<?>) raw).get(i) : Array.get(raw, i);
            if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue()))
                throw new IllegalArgumentException("数组元素必须为有限数值");
            values.add(number.doubleValue());
        }
        return List.copyOf(values);
    }

    public static double[] primitive(Object value) {
        return normalize(value).stream().mapToDouble(Double::doubleValue).toArray();
    }
}
