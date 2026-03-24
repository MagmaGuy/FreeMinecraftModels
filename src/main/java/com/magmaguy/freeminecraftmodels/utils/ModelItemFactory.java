package com.magmaguy.freeminecraftmodels.utils;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.config.DisplayModelRegistry;
import com.magmaguy.magmacore.util.ChatColorConverter;
import com.magmaguy.magmacore.util.VersionChecker;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

public final class ModelItemFactory {

    private ModelItemFactory() {}

    /**
     * Creates a model placement item. If server is 1.21.4+ and a display model
     * JSON exists for this model, sets the item_model component for custom 3D rendering.
     */
    public static ItemStack createModelItem(String modelId, Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName(ChatColorConverter.convert(
                "&e\u2726 &6" + formatModelName(modelId) + " &e\u2726"));
        meta.setLore(List.of(
                "",
                ChatColorConverter.convert("&7Right-click on a block to place"),
                ChatColorConverter.convert("&7Punch to pick back up"),
                "",
                ChatColorConverter.convert("&8Model: " + modelId)
        ));

        // Store model ID in persistent data
        NamespacedKey key = new NamespacedKey(MetadataHandler.PLUGIN, "model_id");
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, modelId);

        // Set custom item model if available and server supports it (1.21.4+)
        if (!VersionChecker.serverVersionOlderThan(21, 4)
                && DisplayModelRegistry.hasDisplayModel(modelId)) {
            meta.setItemModel(NamespacedKey.fromString("freeminecraftmodels:display/" + modelId));
        }

        item.setItemMeta(meta);
        return item;
    }

    /**
     * Formats a model ID into a human-readable name.
     * Strips leading number prefixes (e.g. "01_em_"), replaces underscores
     * with spaces, and capitalizes each word.
     */
    public static String formatModelName(String modelId) {
        String name = modelId.replaceFirst("^\\d+_(?:em_)?", "");
        String[] words = name.split("_");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (!word.isEmpty()) {
                if (!sb.isEmpty()) sb.append(" ");
                sb.append(Character.toUpperCase(word.charAt(0)));
                if (word.length() > 1) sb.append(word.substring(1).toLowerCase());
            }
        }
        return sb.toString();
    }
}
