package com.magmaguy.freeminecraftmodels.api;

import com.magmaguy.freeminecraftmodels.config.DisplayModelRegistry;
import com.magmaguy.magmacore.util.VersionChecker;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** Public, presentation-only access to FMM item models. */
public final class ModelItemAPI {
    private ModelItemAPI() {
    }

    /**
     * Applies a registered display model without adding placement or scripted-item identity.
     *
     * <p>This is the safe integration point for another plugin that owns all interaction and
     * combat behavior but wants FMM to remain the authority for resource-pack model keys.</p>
     *
     * @return true only when the model exists and was applied
     */
    public static boolean applyDisplayModel(ItemStack itemStack, String modelId) {
        if (itemStack == null || modelId == null || modelId.isBlank()
                || VersionChecker.serverVersionOlderThan(21, 4)
                || !DisplayModelRegistry.hasDisplayModel(modelId)) return false;
        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null) return false;
        NamespacedKey modelKey = NamespacedKey.fromString(
                "freeminecraftmodels:display/" + modelId);
        if (modelKey == null) return false;
        meta.setItemModel(modelKey);
        itemStack.setItemMeta(meta);
        return true;
    }
}
