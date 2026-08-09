package com.magmaguy.freeminecraftmodels.utils;

import java.util.AbstractMap;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Creates immutable point-in-time map snapshots while retaining the historical
 * {@link HashMap} return type used by the public FMM API. Keeping that concrete
 * return type preserves binary compatibility for already-compiled integrations.
 */
public final class ImmutableMapSnapshots {
    private ImmutableMapSnapshots() {
    }

    public static <K, V> HashMap<K, V> hashMapCopyOf(Map<? extends K, ? extends V> source) {
        return new FrozenHashMap<>(source);
    }

    private static final class FrozenHashMap<K, V> extends HashMap<K, V> {
        private final Set<Map.Entry<K, V>> frozenEntrySet;
        private final Set<K> frozenKeySet;
        private final Collection<V> frozenValues;
        private boolean frozen;

        private FrozenHashMap(Map<? extends K, ? extends V> source) {
            super(source);
            Set<Map.Entry<K, V>> immutableEntries = new LinkedHashSet<>();
            for (Map.Entry<K, V> entry : super.entrySet()) {
                immutableEntries.add(new AbstractMap.SimpleImmutableEntry<>(entry));
            }
            frozenEntrySet = Collections.unmodifiableSet(immutableEntries);
            frozenKeySet = Collections.unmodifiableSet(super.keySet());
            frozenValues = Collections.unmodifiableCollection(super.values());
            frozen = true;
        }

        private void rejectMutation() {
            if (frozen) throw new UnsupportedOperationException("This registry snapshot is immutable");
        }

        @Override
        public V put(K key, V value) {
            rejectMutation();
            return super.put(key, value);
        }

        @Override
        public void putAll(Map<? extends K, ? extends V> map) {
            rejectMutation();
            super.putAll(map);
        }

        @Override
        public V remove(Object key) {
            rejectMutation();
            return super.remove(key);
        }

        @Override
        public boolean remove(Object key, Object value) {
            rejectMutation();
            return super.remove(key, value);
        }

        @Override
        public void clear() {
            rejectMutation();
            super.clear();
        }

        @Override
        public V putIfAbsent(K key, V value) {
            rejectMutation();
            return super.putIfAbsent(key, value);
        }

        @Override
        public boolean replace(K key, V oldValue, V newValue) {
            rejectMutation();
            return super.replace(key, oldValue, newValue);
        }

        @Override
        public V replace(K key, V value) {
            rejectMutation();
            return super.replace(key, value);
        }

        @Override
        public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
            rejectMutation();
            super.replaceAll(function);
        }

        @Override
        public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
            rejectMutation();
            return super.computeIfAbsent(key, mappingFunction);
        }

        @Override
        public V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
            rejectMutation();
            return super.computeIfPresent(key, remappingFunction);
        }

        @Override
        public V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
            rejectMutation();
            return super.compute(key, remappingFunction);
        }

        @Override
        public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
            rejectMutation();
            return super.merge(key, value, remappingFunction);
        }

        @Override
        public Set<K> keySet() {
            return frozenKeySet;
        }

        @Override
        public Collection<V> values() {
            return frozenValues;
        }

        @Override
        public Set<Map.Entry<K, V>> entrySet() {
            return frozenEntrySet;
        }
    }
}
