package com.magmaguy.freeminecraftmodels.config.items;

import com.magmaguy.magmacore.config.CustomConfigFields;
import lombok.Getter;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration fields for a custom scriptable item config file.
 * <p>
 * Each item YML file defines the material, display name, lore, enchantments,
 * and which Lua scripts to attach when the item is created or interacted with.
 */
public class ItemScriptConfigFields extends CustomConfigFields {

    @Getter
    private String material = "PAPER";
    @Getter
    private String itemName = "";
    @Getter
    private List<String> lore = new ArrayList<>();
    @Getter
    private List<String> enchantments = new ArrayList<>();
    @Getter
    private List<String> scripts = new ArrayList<>();

    /**
     * Used when creating a new default config or loading an existing one.
     *
     * @param filename  the YML filename
     * @param isEnabled whether this config is enabled
     */
    public ItemScriptConfigFields(String filename, boolean isEnabled) {
        super(filename, isEnabled);
    }

    @Override
    public void processConfigFields() {
        this.isEnabled = processBoolean("isEnabled", isEnabled, true, true);
        this.material = processString("material", material, "PAPER", true);
        this.itemName = processString("name", itemName, "", true);
        this.lore = processStringList("lore", lore, new ArrayList<>(), true);
        this.enchantments = processStringList("enchantments", enchantments, new ArrayList<>(), true);
        this.scripts = processStringList("scripts", scripts, new ArrayList<>(), true);
    }

    /**
     * Parses the enchantments list into a map of {@link Enchantment} to level.
     * Each entry is expected in the format {@code "ENCHANTMENT_NAME,LEVEL"} (e.g. {@code "SHARPNESS,5"}).
     * Invalid entries are silently skipped.
     *
     * @return a map of parsed enchantments and their levels
     */
    public Map<Enchantment, Integer> getParsedEnchantments() {
        Map<Enchantment, Integer> parsed = new HashMap<>();
        for (String entry : enchantments) {
            String[] parts = entry.split(",");
            if (parts.length != 2) continue;
            Enchantment enchantment = Enchantment.getByName(parts[0].trim().toUpperCase());
            if (enchantment == null) continue;
            try {
                int level = Integer.parseInt(parts[1].trim());
                parsed.put(enchantment, level);
            } catch (NumberFormatException ignored) {
            }
        }
        return parsed;
    }

    /**
     * Parses the material string into a {@link Material}.
     * Defaults to {@link Material#PAPER} if the value is invalid.
     *
     * @return the parsed material
     */
    public Material getParsedMaterial() {
        try {
            return Material.valueOf(material.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Material.PAPER;
        }
    }
}
