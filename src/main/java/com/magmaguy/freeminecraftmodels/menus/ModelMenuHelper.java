package com.magmaguy.freeminecraftmodels.menus;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.utils.ModelItemFactory;
import com.magmaguy.freeminecraftmodels.config.props.PropScriptConfigFields;
import com.magmaguy.freeminecraftmodels.config.recipes.PropRecipeConfig;
import com.magmaguy.freeminecraftmodels.config.recipes.PropRecipeManager;
import com.magmaguy.freeminecraftmodels.content.FMMPackage;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.magmacore.util.ChatColorConverter;
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
     * Inventory slots used for content items (rows 1-5, all 9 columns).
     * Row 6 (slots 45-53) is reserved for navigation.
     */
    public static final int[] CONTENT_SLOTS = {
             0,  1,  2,  3,  4,  5,  6,  7,  8,
             9, 10, 11, 12, 13, 14, 15, 16, 17,
            18, 19, 20, 21, 22, 23, 24, 25, 26,
            27, 28, 29, 30, 31, 32, 33, 34, 35,
            36, 37, 38, 39, 40, 41, 42, 43, 44
    };

    /** Number of items displayed per page. */
    public static final int ITEMS_PER_PAGE = 45;

    /** Slot index for the "Previous Page" navigation button. */
    public static final int PREV_SLOT = 45;

    /** Slot index for the "Next Page" navigation button. */
    public static final int NEXT_SLOT = 53;

    /** Slot index for the "Back" navigation button (in the nav row). */
    public static final int BACK_SLOT = 49;

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

        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            // Set custom display model if available (1.21.4+)
            if (!com.magmaguy.magmacore.util.VersionChecker.serverVersionOlderThan(21, 4)
                    && com.magmaguy.freeminecraftmodels.config.DisplayModelRegistry.hasDisplayModel(modelId)) {
                meta.setItemModel(NamespacedKey.fromString("freeminecraftmodels:display/" + modelId));
            }

            if (adminMode) {
                NamespacedKey key = new NamespacedKey(MetadataHandler.PLUGIN, "model_id");
                meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, modelId);
            }

            item.setItemMeta(meta);
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

    // Pre-cached skull items — resolved once at startup to avoid
    // blocking HTTP calls to Mojang API during menu creation.
    private static ItemStack cachedArrowLeft;
    private static ItemStack cachedArrowRight;

    /**
     * Pre-generates the MHF skull items. Call once during plugin initialization.
     */
    public static void initialize() {
        cachedArrowLeft = ItemStackGenerator.generateSkullItemStack("MHF_ArrowLeft", " ", List.of());
        cachedArrowRight = ItemStackGenerator.generateSkullItemStack("MHF_ArrowRight", " ", List.of());
    }

    public static void shutdown() {
        cachedArrowLeft = null;
        cachedArrowRight = null;
    }

    private static ItemStack skullWithMeta(ItemStack base, String name, List<String> lore) {
        if (base == null) return ItemStackGenerator.generateItemStack(Material.ARROW, name, lore);
        ItemStack clone = base.clone();
        ItemMeta meta = clone.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColorConverter.convert(name));
            meta.setLore(ChatColorConverter.convert(lore));
            clone.setItemMeta(meta);
        }
        return clone;
    }

    /**
     * Builds a "Previous Page" navigation skull item.
     */
    public static ItemStack buildPrevPageItem() {
        return skullWithMeta(cachedArrowLeft, "&ePrevious Page", List.of());
    }

    /**
     * Builds a "Next Page" navigation skull item.
     */
    public static ItemStack buildNextPageItem() {
        return skullWithMeta(cachedArrowRight, "&eNext Page", List.of());
    }

    /**
     * Builds the "Back" button skull item.
     */
    public static ItemStack buildBackItem() {
        return skullWithMeta(cachedArrowLeft, "&cBack", List.of("&7Return to previous menu"));
    }

    /**
     * Builds a right-pointing arrow skull item (for recipe display, etc.).
     */
    public static ItemStack buildArrowRightItem() {
        return skullWithMeta(cachedArrowRight, " ", List.of());
    }

    /**
     * Builds a display item for a custom scriptable item in the admin menu.
     */
    public static ItemStack buildCustomItemDisplayItem(String itemId) {
        com.magmaguy.freeminecraftmodels.config.props.PropScriptConfigFields config =
                com.magmaguy.freeminecraftmodels.scripting.ItemScriptManager.getItemDefinitions().get(itemId);

        String formattedName = ModelItemFactory.formatModelName(itemId);
        String displayName = config != null && !config.getItemName().isEmpty()
                ? config.getItemName()
                : "&d\u2726 &5" + formattedName + " &d\u2726";

        Material material = (config != null && config.getParsedMaterial() != null) ? config.getParsedMaterial() : Material.PAPER;

        List<String> lore = new ArrayList<>();
        lore.add("&8Custom Item");
        lore.add("");

        if (config != null) {
            // Scripts
            List<String> scripts = config.getScripts();
            lore.add("&7Scripts:");
            if (scripts == null || scripts.isEmpty()) {
                lore.add("  &8None");
            } else {
                for (String script : scripts) {
                    lore.add("  &f- &7" + script);
                }
            }
            lore.add("");

            // Enchantments
            if (!config.getEnchantments().isEmpty()) {
                lore.add("&7Enchantments:");
                for (String ench : config.getEnchantments()) {
                    lore.add("  &b" + ench);
                }
                lore.add("");
            }
        }

        lore.add("&eClick to get item");
        lore.add("");
        lore.add("&8ID: " + itemId);

        return ItemStackGenerator.generateItemStack(material, displayName, lore);
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
