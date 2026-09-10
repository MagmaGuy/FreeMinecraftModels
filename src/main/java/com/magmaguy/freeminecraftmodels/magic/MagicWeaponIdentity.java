package com.magmaguy.freeminecraftmodels.magic;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.api.magic.MagicWeaponKind;
import com.magmaguy.freeminecraftmodels.config.DisplayModelRegistry;
import com.magmaguy.freeminecraftmodels.scripting.ItemScriptManager;
import com.magmaguy.magmacore.util.VersionChecker;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.Optional;

/** Canonical item identity boundary for built-in and externally-created magic weapons. */
final class MagicWeaponIdentity {

    private MagicWeaponIdentity() {
    }

    static Optional<MagicWeaponDefinition> resolve(
            ItemStack itemStack,
            MagicWeaponCatalog catalog) {
        if (itemStack == null || itemStack.getType().isAir() || !itemStack.hasItemMeta())
            return Optional.empty();
        PersistentDataContainer data = itemStack.getItemMeta().getPersistentDataContainer();

        String fmmItem = data.get(ItemScriptManager.ITEM_ID_KEY, PersistentDataType.STRING);
        return catalog.find(fmmItem);
    }

    static boolean apply(
            ItemStack itemStack,
            MagicWeaponDefinition definition) {
        if (itemStack == null || itemStack.getType().isAir()) return false;
        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null) return false;
        meta.getPersistentDataContainer().set(
                ItemScriptManager.ITEM_ID_KEY, PersistentDataType.STRING, definition.itemId());
        if (!VersionChecker.serverVersionOlderThan(21, 4)
                && DisplayModelRegistry.hasDisplayModel(definition.itemId())) {
            NamespacedKey model = NamespacedKey.fromString(
                    "freeminecraftmodels:display/" + definition.itemId());
            if (model != null) meta.setItemModel(model);
        }
        itemStack.setItemMeta(meta);
        return true;
    }


    static MagicWeaponKind kind(ItemStack itemStack, MagicWeaponCatalog catalog) {
        return resolve(itemStack, catalog).map(MagicWeaponDefinition::kind).orElse(null);
    }

}
