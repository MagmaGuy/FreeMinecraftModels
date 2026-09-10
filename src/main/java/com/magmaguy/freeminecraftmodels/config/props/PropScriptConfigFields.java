package com.magmaguy.freeminecraftmodels.config.props;

import com.magmaguy.magmacore.config.CustomConfigFields;
import lombok.Getter;
import com.magmaguy.freeminecraftmodels.magic.MagicWeaponConfig;
import com.magmaguy.freeminecraftmodels.magic.MagicWeaponDefinition;
import org.bukkit.Material;

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
    @Getter
    private MagicWeaponDefinition weapon;
    private Map<String, Integer> parsedEnchantments = Map.of();
    @Getter
    private String unavailableReason;

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
        Object enabled = fileConfiguration.get("isEnabled");
        if (enabled != null && !(enabled instanceof Boolean))
            throw new IllegalArgumentException(filename + ": isEnabled must be a boolean");
        this.isEnabled = processBoolean("isEnabled", isEnabled, true, true);
        weapon = null;
        parsedEnchantments = Map.of();
        unavailableReason = null;
        scripts = new ArrayList<>();
        material = "";
        if (!isEnabled) return;
        Object rawMaterial = fileConfiguration.get("material");
        if (rawMaterial != null && !(rawMaterial instanceof String))
            throw new IllegalArgumentException(filename + ": material must be a string");
        if (rawMaterial instanceof String itemMaterial && !itemMaterial.isBlank()) {
            // Unsupported/retired authored items are isolated content, not a plugin bootstrap failure.
            // Do not rewrite the file, infer a replacement material, or revive its old item script.
            try { Material.valueOf(itemMaterial.trim().toUpperCase(java.util.Locale.ROOT)); }
            catch (IllegalArgumentException unavailable) {
                unavailableReason = "item material " + itemMaterial + " is unavailable on this server";
                return;
            }
            if (fileConfiguration.get("scripts") instanceof List<?> itemScripts && !itemScripts.isEmpty()) {
                unavailableReason = "retired scripted item format; replace this item with authored enchantments";
                return;
            }
            if (fileConfiguration.get("enchantments") instanceof List<?> entries) {
                for (Object entry : entries) {
                    if (entry instanceof String text && text.matches("(?i)\\s*[a-z0-9_]+\\s*,.*")) {
                        unavailableReason = "retired unnamespaced item enchantments; replace this item definition";
                        return;
                    }
                }
            }
        }
        if (fileConfiguration.contains("weapon")) {
            Object rawScripts = fileConfiguration.get("scripts");
            if (rawScripts != null && (!(rawScripts instanceof List<?> list) || !list.isEmpty()))
                throw new IllegalArgumentException(filename + ": weapon effects must use enchantments, not item scripts");
        }
        this.scripts = processStringList("scripts", scripts, new ArrayList<>(), true);
        this.material = processString("material", material, "", false);
        this.itemName = processString("name", itemName, "", false);
        this.lore = processStringList("lore", lore, new ArrayList<>(), false);
        this.enchantments = processStringList("enchantments", enchantments, new ArrayList<>(), false);
        this.voxelize = processBoolean("voxelize", voxelize, false, false);
        this.solidify = processBoolean("solidify", solidify, false, false);
        if (fileConfiguration.contains("weapon")) {
            if (!isCustomItem()) throw new IllegalArgumentException(filename + ": weapon requires an item material");
            weapon = MagicWeaponConfig.parse(filename.substring(0, filename.length() - 4).toLowerCase(java.util.Locale.ROOT),
                    fileConfiguration.getConfigurationSection("weapon"));
            if (!scripts.isEmpty()) throw new IllegalArgumentException(filename + ": weapon effects must use enchantments, not item scripts");
        }
        if (isCustomItem()) {
            Material parsedMaterial = getParsedMaterial();
            if (parsedMaterial.isAir() || !parsedMaterial.isItem() || weapon != null && parsedMaterial == Material.ENCHANTED_BOOK)
                throw new IllegalArgumentException(filename + ": invalid material for this item definition");
            parsedEnchantments = parseEnchantments(fileConfiguration.get("enchantments"));
        }
    }

    /**
     * Returns true if this model is also configured as a custom item
     * (has a material defined).
     */
    public boolean isCustomItem() {
        return material != null && !material.isEmpty();
    }

    public Map<String, Integer> getParsedEnchantments() { return parsedEnchantments; }

    private Map<String, Integer> parseEnchantments(Object raw) {
        if (raw == null) return Map.of();
        if (!(raw instanceof List<?> entries)) throw new IllegalArgumentException(filename + ": enchantments must be a list");
        Map<String, Integer> parsed = new HashMap<>();
        for (Object value : entries) {
            if (!(value instanceof String entry)) throw new IllegalArgumentException(filename + ": enchantment entries must be strings");
            String[] parts = entry.split(",", -1);
            if (parts.length != 2 || !parts[0].trim().matches("[a-z0-9._-]{1,64}:[a-z0-9._-]{1,128}") || !parts[1].trim().matches("[1-9][0-9]*"))
                throw new IllegalArgumentException(filename + ": expected namespaced enchantment and positive integer level: " + entry);
            int level = Integer.parseInt(parts[1].trim());
            if (parsed.putIfAbsent(parts[0].trim(), level) != null) throw new IllegalArgumentException(filename + ": duplicate enchantment " + parts[0]);
        }
        return Map.copyOf(parsed);
    }

    /**
     * Parses the material string. Returns null only when it is not set.
     */
    public Material getParsedMaterial() {
        if (material == null || material.isEmpty()) return null;
        try {
            return Material.valueOf(material.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) { throw new IllegalArgumentException(filename + ": unknown item material " + material, e); }
    }
}
