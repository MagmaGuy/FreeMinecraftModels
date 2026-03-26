package com.magmaguy.freeminecraftmodels.config.props;

import com.magmaguy.magmacore.config.CustomConfigFields;
import lombok.Getter;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Unified configuration fields for model YML config files.
 * <p>
 * Each model file (e.g. {@code torch_01.bbmodel}) can have a sibling YML file
 * (e.g. {@code torch_01.yml}) that configures scripts and optionally item properties.
 * <p>
 * If {@code material} is set, the model is also available as a custom item that
 * players can hold, with the specified material, enchantments, and lore.
 * Script filenames resolve from the central {@code scripts/} folder.
 */
public class PropScriptConfigFields extends CustomConfigFields {

    @Getter
    private List<String> scripts = new ArrayList<>();

    // Item-specific fields (optional — if material is set, model is also a custom item)
    @Getter
    private String material = "";
    @Getter
    private String itemName = "";
    @Getter
    private List<String> lore = new ArrayList<>();
    @Getter
    private List<String> enchantments = new ArrayList<>();
    @Getter
    private boolean voxelize = false;
    @Getter
    private boolean solidify = false;

    /**
     * Used when creating a new default config or loading an existing one.
     *
     * @param filename  the YML filename (e.g. {@code torch_01.yml})
     * @param isEnabled whether this config is enabled
     */
    public PropScriptConfigFields(String filename, boolean isEnabled) {
        super(filename, isEnabled);
    }

    @Override
    public void processConfigFields() {
        this.isEnabled = processBoolean("isEnabled", isEnabled, true, true);
        this.scripts = processStringList("scripts", scripts, new ArrayList<>(), true);
        this.material = processString("material", material, "", false);
        this.itemName = processString("name", itemName, "", false);
        this.lore = processStringList("lore", lore, new ArrayList<>(), false);
        this.enchantments = processStringList("enchantments", enchantments, new ArrayList<>(), false);
        this.voxelize = processBoolean("voxelize", voxelize, false, false);
        this.solidify = processBoolean("solidify", solidify, false, false);
    }

    /**
     * Returns true if this model is also configured as a custom item
     * (has a material defined).
     */
    public boolean isCustomItem() {
        return material != null && !material.isEmpty();
    }

    /**
     * Parses the enchantments list into a map of {@link Enchantment} to level.
     * Format: {@code "ENCHANTMENT_NAME,LEVEL"} (e.g. {@code "SHARPNESS,5"}).
     */
    public Map<Enchantment, Integer> getParsedEnchantments() {
        Map<Enchantment, Integer> parsed = new HashMap<>();
        for (String entry : enchantments) {
            String[] parts = entry.split(",");
            if (parts.length != 2) continue;
            Enchantment enchantment = Enchantment.getByName(parts[0].trim().toUpperCase());
            if (enchantment == null) continue;
            try {
                parsed.put(enchantment, Integer.parseInt(parts[1].trim()));
            } catch (NumberFormatException ignored) {}
        }
        return parsed;
    }

    /**
     * Parses the material string. Returns null if not set or invalid.
     */
    public Material getParsedMaterial() {
        if (material == null || material.isEmpty()) return null;
        try {
            return Material.valueOf(material.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Material.PAPER;
        }
    }
}
