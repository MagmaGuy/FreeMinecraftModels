package com.magmaguy.freeminecraftmodels.api;

import com.magmaguy.freeminecraftmodels.config.DisplayModelRegistry;
import com.magmaguy.freeminecraftmodels.config.props.PropScriptConfigFields;
import com.magmaguy.freeminecraftmodels.scripting.ItemScriptManager;
import com.magmaguy.magmacore.util.VersionChecker;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Public API for external plugins to integrate with FMM's scripted item system.
 * <p>
 * Allows other plugins to stamp their own ItemStacks with FMM scripted item data
 * (PDC tag + item model) so that FMM's Lua script hooks fire for those items,
 * without FMM overriding the item's name, lore, or enchantments.
 */
public final class ScriptedItemAPI {

    private ScriptedItemAPI() {}

    /**
     * Checks whether a scripted item definition exists for the given item ID.
     *
     * @param itemId the item ID (FMM config filename without .yml)
     * @return true if the item ID is registered in FMM's item definitions
     */
    public static boolean isValidItemId(String itemId) {
        return itemId != null && ItemScriptManager.getItemDefinitions().containsKey(itemId);
    }

    /**
     * Applies FMM scripted item data to an existing ItemStack.
     * <p>
     * This sets:
     * <ul>
     *   <li>The {@code fmm_item_id} PDC tag so FMM's script system recognizes the item</li>
     *   <li>The item model (1.21.4+) from FMM's display model registry</li>
     * </ul>
     * Does NOT modify name, lore, enchantments, or any other item properties.
     *
     * @param itemStack the ItemStack to modify (must not be null)
     * @param itemId    the FMM item ID (config filename without .yml)
     * @return true if the data was applied successfully, false if the item ID is invalid
     *         or the ItemStack has no meta
     */
    public static boolean applyScriptedItemData(ItemStack itemStack, String itemId) {
        if (itemStack == null || itemId == null) return false;

        if (!isValidItemId(itemId)) return false;

        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null) return false;

        // Stamp the fmm_item_id PDC tag so ItemScriptListener picks it up
        meta.getPersistentDataContainer().set(
                ItemScriptManager.ITEM_ID_KEY,
                PersistentDataType.STRING,
                itemId
        );

        // Set the item model if available (1.21.4+)
        if (!VersionChecker.serverVersionOlderThan(21, 4)
                && DisplayModelRegistry.hasDisplayModel(itemId)) {
            meta.setItemModel(NamespacedKey.fromString("freeminecraftmodels:display/" + itemId));
        }

        itemStack.setItemMeta(meta);
        return true;
    }

    /**
     * Gets the FMM scripted item config for the given item ID.
     *
     * @param itemId the item ID (FMM config filename without .yml)
     * @return the config fields, or null if not found
     */
    public static PropScriptConfigFields getItemConfig(String itemId) {
        if (itemId == null) return null;
        return ItemScriptManager.getItemDefinitions().get(itemId);
    }
}
