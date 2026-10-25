package com.magmaguy.freeminecraftmodels.pets.behavior;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/** One line of a pet file's behaviors list: a module by its file name, and the settings this creature gives it. */
public record PetBehaviorEntry(String module, Map<String, Object> settings) {
    public PetBehaviorEntry {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(settings, "settings");
        if (module.isBlank()) throw new IllegalArgumentException("A behavior module needs a name.");
        module = module.trim().toLowerCase(Locale.ROOT);
        settings = Collections.unmodifiableMap(new LinkedHashMap<>(settings));
    }

    /**
     * Reads a behaviors list: each item is a module name, or a one-key map from a module name to its settings.
     * Malformed items and repeats are reported through {@code warn}, naming {@code where}, and skipped. Returns null
     * when {@code raw} is not a list, after reporting it, so the caller keeps what it would run without the list.
     */
    public static List<PetBehaviorEntry> read(Object raw, String where, Consumer<String> warn) {
        if (!(raw instanceof List<?> items)) {
            warn.accept(where + ": behaviors must be a list, one module per line starting with \"- \"; this list is ignored.");
            return null;
        }
        List<PetBehaviorEntry> entries = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object item : items) {
            PetBehaviorEntry entry = parse(item, where, warn);
            if (entry == null) continue;
            if (!seen.add(entry.module())) {
                warn.accept(where + ": behaviors lists " + entry.module() + " twice; keeping the first.");
                continue;
            }
            entries.add(entry);
        }
        return List.copyOf(entries);
    }

    /** One item as an entry, or null after a warning that says what is wrong with it. */
    private static PetBehaviorEntry parse(Object item, String where, Consumer<String> warn) {
        if (item instanceof String name && !name.isBlank()) return new PetBehaviorEntry(name, Map.of());
        if (item instanceof Map<?, ?> map && map.size() > 1) {
            // Usually "- hover:" with its settings indented no further than the name, which YAML reads as one map.
            List<String> keys = map.keySet().stream().map(String::valueOf).toList();
            Map<Object, Object> rest = new LinkedHashMap<>(map);
            rest.remove(map.keySet().iterator().next());
            warn.accept(where + ": " + String.join(", ", keys.subList(0, keys.size() - 1)) + " and " + keys.getLast()
                    + " ended up in one behaviors entry; indent " + keys.getFirst()
                    + "'s settings two spaces past its name, or write \"- " + keys.getFirst() + ": "
                    + PetBehaviorYaml.plain(rest) + "\".");
            return null;
        }
        if (item instanceof Map<?, ?> map && map.size() == 1
                && map.keySet().iterator().next() instanceof String name && !name.isBlank()) {
            Object value = map.get(name);
            if (value == null) return new PetBehaviorEntry(name, Map.of());
            if (!(value instanceof Map<?, ?> values)) {
                warn.accept(where + ": skipping " + PetBehaviorYaml.describe(item) + " in behaviors; write " + name
                        + "'s settings as \"- " + name + ": { setting: value }\".");
                return null;
            }
            Map<String, Object> settings = new LinkedHashMap<>();
            for (Map.Entry<?, ?> setting : values.entrySet()) settings.put(String.valueOf(setting.getKey()), setting.getValue());
            return new PetBehaviorEntry(name, settings);
        }
        warn.accept(where + ": skipping " + PetBehaviorYaml.describe(item)
                + " in behaviors; each entry is a module name or \"name: { settings }\".");
        return null;
    }

    /**
     * A variant's entries over its species' list: a module both lists name keeps its place and takes the variant's
     * settings on top of the species', except that a setting the variant leaves without a value keeps the species'
     * value when it has one; the variant's other modules follow the species' own.
     */
    public static List<PetBehaviorEntry> overlay(List<PetBehaviorEntry> species, List<PetBehaviorEntry> variant) {
        if (variant.isEmpty()) return List.copyOf(species);
        Map<String, PetBehaviorEntry> merged = new LinkedHashMap<>();
        for (PetBehaviorEntry entry : species) merged.put(entry.module(), entry);
        for (PetBehaviorEntry entry : variant) {
            PetBehaviorEntry base = merged.get(entry.module());
            if (base == null) {
                merged.put(entry.module(), entry);
                continue;
            }
            Map<String, Object> settings = new LinkedHashMap<>(base.settings());
            for (Map.Entry<String, Object> setting : entry.settings().entrySet()) {
                // A valueless setting keeps the species' value; one the species lacks stays for the merge to report.
                if (setting.getValue() != null) settings.put(setting.getKey(), setting.getValue());
                else settings.putIfAbsent(setting.getKey(), null);
            }
            merged.put(entry.module(), new PetBehaviorEntry(entry.module(), settings));
        }
        return List.copyOf(merged.values());
    }
}
