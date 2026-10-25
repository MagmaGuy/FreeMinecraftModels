package com.magmaguy.freeminecraftmodels.pets.behavior;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** One line of a pet file's behaviors list: a module by its file name, and the settings this creature gives it. */
public record PetBehaviorEntry(String module, Map<String, Object> settings) {
    public PetBehaviorEntry {
        module = module.trim().toLowerCase(Locale.ROOT);
        settings = Collections.unmodifiableMap(new LinkedHashMap<>(settings));
    }

    /**
     * Reads a behaviors list: each item is a module name, or a one-key map from a module name to its settings.
     * Malformed items and repeats are reported through {@code warn}, naming {@code where}, and skipped.
     */
    public static List<PetBehaviorEntry> read(Object raw, String where, Consumer<String> warn) {
        if (!(raw instanceof List<?> items)) {
            warn.accept(where + ": behaviors must be a list of module names; the creature runs none of them.");
            return List.of();
        }
        List<PetBehaviorEntry> entries = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object item : items) {
            PetBehaviorEntry entry = parse(item);
            if (entry == null) {
                warn.accept(where + ": a behaviors entry is neither a module name nor 'name: { settings }': " + item);
                continue;
            }
            if (!seen.add(entry.module())) {
                warn.accept(where + ": behaviors lists " + entry.module() + " twice; keeping the first.");
                continue;
            }
            entries.add(entry);
        }
        return List.copyOf(entries);
    }

    private static PetBehaviorEntry parse(Object item) {
        if (item instanceof String name && !name.isBlank()) return new PetBehaviorEntry(name, Map.of());
        if (!(item instanceof Map<?, ?> map) || map.size() != 1) return null;
        Map.Entry<?, ?> only = map.entrySet().iterator().next();
        if (!(only.getKey() instanceof String name) || name.isBlank()) return null;
        if (only.getValue() == null) return new PetBehaviorEntry(name, Map.of());
        if (!(only.getValue() instanceof Map<?, ?> values)) return null;
        Map<String, Object> settings = new LinkedHashMap<>();
        for (Map.Entry<?, ?> setting : values.entrySet()) settings.put(String.valueOf(setting.getKey()), setting.getValue());
        return new PetBehaviorEntry(name, settings);
    }

    /**
     * A variant's entries over its species' list: a module both name keeps its place and takes the variant's
     * settings on top of the species'; the variant's other modules follow the species' own.
     */
    public static List<PetBehaviorEntry> overlay(List<PetBehaviorEntry> species, List<PetBehaviorEntry> variant) {
        if (variant.isEmpty()) return species;
        Map<String, PetBehaviorEntry> merged = new LinkedHashMap<>();
        for (PetBehaviorEntry entry : species) merged.put(entry.module(), entry);
        for (PetBehaviorEntry entry : variant) {
            PetBehaviorEntry base = merged.get(entry.module());
            if (base == null) {
                merged.put(entry.module(), entry);
                continue;
            }
            Map<String, Object> settings = new LinkedHashMap<>(base.settings());
            settings.putAll(entry.settings());
            merged.put(entry.module(), new PetBehaviorEntry(entry.module(), settings));
        }
        return List.copyOf(merged.values());
    }
}
