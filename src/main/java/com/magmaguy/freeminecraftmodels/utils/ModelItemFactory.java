package com.magmaguy.freeminecraftmodels.utils;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.config.DisplayModelRegistry;
import com.magmaguy.freeminecraftmodels.config.props.PropScriptConfigFields;
import com.magmaguy.magmacore.util.ChatColorConverter;
import com.magmaguy.magmacore.util.VersionChecker;
import com.magmaguy.magmacore.enchantments.EnchantmentDefinitions;
import com.magmaguy.magmacore.enchantments.EnchantmentItems;
import com.magmaguy.magmacore.enchantments.EnchantmentItemProfile;
import com.magmaguy.magmacore.enchantments.EnchantmentDefinition;
import com.magmaguy.freeminecraftmodels.scripting.ItemScriptManager;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Map;

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
     * Creates a custom item from a model config that has item fields (material, enchantments, etc.).
     * Uses the display model for rendering, stores fmm_item_id PDC (not model_id).
     */
    public static ItemStack createCustomItem(String itemId, PropScriptConfigFields config) {
        Material material = config.getParsedMaterial();
        if (material == null) throw new IllegalArgumentException("Item has no material: " + itemId);

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) throw new IllegalArgumentException("Item does not support metadata: " + itemId);

        // Name — use config name if set, otherwise generate from ID
        String name = config.getItemName();
        if (name != null && !name.isEmpty()) {
            meta.setDisplayName(ChatColorConverter.convert(name));
        } else {
            meta.setDisplayName(ChatColorConverter.convert(
                    "&e\u2726 &6" + formatModelName(itemId) + " &e\u2726"));
        }

        // Lore
        List<String> lore = config.getLore();
        if (lore != null && !lore.isEmpty()) {
            meta.setLore(ChatColorConverter.convert(lore));
        }

        // PDC — custom item ID (not model_id, so ModelItemListener won't try to place it)
        NamespacedKey itemKey = new NamespacedKey(MetadataHandler.PLUGIN, "fmm_item_id");
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, itemId);

        // Display model (1.21.4+)
        if (!VersionChecker.serverVersionOlderThan(21, 4)
                && DisplayModelRegistry.hasDisplayModel(itemId)) {
            meta.setItemModel(NamespacedKey.fromString("freeminecraftmodels:display/" + itemId));
        }

        item.setItemMeta(meta);
        if (config.getParsedEnchantments().isEmpty()) return item;
        EnchantmentItems enchantments = new EnchantmentItems(EnchantmentDefinitions::resolve, ModelItemFactory::enchantmentProfile);
        return enchantments.previewAuthored(item, config.getParsedEnchantments()).apply(item);
    }

    public static EnchantmentItemProfile enchantmentProfile(ItemStack item) {
        String id = item.hasItemMeta() ? item.getItemMeta().getPersistentDataContainer()
                .get(ItemScriptManager.ITEM_ID_KEY, PersistentDataType.STRING) : null;
        if (id == null) return EnchantmentItemProfile.vanilla(item);
        var definition = ItemScriptManager.getItemDefinitions().get(id);
        if (definition == null || !definition.isEnabled() || definition.getParsedMaterial() != item.getType())
            throw new IllegalArgumentException("FMM item definition is unavailable or its material changed: " + id);
        var weapon = definition.getWeapon();
        if (weapon == null) return EnchantmentItemProfile.vanilla(item);
        return new EnchantmentItemProfile(EnchantmentDefinition.ItemType.valueOf(weapon.kind().name()),
                java.util.Set.of(EnchantmentDefinition.Slot.MAINHAND),
                weapon.basePowers().keySet().stream().map(Enum::name).collect(java.util.stream.Collectors.toUnmodifiableSet()));
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
