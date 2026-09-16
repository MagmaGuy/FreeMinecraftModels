package com.magmaguy.freeminecraftmodels.api;

import com.magmaguy.freeminecraftmodels.api.magic.MagicWeaponKind;
import com.magmaguy.freeminecraftmodels.api.magic.MagicWeaponService;
import com.magmaguy.freeminecraftmodels.config.DisplayModelRegistry;
import com.magmaguy.freeminecraftmodels.magic.MagicWeaponCatalog;
import com.magmaguy.freeminecraftmodels.scripting.ItemScriptManager;
import com.magmaguy.magmacore.util.VersionChecker;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

/** Captured on the server thread; its immutable item data can be applied by a construction worker. */
public final class ItemConstructionSnapshot {
    public record ItemData(String authoredId, NamespacedKey model, MagicWeaponKind kind) {
        public void apply(ItemStack item) {
            var meta = Objects.requireNonNull(item.getItemMeta(), "item metadata");
            if (authoredId != null)
                meta.getPersistentDataContainer().set(ItemScriptManager.ITEM_ID_KEY, PersistentDataType.STRING, authoredId);
            if (model != null) meta.setItemModel(model);
            if (!item.setItemMeta(meta)) throw new IllegalArgumentException("Item rejected FMM metadata");
        }
    }

    private final Map<String, ?> definitions;
    private final MagicWeaponCatalog weapons;
    private final Set<String> models;
    private final MagicWeaponService service;
    private final Map<String, ItemData> items;
    private final boolean operational;

    private ItemConstructionSnapshot() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Capture FMM construction data on the server thread");
        definitions = ItemScriptManager.getItemDefinitions();
        weapons = ItemScriptManager.getWeaponCatalog();
        models = DisplayModelRegistry.getRegisteredModels();
        service = Bukkit.getServicesManager().load(MagicWeaponService.class);
        operational = service != null && service.isOperational();
        Map<String, ItemData> prepared = new HashMap<>();
        Set<String> ids = new HashSet<>(models);
        ids.addAll(definitions.keySet());
        for (String id : ids) {
            var kind = operational ? weapons.find(id).map(w -> w.kind()).orElse(null) : null;
            NamespacedKey model = !VersionChecker.serverVersionOlderThan(21, 4) && models.contains(id)
                    ? NamespacedKey.fromString("freeminecraftmodels:display/" + id) : null;
            prepared.put(id, new ItemData(definitions.containsKey(id) ? id : null, model, kind));
        }
        items = Map.copyOf(prepared);
    }

    public static ItemConstructionSnapshot capture() { return new ItemConstructionSnapshot(); }

    public ItemData item(String id) {
        if (id == null) return null;
        return items.get(id);
    }

    /** Magic weapon IDs use the same normalization as the live weapon catalog. */
    public ItemData weapon(String id) {
        ItemData item = id == null ? null : items.get(id.trim().toLowerCase(Locale.ROOT));
        return item != null && item.kind() != null ? item : null;
    }

    public void requireCurrent() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Publish FMM construction data on the server thread");
        if (definitions != ItemScriptManager.getItemDefinitions() || weapons != ItemScriptManager.getWeaponCatalog()
                || !models.equals(DisplayModelRegistry.getRegisteredModels())
                || service != Bukkit.getServicesManager().load(MagicWeaponService.class)
                || operational != (service != null && service.isOperational()))
            throw new ConcurrentModificationException("FMM definitions changed during item construction");
    }
}
