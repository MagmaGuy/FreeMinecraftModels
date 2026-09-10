package com.magmaguy.freeminecraftmodels.magic;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Stable item-id registry for FMM-owned magic weapons. */
public final class MagicWeaponCatalog {
    private final Map<String, MagicWeaponDefinition> definitions;

    public MagicWeaponCatalog(Collection<MagicWeaponDefinition> definitions) {
        Map<String, MagicWeaponDefinition> indexed = new LinkedHashMap<>();
        for (MagicWeaponDefinition definition : definitions) {
            String id = normalize(definition.itemId());
            if (indexed.putIfAbsent(id, definition) != null)
                throw new IllegalArgumentException("Duplicate magic weapon id: " + id);
        }
        this.definitions = Map.copyOf(indexed);
    }

    public Optional<MagicWeaponDefinition> find(String itemId) {
        return Optional.ofNullable(definitions.get(normalize(itemId)));
    }

    public MagicWeaponDefinition require(String itemId) {
        return find(itemId).orElseThrow(() -> new IllegalArgumentException("Unknown magic weapon: " + itemId));
    }

    public Collection<MagicWeaponDefinition> definitions() {
        return definitions.values();
    }

    private static String normalize(String itemId) {
        return itemId == null ? "" : itemId.trim().toLowerCase(Locale.ROOT);
    }
}
