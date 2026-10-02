package com.magmaguy.freeminecraftmodels.bedrock;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Tells ResourcePackManager which vanilla item a custom item's display model rides on. Geyser
 * matches custom items by base item and model, and ResourcePackManager otherwise guesses the
 * base item from the file name ("wand" becomes a stick), so a BLAZE_ROD wand or a WOODEN_SPEAR
 * staff would stay vanilla on Bedrock. The material comes from the model's sibling YAML.
 */
public final class ItemBaseDeclaration {
    private ItemBaseDeclaration() {
    }

    /**
     * Writes {@code rspm_item_bases/display/<itemId>.json} when the model's YAML names an item
     * material this server knows.
     */
    public static void write(File freeMinecraftModelsAssets, String itemId, File modelSource) throws IOException {
        write(freeMinecraftModelsAssets, itemId, modelSource, ItemBaseDeclaration::isServerItem);
    }

    static void write(File freeMinecraftModelsAssets, String itemId, File modelSource, Predicate<String> isItem)
            throws IOException {
        if (modelSource == null) return;
        String name = modelSource.getName();
        int dot = name.lastIndexOf('.');
        File yml = new File(modelSource.getParentFile(), (dot > 0 ? name.substring(0, dot) : name) + ".yml");
        if (!yml.isFile()) return;
        String materialName = YamlConfiguration.loadConfiguration(yml).getString("material");
        if (materialName == null) return;
        String material = materialName.trim().toUpperCase(Locale.ROOT);
        if (!material.matches("[A-Z0-9_]+") || !isItem.test(material)) return;
        File file = new File(freeMinecraftModelsAssets, "rspm_item_bases/display/" + itemId + ".json");
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), "{\"base_items\": [\"minecraft:" + material.toLowerCase(Locale.ROOT) + "\"]}\n",
                StandardCharsets.UTF_8);
    }

    private static boolean isServerItem(String material) {
        Material match = Material.matchMaterial(material);
        return match != null && match.isItem() && !match.isAir();
    }
}
