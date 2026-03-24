package com.magmaguy.freeminecraftmodels.menus;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.utils.ModelItemFactory;
import com.magmaguy.freeminecraftmodels.config.props.PropScriptConfigFields;
import com.magmaguy.freeminecraftmodels.config.recipes.PropRecipeConfig;
import com.magmaguy.freeminecraftmodels.config.recipes.PropRecipeManager;
import com.magmaguy.freeminecraftmodels.content.FMMPackage;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.magmacore.util.ItemStackGenerator;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Shared constants and utility methods used by the admin content menu,
 * admin model list menu, and craftable items menu.
 */
public final class ModelMenuHelper {

    /**
     * Inventory slots used for content items (3 rows of 7, starting at row 2).
     */
    public static final int[] CONTENT_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34
    };

    /** Number of items displayed per page. */
    public static final int ITEMS_PER_PAGE = 21;

    /** Slot index for the "Previous Page" navigation button. */
    public static final int PREV_SLOT = 45;

    /** Slot index for the "Next Page" navigation button. */
    public static final int NEXT_SLOT = 53;

    /** Slot index for the "Back" navigation button. */
    public static final int BACK_SLOT = 0;

    private ModelMenuHelper() {
        // utility class
    }

    // ---------------------------------------------------------------
    // Data helpers
    // ---------------------------------------------------------------

    /**
     * Returns all loaded {@link FileModelConverter}s whose source file belongs
     * to the given content pack, sorted alphabetically by model ID.
     */
    public static List<FileModelConverter> getModelsForPack(FMMPackage pack) {
        String folderName = pack.getContentPackageConfigFields().getFolderName();
        List<String> prefixes = pack.getContentPackageConfigFields().getContentFilePrefixes();

        File modelsRoot = new File(MetadataHandler.PLUGIN.getDataFolder(), "models");

        return FileModelConverter.getConvertedFileModels().values().stream()
                .filter(converter -> {
                    File source = converter.getSourceFile();
                    if (source == null) return false;
                    // Walk parent directories up to models root, check for exact folder name match
                    File parent = source.getParentFile();
                    while (parent != null && !parent.equals(modelsRoot)) {
                        if (parent.getName().equals(folderName)) return true;
                        parent = parent.getParentFile();
                    }
                    // Fall back to content file prefix matching
                    if (prefixes != null) {
                        String fileName = source.getName();
                        for (String prefix : prefixes) {
                            if (fileName.startsWith(prefix)) return true;
                        }
                    }
                    return false;
                })
                .sorted(Comparator.comparing(FileModelConverter::getID))
                .collect(Collectors.toList());
    }

    /**
     * Reads the sibling {@code .yml} config file for a model and returns
     * the list of attached script filenames, or an empty list if none.
     */
    public static List<String> getScriptsForModel(FileModelConverter converter) {
        File source = converter.getSourceFile();
        if (source == null) return Collections.emptyList();

        String baseName = source.getName();
        int dotIndex = baseName.lastIndexOf('.');
        if (dotIndex > 0) {
            baseName = baseName.substring(0, dotIndex);
        }

        File ymlFile = new File(source.getParentFile(), baseName + ".yml");
        if (!ymlFile.exists()) return Collections.emptyList();

        try {
            PropScriptConfigFields fields = new PropScriptConfigFields(ymlFile.getName(), true);
            fields.setFileConfiguration(YamlConfiguration.loadConfiguration(ymlFile));
            fields.setFile(ymlFile);
            fields.processConfigFields();
            List<String> scripts = fields.getScripts();
            return scripts != null ? scripts : Collections.emptyList();
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    // ---------------------------------------------------------------
    // ItemStack builders
    // ---------------------------------------------------------------

    /**
     * Builds a display {@link ItemStack} representing a model in the menu.
     *
     * @param converter the model converter
     * @param adminMode if true, adds "Click to get item" hint and stores model_id in PDC
     * @return the display item
     */
    public static ItemStack buildModelItem(FileModelConverter converter, boolean adminMode) {
        String modelId = converter.getID();
        String formattedName = ModelItemFactory.formatModelName(modelId);
        String displayName = "&e\u2726 &6" + formattedName + " &e\u2726";

        List<String> lore = new ArrayList<>();
        lore.add("");

        // Scripts section (admin only — players don't need to see this)
        if (adminMode) {
            List<String> scripts = getScriptsForModel(converter);
            lore.add("&7Scripts:");
            if (scripts.isEmpty()) {
                lore.add("  &8None");
            } else {
                for (String script : scripts) {
                    lore.add("  &f- &7" + script);
                }
            }
            lore.add("");
        }

        // Craftable status
        PropRecipeConfig recipe = PropRecipeManager.getLoadedRecipes().get(modelId);
        if (recipe != null) {
            lore.add("&7Craftable: &aYes");
            lore.add("");
            // Render the 3x3 recipe grid
            List<String> shape = recipe.getShapeList();
            Map<Character, Material> ingredients = recipe.getIngredients();
            lore.add("&7Recipe:");
            for (String row : shape) {
                StringBuilder rowDisplay = new StringBuilder("  ");
                for (int i = 0; i < row.length(); i++) {
                    char c = row.charAt(i);
                    if (i > 0) rowDisplay.append(" &8| ");
                    Material mat = ingredients.get(c);
                    if (mat != null) {
                        rowDisplay.append("&f").append(formatMaterialName(mat));
                    } else {
                        rowDisplay.append("&8Empty");
                    }
                }
                lore.add(rowDisplay.toString());
            }
        } else {
            lore.add("&7Craftable: &cNo");
        }

        if (adminMode) {
            lore.add("");
            lore.add("&eClick to get item");
        } else if (recipe != null) {
            lore.add("");
            lore.add("&eClick for recipe");
        }

        lore.add("");
        lore.add("&8ID: " + modelId);

        ItemStack item = ItemStackGenerator.generateItemStack(Material.PAPER, displayName, lore);

        if (adminMode) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                NamespacedKey key = new NamespacedKey(MetadataHandler.PLUGIN, "model_id");
                meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, modelId);
                item.setItemMeta(meta);
            }
        }

        return item;
    }

    /**
     * Builds a display {@link ItemStack} representing a content pack in the menu.
     */
    public static ItemStack buildPackItem(FMMPackage pack) {
        String packName = pack.getContentPackageConfigFields().getName();
        String name = "<gradient:#FFD700:#FFA500>" + packName + "</gradient>";

        List<String> lore = new ArrayList<>();
        List<String> description = pack.getContentPackageConfigFields().getDescription();
        if (description != null) {
            for (String line : description) {
                lore.add("&7" + line);
            }
        }
        lore.add("");
        int modelCount = getModelsForPack(pack).size();
        lore.add("&7Models: &f" + modelCount);
        lore.add("");
        lore.add("&eClick to browse");

        return ItemStackGenerator.generateItemStack(Material.CHEST, name, lore);
    }

    /**
     * Builds a simple navigation arrow item with the given display name.
     */
    public static ItemStack buildNavItem(String name) {
        return ItemStackGenerator.generateItemStack(Material.ARROW, name, List.of());
    }

    /**
     * Builds the "Back" button item.
     */
    public static ItemStack buildBackItem() {
        return ItemStackGenerator.generateItemStack(Material.BARRIER, "&cBack", List.of("&7Return to pack list"));
    }

    // ---------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------

    /**
     * Converts a {@link Material} enum name like {@code DARK_OAK_PLANKS} into
     * a human-readable form like {@code "Dark Oak Planks"}.
     */
    private static String formatMaterialName(Material material) {
        String[] words = material.name().split("_");
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
