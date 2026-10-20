package com.zoutrankil.data.strategy.score;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/** Immutable row values sharing column names instead of allocating a map node per cell. */
public final class FrozenScalarValues extends AbstractMap<String, Object> {
    public static final class Layout {
        private final List<String> columns;
        private final Map<String, Integer> indices;
        private final List<String> sorted;
        private final Map<String, Layout> appended = new ConcurrentHashMap<>();

        private Layout(List<String> columns) {
            this.columns = columns;
            var indices = new HashMap<String, Integer>();
            for (int i = 0; i < columns.size(); i++)
                if (indices.put(Objects.requireNonNull(columns.get(i)), i) != null)
                    throw new IllegalArgumentException(
                            "Duplicate scalar column: " + columns.get(i));
            this.indices = Map.copyOf(indices);
            this.sorted = columns.stream().sorted().toList();
        }

        int size() {
            return columns.size();
        }

        String column(int index) {
            return columns.get(index);
        }

        int index(Object key) {
            var index = indices.get(key);
            return index == null ? -1 : index;
        }

        Layout adding(String column) {
            return appended.computeIfAbsent(
                    column,
                    key -> {
                        var names = new ArrayList<>(columns);
                        names.add(key);
                        return layout(names);
                    });
        }

        Layout removing(int index) {
            var names = new ArrayList<>(columns);
            names.remove(index);
            return layout(names);
        }
    }

    private static final Map<List<String>, Layout> LAYOUTS =
            new LinkedHashMap<>(64, .75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<List<String>, Layout> entry) {
                    return size() > 256;
                }
            };

    public static Layout layout(List<String> columns) {
        var names = List.copyOf(columns);
        synchronized (LAYOUTS) {
            return LAYOUTS.computeIfAbsent(names, Layout::new);
        }
    }

    private final Layout layout;
    private final Object[] values;

    private FrozenScalarValues(Layout layout, Object[] values) {
        this(layout, values, false);
    }

    private FrozenScalarValues(Layout layout, Object[] values, boolean spareCapacity) {
        this.layout = Objects.requireNonNull(layout);
        if (values.length != layout.columns.size()
                && (!spareCapacity || values.length < layout.columns.size()))
            throw new IllegalArgumentException("Scalar row width differs from its columns");
        this.values = Arrays.copyOf(values, layout.columns.size());
    }

    public static FrozenScalarValues row(Layout layout, Object[] values) {
        return new FrozenScalarValues(layout, values);
    }

    static Layout layoutOf(Map<String, ?> source) {
        return source instanceof FrozenScalarValues frozen
                ? frozen.layout
                : layout(new ArrayList<>(source.keySet()));
    }

    static void copyCells(Map<String, ?> source, Layout layout, Object[] target) {
        if (source instanceof FrozenScalarValues frozen && frozen.layout == layout)
            System.arraycopy(frozen.values, 0, target, 0, layout.size());
        else for (int i = 0; i < layout.size(); i++) target[i] = source.get(layout.column(i));
    }

    static FrozenScalarValues snapshot(Layout layout, Object[] buffer) {
        return new FrozenScalarValues(layout, buffer, true);
    }

    public static Map<String, Object> copyOf(Map<String, ?> source) {
        Objects.requireNonNull(source);
        if (source instanceof FrozenScalarValues frozen) return frozen;
        if (source instanceof Projection projected) return projected;
        if (source instanceof MutableScalarValues mutable) return mutable.immutableCopy();
        var layout = layout(new ArrayList<>(source.keySet()));
        var values = new Object[layout.columns.size()];
        for (int i = 0; i < values.length; i++) values[i] = source.get(layout.columns.get(i));
        return row(layout, values);
    }

    public List<String> sortedColumns() {
        return layout.sorted;
    }

    /** LinkedHashMap merge semantics without allocating a map node for each published cell. */
    public static Map<String, Object> merging(Map<String, ?> source, Map<String, ?> additions) {
        var base = copyOf(source);
        var original = layoutOf(base);
        var combined = original;
        for (String name : additions.keySet())
            if (combined.index(name) < 0) combined = combined.adding(name);
        var cells = new Object[combined.size()];
        copyCells(base, original, cells);
        for (var entry : additions.entrySet())
            cells[combined.index(entry.getKey())] = entry.getValue();
        return row(combined, cells);
    }

    /** Identity columns are already carried by ScoreRow; share the immutable cells. */
    public static Map<String, Object> excluding(Map<String, ?> source, Set<String> excluded) {
        return new Projection(copyOf(source), Set.copyOf(excluded));
    }

    private static final class Projection extends AbstractMap<String, Object> {
        private final Map<String, Object> source;
        private final Set<String> excluded;
        private final int size;

        Projection(Map<String, Object> source, Set<String> excluded) {
            this.source = source;
            this.excluded = excluded;
            this.size = source.size() - (int) excluded.stream().filter(source::containsKey).count();
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public Object get(Object key) {
            return key == null || excluded.contains(key) ? null : source.get(key);
        }

        @Override
        public boolean containsKey(Object key) {
            return key != null && !excluded.contains(key) && source.containsKey(key);
        }

        @Override
        public Set<Map.Entry<String, Object>> entrySet() {
            return new AbstractSet<>() {
                @Override
                public int size() {
                    return size;
                }

                @Override
                public Iterator<Map.Entry<String, Object>> iterator() {
                    return new Iterator<>() {
                        private final Iterator<Map.Entry<String, Object>> input =
                                source.entrySet().iterator();
                        private Map.Entry<String, Object> next;

                        @Override
                        public boolean hasNext() {
                            while (next == null && input.hasNext()) {
                                var candidate = input.next();
                                if (!excluded.contains(candidate.getKey())) next = candidate;
                            }
                            return next != null;
                        }

                        @Override
                        public Map.Entry<String, Object> next() {
                            if (!hasNext()) throw new NoSuchElementException();
                            var entry = next;
                            next = null;
                            return entry;
                        }
                    };
                }
            };
        }

        @Override
        public Object put(String key, Object value) {
            throw new UnsupportedOperationException("Frozen scalar projection");
        }

        @Override
        public Object remove(Object key) {
            throw new UnsupportedOperationException("Frozen scalar projection");
        }

        @Override
        public void clear() {
            throw new UnsupportedOperationException("Frozen scalar projection");
        }

        @Override
        public void putAll(Map<? extends String, ?> values) {
            throw new UnsupportedOperationException("Frozen scalar projection");
        }
    }

    @Override
    public int size() {
        return values.length;
    }

    @Override
    public boolean containsKey(Object key) {
        return layout.indices.containsKey(key);
    }

    @Override
    public Object get(Object key) {
        var index = layout.indices.get(key);
        return index == null ? null : values[index];
    }

    @Override
    public void forEach(BiConsumer<? super String, ? super Object> action) {
        Objects.requireNonNull(action);
        for (int i = 0; i < values.length; i++) action.accept(layout.columns.get(i), values[i]);
    }

    @Override
    public Set<Map.Entry<String, Object>> entrySet() {
        return new AbstractSet<>() {
            @Override
            public int size() {
                return values.length;
            }

            @Override
            public Iterator<Map.Entry<String, Object>> iterator() {
                return new Iterator<>() {
                    private int next;

                    @Override
                    public boolean hasNext() {
                        return next < values.length;
                    }

                    @Override
                    public Map.Entry<String, Object> next() {
                        if (!hasNext()) throw new NoSuchElementException();
                        int index = next++;
                        return new SimpleImmutableEntry<>(layout.columns.get(index), values[index]);
                    }
                };
            }
        };
    }

    @Override
    public Object put(String key, Object value) {
        throw new UnsupportedOperationException("Frozen scalar row");
    }

    @Override
    public Object remove(Object key) {
        throw new UnsupportedOperationException("Frozen scalar row");
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException("Frozen scalar row");
    }

    @Override
    public void putAll(Map<? extends String, ?> values) {
        throw new UnsupportedOperationException("Frozen scalar row");
    }
}
