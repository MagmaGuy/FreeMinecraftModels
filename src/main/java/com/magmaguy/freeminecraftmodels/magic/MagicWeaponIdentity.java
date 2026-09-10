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
    private static final NamespacedKey ELITEMOBS_WEAPON_TYPE =
            NamespacedKey.fromString("elitemobs:weapon_type");

    private MagicWeaponIdentity() {
    }

    static Optional<MagicWeaponDefinition> resolve(
            ItemStack itemStack,
            MagicWeaponCatalog catalog) {
        if (itemStack == null || itemStack.getType().isAir() || !itemStack.hasItemMeta())
            return Optional.empty();
        PersistentDataContainer data = itemStack.getItemMeta().getPersistentDataContainer();

        String explicit = data.get(magicWeaponKey(), PersistentDataType.STRING);
        Optional<MagicWeaponDefinition> resolved = catalog.find(explicit);
        if (resolved.isPresent()) return resolved;

        String fmmItem = data.get(ItemScriptManager.ITEM_ID_KEY, PersistentDataType.STRING);
        resolved = catalog.find(fmmItem);
        if (resolved.isPresent()) return resolved;

        // Existing EliteMobs prototype items already carry this stable identity.
        // Keeping the fallback here upgrades those items without rewriting inventories.
        if (ELITEMOBS_WEAPON_TYPE == null) return Optional.empty();
        String eliteWeapon = data.get(ELITEMOBS_WEAPON_TYPE, PersistentDataType.STRING);
        if ("elitemobs:staff".equals(eliteWeapon))
            return catalog.find(BuiltInMagicWeapons.DEFAULT_STAFF_ID);
        if ("elitemobs:wand".equals(eliteWeapon))
            return catalog.find(BuiltInMagicWeapons.DEFAULT_WAND_ID);
        return Optional.empty();
    }

    static boolean apply(
            ItemStack itemStack,
            MagicWeaponDefinition definition) {
        if (itemStack == null || itemStack.getType().isAir()) return false;
        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null) return false;
        meta.getPersistentDataContainer().set(
                magicWeaponKey(), PersistentDataType.STRING, definition.itemId());
        if (!VersionChecker.serverVersionOlderThan(21, 4)
                && DisplayModelRegistry.hasDisplayModel(definition.itemId())) {
            NamespacedKey model = NamespacedKey.fromString(
                    "freeminecraftmodels:display/" + definition.itemId());
            if (model != null) meta.setItemModel(model);
        }
        itemStack.setItemMeta(meta);
        return true;
    }

    static boolean bundledContentReady() {
        return ItemScriptManager.getItemDefinitions().containsKey(BuiltInMagicWeapons.DEFAULT_STAFF_ID)
                && ItemScriptManager.getItemDefinitions().containsKey(BuiltInMagicWeapons.DEFAULT_WAND_ID)
                && DisplayModelRegistry.hasDisplayModel(BuiltInMagicWeapons.DEFAULT_STAFF_ID);
    }

    static MagicWeaponKind kind(ItemStack itemStack, MagicWeaponCatalog catalog) {
        return resolve(itemStack, catalog).map(MagicWeaponDefinition::kind).orElse(null);
    }

    private static NamespacedKey magicWeaponKey() {
        return new NamespacedKey(MetadataHandler.PLUGIN, "magic_weapon_id");
    }
}
