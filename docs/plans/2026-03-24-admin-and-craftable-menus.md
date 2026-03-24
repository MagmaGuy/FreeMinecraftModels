# Admin Content Browser & Craftable Items Menu — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add two inventory menus: `/fmm admin` for OPs to browse all installed content packs/models (with script & recipe info, click-to-get), and `/fmm` (no args) for all players to browse craftable items with recipe details.

**Architecture:** Three new menu classes following EliteMobs patterns — each owns a static `HashMap<Inventory, MenuInstance>` for tracking, an inner `Listener` class for click/close events, and pagination. Data is resolved at menu-open time by cross-referencing `FileModelConverter`, `PropScriptManager` sibling YMLs, and `PropRecipeManager`. A shared utility class builds model lore to avoid duplication between admin and player menus.

**Tech Stack:** Spigot Inventory API, MagmaCore `ItemStackGenerator` + `ChatColorConverter`, existing `FileModelConverter` / `PropRecipeManager` / `PropScriptConfigFields` data sources.

---

### Task 1: ModelMenuHelper — shared lore builder + model-to-pack mapping

**Files:**
- Create: `src/main/java/com/magmaguy/freeminecraftmodels/menus/ModelMenuHelper.java`

**Step 1: Create ModelMenuHelper with model-to-pack mapping and lore building**

This utility class provides:
1. `getModelsForPack(FMMPackage)` — returns list of `FileModelConverter` whose `sourceFile` lives under the pack's folder
2. `getScriptsForModel(FileModelConverter)` — reads the sibling `.yml` to get script filenames
3. `getRecipeForModel(String modelId)` — looks up `PropRecipeManager.getLoadedRecipes().get(modelId)`
4. `buildModelItem(FileModelConverter, boolean adminMode)` — creates a PAPER ItemStack with name + lore (scripts, craftable status, recipe grid, click hint)
5. `buildPackItem(FMMPackage)` — creates an ItemStack representing a pack

```java
package com.magmaguy.freeminecraftmodels.menus;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.commands.ItemifyCommand;
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

public class ModelMenuHelper {

    // Content slots: 3 rows of 7 in the middle of a 54-slot inventory
    public static final int[] CONTENT_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34
    };
    public static final int ITEMS_PER_PAGE = CONTENT_SLOTS.length; // 21
    public static final int PREV_SLOT = 45;
    public static final int NEXT_SLOT = 53;
    public static final int BACK_SLOT = 0;

    /**
     * Returns all loaded FileModelConverters whose source file is inside
     * the given pack's folder (or matches its content file prefixes).
     */
    public static List<FileModelConverter> getModelsForPack(FMMPackage pack) {
        String folderName = pack.getContentPackageConfigFields().getFolderName();
        List<String> prefixes = pack.getContentPackageConfigFields().getContentFilePrefixes();
        File modelsRoot = new File(MetadataHandler.PLUGIN.getDataFolder(), "models");

        List<FileModelConverter> result = new ArrayList<>();
        for (FileModelConverter converter : FileModelConverter.getConvertedFileModels().values()) {
            if (converter.getSourceFile() == null) continue;
            File sourceFile = converter.getSourceFile();

            // Check if file is inside the pack's subfolder
            File parent = sourceFile.getParentFile();
            while (parent != null && !parent.equals(modelsRoot)) {
                if (parent.getName().equals(folderName)) {
                    result.add(converter);
                    break;
                }
                parent = parent.getParentFile();
            }

            // Also check content file prefixes if not matched by folder
            if (!result.contains(converter) && prefixes != null) {
                for (String prefix : prefixes) {
                    if (sourceFile.getName().startsWith(prefix)) {
                        result.add(converter);
                        break;
                    }
                }
            }
        }

        result.sort(Comparator.comparing(FileModelConverter::getID));
        return result;
    }

    /**
     * Reads the sibling .yml config for a model and returns script filenames.
     */
    public static List<String> getScriptsForModel(FileModelConverter converter) {
        if (converter.getSourceFile() == null) return Collections.emptyList();

        File modelFile = converter.getSourceFile();
        String baseName = modelFile.getName();
        if (baseName.endsWith(".fmmodel")) baseName = baseName.substring(0, baseName.length() - 8);
        else if (baseName.endsWith(".bbmodel")) baseName = baseName.substring(0, baseName.length() - 8);
        File ymlFile = new File(modelFile.getParentFile(), baseName + ".yml");

        if (!ymlFile.exists()) return Collections.emptyList();

        PropScriptConfigFields configFields = new PropScriptConfigFields(ymlFile.getName(), true);
        org.bukkit.configuration.file.FileConfiguration fileConfig = YamlConfiguration.loadConfiguration(ymlFile);
        configFields.setFileConfiguration(fileConfig);
        configFields.setFile(ymlFile);
        configFields.processConfigFields();

        List<String> scripts = configFields.getScripts();
        return scripts != null ? scripts : Collections.emptyList();
    }

    /**
     * Builds a PAPER ItemStack representing a model, with full lore
     * showing scripts, craftable status, and recipe grid.
     *
     * @param adminMode if true, shows "Click to get item"; if false, view-only
     */
    public static ItemStack buildModelItem(FileModelConverter converter, boolean adminMode) {
        String modelId = converter.getID();
        String displayName = "&e\u2726 &6" + ItemifyCommand.formatModelName(modelId) + " &e\u2726";

        List<String> lore = new ArrayList<>();

        // Scripts section
        List<String> scripts = getScriptsForModel(converter);
        lore.add("&7Scripts:");
        if (scripts.isEmpty()) {
            lore.add("  &8None");
        } else {
            for (String script : scripts) {
                lore.add("  &8- " + script);
            }
        }

        lore.add("");

        // Craftable section
        PropRecipeConfig recipe = PropRecipeManager.getLoadedRecipes().get(modelId);
        if (recipe != null) {
            lore.add("&7Craftable: &aYes");
            lore.add("&7Recipe:");
            // Render the 3x3 grid from the shape + ingredients
            // The shape is a list of strings like ["ABA", "A A", "AAA"]
            // and ingredients maps chars to Materials
            Map<Character, Material> ingredients = recipe.getIngredients();
            for (String row : recipe.getShapeList()) {
                StringBuilder rowStr = new StringBuilder("  &8");
                for (int i = 0; i < row.length(); i++) {
                    char c = row.charAt(i);
                    if (c == ' ') {
                        rowStr.append("[     ] ");
                    } else {
                        Material mat = ingredients.get(c);
                        String matName = mat != null ? formatMaterialName(mat) : "?";
                        rowStr.append("[").append(matName).append("] ");
                    }
                }
                lore.add(rowStr.toString().trim());
            }
        } else {
            lore.add("&7Craftable: &cNo");
        }

        lore.add("");
        if (adminMode) {
            lore.add("&aClick to get item");
        }
        lore.add("&8ID: " + modelId);

        ItemStack item = ItemStackGenerator.generateItemStack(Material.PAPER, displayName, lore);

        // Store model_id in PDC for the admin click-to-get
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
     * Builds an ItemStack representing a content pack for the pack list menu.
     */
    public static ItemStack buildPackItem(FMMPackage pack) {
        String name = "<gradient:#FFD700:#FFA500>" + pack.getDisplayName() + "</gradient>";
        List<String> lore = new ArrayList<>();
        List<String> desc = pack.getContentPackageConfigFields().getDescription();
        if (desc != null) {
            for (String line : desc) {
                lore.add("&7" + line);
            }
        }
        int modelCount = getModelsForPack(pack).size();
        lore.add("");
        lore.add("&e" + modelCount + " model" + (modelCount != 1 ? "s" : ""));
        lore.add("&aClick to browse");

        return ItemStackGenerator.generateItemStack(Material.CHEST, name, lore);
    }

    /**
     * Creates a navigation arrow item.
     */
    public static ItemStack buildNavItem(String name) {
        return ItemStackGenerator.generateItemStack(Material.ARROW, name, List.of());
    }

    /**
     * Creates a back button item.
     */
    public static ItemStack buildBackItem() {
        return ItemStackGenerator.generateItemStack(Material.BARRIER, "&cBack", List.of("&7Return to pack list"));
    }

    /**
     * Formats a Material enum name into a readable short name.
     * e.g. DARK_OAK_PLANKS -> "Dark Oak Planks"
     * If the name is too long for recipe display, abbreviates.
     */
    private static String formatMaterialName(Material material) {
        String name = material.name().replace('_', ' ');
        // Title case
        StringBuilder sb = new StringBuilder();
        for (String word : name.split(" ")) {
            if (!sb.isEmpty()) sb.append(" ");
            if (word.length() <= 1) {
                sb.append(word.toUpperCase());
            } else {
                sb.append(Character.toUpperCase(word.charAt(0)))
                  .append(word.substring(1).toLowerCase());
            }
        }
        return sb.toString();
    }
}
```

**Step 2: Fix PropRecipeConfig getter — `getShape()` returns String but should return List**

`PropRecipeConfig.java:94` has `public String getShape()` but `shape` is `List<String>`. Add a proper list getter:

In `PropRecipeConfig.java`, change:
```java
public String getShape() { return shape; }
```
to:
```java
public List<String> getShapeList() { return shape; }
```

Note: The existing `getShape()` likely has a compile warning or returns toString. Replace it with `getShapeList()`.

**Step 3: Compile and verify**

Run: `mvn compile -f pom.xml`
Expected: BUILD SUCCESS

**Step 4: Commit**

```bash
git add src/main/java/com/magmaguy/freeminecraftmodels/menus/ModelMenuHelper.java
git add src/main/java/com/magmaguy/freeminecraftmodels/config/recipes/PropRecipeConfig.java
git commit -m "feat: add ModelMenuHelper for shared menu lore building and model-pack mapping"
```

---

### Task 2: AdminContentMenu — pack list (Level 1)

**Files:**
- Create: `src/main/java/com/magmaguy/freeminecraftmodels/menus/AdminContentMenu.java`

**Step 1: Create AdminContentMenu**

This class:
- Opens a 54-slot inventory titled "FMM Admin - Content Packs"
- Populates content slots with pack items from `FMMPackage.getFmmPackages()`
- Handles pagination via prev/next arrows
- On pack click, opens `AdminModelListMenu` for that pack
- Tracks open menus in `HashMap<Inventory, AdminContentMenu>` for event routing
- Inner static `Events` class implements `Listener`

```java
package com.magmaguy.freeminecraftmodels.menus;

import com.magmaguy.freeminecraftmodels.content.FMMPackage;
import com.magmaguy.magmacore.util.ChatColorConverter;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.stream.Collectors;

public class AdminContentMenu {

    private static final HashMap<Inventory, AdminContentMenu> activeMenus = new HashMap<>();

    private final Player player;
    private final Inventory inventory;
    private final List<FMMPackage> packs;
    private int page;

    public AdminContentMenu(Player player) {
        this(player, 0);
    }

    public AdminContentMenu(Player player, int page) {
        this.player = player;
        this.page = page;

        // Sort packs alphabetically by display name
        this.packs = FMMPackage.getFmmPackages().values().stream()
                .filter(pkg -> pkg.getContentPackageConfigFields().isEnabled())
                .sorted(Comparator.comparing(pkg ->
                        ChatColor.stripColor(ChatColorConverter.convert(pkg.getDisplayName()))))
                .collect(Collectors.toList());

        this.inventory = Bukkit.createInventory(null, 54,
                ChatColorConverter.convert("&8FMM Admin - Content Packs"));

        populate();
        activeMenus.put(inventory, this);
        player.openInventory(inventory);
    }

    private void populate() {
        inventory.clear();

        int startIndex = page * ModelMenuHelper.ITEMS_PER_PAGE;
        int endIndex = Math.min(startIndex + ModelMenuHelper.ITEMS_PER_PAGE, packs.size());

        for (int i = 0; i < ModelMenuHelper.CONTENT_SLOTS.length && (startIndex + i) < endIndex; i++) {
            FMMPackage pack = packs.get(startIndex + i);
            inventory.setItem(ModelMenuHelper.CONTENT_SLOTS[i], ModelMenuHelper.buildPackItem(pack));
        }

        // Navigation
        if (page > 0) {
            inventory.setItem(ModelMenuHelper.PREV_SLOT, ModelMenuHelper.buildNavItem("&ePrevious Page"));
        }
        if (endIndex < packs.size()) {
            inventory.setItem(ModelMenuHelper.NEXT_SLOT, ModelMenuHelper.buildNavItem("&eNext Page"));
        }
    }

    /**
     * Returns the FMMPackage at the given content slot index, or null.
     */
    private FMMPackage getPackAtSlot(int slot) {
        int contentIndex = -1;
        for (int i = 0; i < ModelMenuHelper.CONTENT_SLOTS.length; i++) {
            if (ModelMenuHelper.CONTENT_SLOTS[i] == slot) {
                contentIndex = i;
                break;
            }
        }
        if (contentIndex < 0) return null;
        int packIndex = page * ModelMenuHelper.ITEMS_PER_PAGE + contentIndex;
        if (packIndex >= packs.size()) return null;
        return packs.get(packIndex);
    }

    public static void registerEvents(org.bukkit.plugin.Plugin plugin) {
        Bukkit.getPluginManager().registerEvents(new Events(), plugin);
    }

    public static class Events implements Listener {
        @EventHandler
        public void onClick(InventoryClickEvent event) {
            AdminContentMenu menu = activeMenus.get(event.getInventory());
            if (menu == null) return;
            event.setCancelled(true);

            if (!(event.getWhoClicked() instanceof Player clicker)) return;
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || clicked.getType().isAir()) return;

            int slot = event.getRawSlot();
            if (slot >= event.getInventory().getSize()) return; // ignore bottom inventory

            if (slot == ModelMenuHelper.PREV_SLOT && menu.page > 0) {
                menu.page--;
                menu.populate();
                return;
            }
            if (slot == ModelMenuHelper.NEXT_SLOT) {
                int maxPage = (menu.packs.size() - 1) / ModelMenuHelper.ITEMS_PER_PAGE;
                if (menu.page < maxPage) {
                    menu.page++;
                    menu.populate();
                }
                return;
            }

            FMMPackage pack = menu.getPackAtSlot(slot);
            if (pack != null) {
                activeMenus.remove(menu.inventory);
                new AdminModelListMenu(clicker, pack);
            }
        }

        @EventHandler
        public void onClose(InventoryCloseEvent event) {
            activeMenus.remove(event.getInventory());
        }
    }
}
```

**Step 2: Compile and verify**

Run: `mvn compile -f pom.xml`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add src/main/java/com/magmaguy/freeminecraftmodels/menus/AdminContentMenu.java
git commit -m "feat: add AdminContentMenu — pack list browser for /fmm admin"
```

---

### Task 3: AdminModelListMenu — model list per pack (Level 2)

**Files:**
- Create: `src/main/java/com/magmaguy/freeminecraftmodels/menus/AdminModelListMenu.java`

**Step 1: Create AdminModelListMenu**

This class:
- Opens a 54-slot inventory titled "FMM Admin - {Pack Name}"
- Populates with model items from `ModelMenuHelper.getModelsForPack(pack)`
- Back button at slot 0 returns to AdminContentMenu
- Clicking a model gives the player a PAPER item with model_id PDC (via `ItemifyCommand.createModelItem` logic or cloning the display item)
- Pagination via prev/next

```java
package com.magmaguy.freeminecraftmodels.menus;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.commands.ItemifyCommand;
import com.magmaguy.freeminecraftmodels.content.FMMPackage;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.magmacore.util.ChatColorConverter;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

public class AdminModelListMenu {

    private static final HashMap<Inventory, AdminModelListMenu> activeMenus = new HashMap<>();

    private final Player player;
    private final FMMPackage pack;
    private final Inventory inventory;
    private final List<FileModelConverter> models;
    private int page;

    public AdminModelListMenu(Player player, FMMPackage pack) {
        this(player, pack, 0);
    }

    public AdminModelListMenu(Player player, FMMPackage pack, int page) {
        this.player = player;
        this.pack = pack;
        this.page = page;
        this.models = ModelMenuHelper.getModelsForPack(pack);

        String title = "&8FMM Admin - " + pack.getDisplayName();
        this.inventory = Bukkit.createInventory(null, 54, ChatColorConverter.convert(title));

        populate();
        activeMenus.put(inventory, this);
        player.openInventory(inventory);
    }

    private void populate() {
        inventory.clear();

        // Back button
        inventory.setItem(ModelMenuHelper.BACK_SLOT, ModelMenuHelper.buildBackItem());

        int startIndex = page * ModelMenuHelper.ITEMS_PER_PAGE;
        int endIndex = Math.min(startIndex + ModelMenuHelper.ITEMS_PER_PAGE, models.size());

        for (int i = 0; i < ModelMenuHelper.CONTENT_SLOTS.length && (startIndex + i) < endIndex; i++) {
            FileModelConverter converter = models.get(startIndex + i);
            inventory.setItem(ModelMenuHelper.CONTENT_SLOTS[i],
                    ModelMenuHelper.buildModelItem(converter, true));
        }

        // Navigation
        if (page > 0) {
            inventory.setItem(ModelMenuHelper.PREV_SLOT, ModelMenuHelper.buildNavItem("&ePrevious Page"));
        }
        if (endIndex < models.size()) {
            inventory.setItem(ModelMenuHelper.NEXT_SLOT, ModelMenuHelper.buildNavItem("&eNext Page"));
        }
    }

    private String getModelIdAtSlot(int slot) {
        int contentIndex = -1;
        for (int i = 0; i < ModelMenuHelper.CONTENT_SLOTS.length; i++) {
            if (ModelMenuHelper.CONTENT_SLOTS[i] == slot) {
                contentIndex = i;
                break;
            }
        }
        if (contentIndex < 0) return null;
        int modelIndex = page * ModelMenuHelper.ITEMS_PER_PAGE + contentIndex;
        if (modelIndex >= models.size()) return null;
        return models.get(modelIndex).getID();
    }

    public static void registerEvents(org.bukkit.plugin.Plugin plugin) {
        Bukkit.getPluginManager().registerEvents(new Events(), plugin);
    }

    public static class Events implements Listener {
        @EventHandler
        public void onClick(InventoryClickEvent event) {
            AdminModelListMenu menu = activeMenus.get(event.getInventory());
            if (menu == null) return;
            event.setCancelled(true);

            if (!(event.getWhoClicked() instanceof Player clicker)) return;
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || clicked.getType().isAir()) return;

            int slot = event.getRawSlot();
            if (slot >= event.getInventory().getSize()) return;

            // Back button
            if (slot == ModelMenuHelper.BACK_SLOT) {
                activeMenus.remove(menu.inventory);
                new AdminContentMenu(clicker);
                return;
            }

            // Pagination
            if (slot == ModelMenuHelper.PREV_SLOT && menu.page > 0) {
                menu.page--;
                menu.populate();
                return;
            }
            if (slot == ModelMenuHelper.NEXT_SLOT) {
                int maxPage = (menu.models.size() - 1) / ModelMenuHelper.ITEMS_PER_PAGE;
                if (menu.page < maxPage) {
                    menu.page++;
                    menu.populate();
                }
                return;
            }

            // Model click — give item to player
            String modelId = menu.getModelIdAtSlot(slot);
            if (modelId != null) {
                ItemStack giveItem = new ItemStack(Material.PAPER);
                ItemMeta meta = giveItem.getItemMeta();
                if (meta != null) {
                    meta.setDisplayName(ChatColorConverter.convert(
                            "&e\u2726 &6" + ItemifyCommand.formatModelName(modelId) + " &e\u2726"));
                    meta.setLore(List.of(
                            "",
                            ChatColorConverter.convert("&7Right-click on a block to place"),
                            ChatColorConverter.convert("&7Punch to pick back up"),
                            "",
                            ChatColorConverter.convert("&8Model: " + modelId)
                    ));
                    NamespacedKey key = new NamespacedKey(MetadataHandler.PLUGIN, "model_id");
                    meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, modelId);
                    giveItem.setItemMeta(meta);
                }
                clicker.getInventory().addItem(giveItem);
            }
        }

        @EventHandler
        public void onClose(InventoryCloseEvent event) {
            activeMenus.remove(event.getInventory());
        }
    }
}
```

**Step 2: Compile and verify**

Run: `mvn compile -f pom.xml`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add src/main/java/com/magmaguy/freeminecraftmodels/menus/AdminModelListMenu.java
git commit -m "feat: add AdminModelListMenu — per-pack model browser with click-to-get"
```

---

### Task 4: CraftableItemsMenu — player-facing craftable browser

**Files:**
- Create: `src/main/java/com/magmaguy/freeminecraftmodels/menus/CraftableItemsMenu.java`

**Step 1: Create CraftableItemsMenu**

This class:
- Opens a 54-slot inventory titled "FMM - Craftable Items"
- Shows all models that have a recipe in PropRecipeManager (across all packs)
- Uses `buildModelItem(converter, false)` — no "click to get", view-only
- Pagination

```java
package com.magmaguy.freeminecraftmodels.menus;

import com.magmaguy.freeminecraftmodels.config.recipes.PropRecipeManager;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.magmacore.util.ChatColorConverter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.stream.Collectors;

public class CraftableItemsMenu {

    private static final HashMap<Inventory, CraftableItemsMenu> activeMenus = new HashMap<>();

    private final Player player;
    private final Inventory inventory;
    private final List<FileModelConverter> craftableModels;
    private int page;

    public CraftableItemsMenu(Player player) {
        this(player, 0);
    }

    public CraftableItemsMenu(Player player, int page) {
        this.player = player;
        this.page = page;

        // Collect all models that have recipes
        Set<String> craftableIds = PropRecipeManager.getLoadedRecipes().keySet();
        this.craftableModels = FileModelConverter.getConvertedFileModels().values().stream()
                .filter(c -> craftableIds.contains(c.getID()))
                .sorted(Comparator.comparing(FileModelConverter::getID))
                .collect(Collectors.toList());

        this.inventory = Bukkit.createInventory(null, 54,
                ChatColorConverter.convert("&8FMM - Craftable Items"));

        populate();
        activeMenus.put(inventory, this);
        player.openInventory(inventory);
    }

    private void populate() {
        inventory.clear();

        int startIndex = page * ModelMenuHelper.ITEMS_PER_PAGE;
        int endIndex = Math.min(startIndex + ModelMenuHelper.ITEMS_PER_PAGE, craftableModels.size());

        for (int i = 0; i < ModelMenuHelper.CONTENT_SLOTS.length && (startIndex + i) < endIndex; i++) {
            FileModelConverter converter = craftableModels.get(startIndex + i);
            inventory.setItem(ModelMenuHelper.CONTENT_SLOTS[i],
                    ModelMenuHelper.buildModelItem(converter, false));
        }

        // Navigation
        if (page > 0) {
            inventory.setItem(ModelMenuHelper.PREV_SLOT, ModelMenuHelper.buildNavItem("&ePrevious Page"));
        }
        if (endIndex < craftableModels.size()) {
            inventory.setItem(ModelMenuHelper.NEXT_SLOT, ModelMenuHelper.buildNavItem("&eNext Page"));
        }
    }

    public static void registerEvents(org.bukkit.plugin.Plugin plugin) {
        Bukkit.getPluginManager().registerEvents(new Events(), plugin);
    }

    public static class Events implements Listener {
        @EventHandler
        public void onClick(InventoryClickEvent event) {
            CraftableItemsMenu menu = activeMenus.get(event.getInventory());
            if (menu == null) return;
            event.setCancelled(true);

            if (!(event.getWhoClicked() instanceof Player)) return;
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || clicked.getType().isAir()) return;

            int slot = event.getRawSlot();
            if (slot >= event.getInventory().getSize()) return;

            // Pagination only — no item actions
            if (slot == ModelMenuHelper.PREV_SLOT && menu.page > 0) {
                menu.page--;
                menu.populate();
                return;
            }
            if (slot == ModelMenuHelper.NEXT_SLOT) {
                int maxPage = (menu.craftableModels.size() - 1) / ModelMenuHelper.ITEMS_PER_PAGE;
                if (menu.page < maxPage) {
                    menu.page++;
                    menu.populate();
                }
            }
        }

        @EventHandler
        public void onClose(InventoryCloseEvent event) {
            activeMenus.remove(event.getInventory());
        }
    }
}
```

**Step 2: Compile and verify**

Run: `mvn compile -f pom.xml`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add src/main/java/com/magmaguy/freeminecraftmodels/menus/CraftableItemsMenu.java
git commit -m "feat: add CraftableItemsMenu — player-facing craftable item browser"
```

---

### Task 5: AdminCommand + wire FreeMinecraftModelsCommand + register listeners + permissions

**Files:**
- Create: `src/main/java/com/magmaguy/freeminecraftmodels/commands/AdminCommand.java`
- Modify: `src/main/java/com/magmaguy/freeminecraftmodels/commands/FreeMinecraftModelsCommand.java`
- Modify: `src/main/java/com/magmaguy/freeminecraftmodels/FreeMinecraftModels.java` (around line 160-183)
- Modify: `src/main/resources/plugin.yml`

**Step 1: Create AdminCommand**

```java
package com.magmaguy.freeminecraftmodels.commands;

import com.magmaguy.freeminecraftmodels.menus.AdminContentMenu;
import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.magmacore.command.SenderType;

import java.util.List;

public class AdminCommand extends AdvancedCommand {

    public AdminCommand() {
        super(List.of("admin"));
        setDescription("Opens the admin content browser");
        setPermission("freeminecraftmodels.admin");
        setUsage("/fmm admin");
        setSenderType(SenderType.PLAYER);
    }

    @Override
    public void execute(CommandData commandData) {
        new AdminContentMenu(commandData.getPlayerSender());
    }
}
```

**Step 2: Modify FreeMinecraftModelsCommand to open CraftableItemsMenu on bare `/fmm`**

Change the `execute` method in `FreeMinecraftModelsCommand.java` to:

```java
@Override
public void execute(CommandData commandData) {
    if (commandData.getCommandSender() instanceof org.bukkit.entity.Player player) {
        if (player.hasPermission("freeminecraftmodels.menu")) {
            new com.magmaguy.freeminecraftmodels.menus.CraftableItemsMenu(player);
            return;
        }
    }
    // Fallback for console or no permission
    Logger.sendMessage(commandData.getCommandSender(), "FreeMinecraftModels is a plugin that allows you to use Minecraft models in your world.");
    Logger.sendMessage(commandData.getCommandSender(), "Use &2/fmm setup &fto browse Nightbreak-managed model packs.");
    Logger.sendMessage(commandData.getCommandSender(), "Use &2/fmm initialize &ffor the first-time setup flow, or &2/fmm downloadall &fto install all available content.");
}
```

**Step 3: Register AdminCommand + menu listeners in FreeMinecraftModels.java**

After line 183 (`manager.registerCommand(new CraftifyCommand());`), add:

```java
manager.registerCommand(new AdminCommand());
```

After line 162 (`Bukkit.getPluginManager().registerEvents(new CraftifyListener(), this);`), add:

```java
AdminContentMenu.registerEvents(this);
AdminModelListMenu.registerEvents(this);
CraftableItemsMenu.registerEvents(this);
```

**Step 4: Update plugin.yml permissions**

Add to `plugin.yml`:

```yaml
permissions:
  freeminecraftmodels.*:
    description: Allows access to all FreeMinecraftModels commands
    default: op
  freeminecraftmodels.admin:
    description: Allows access to the admin content browser
    default: op
  freeminecraftmodels.menu:
    description: Allows access to the craftable items menu
    default: true
```

**Step 5: Compile and verify**

Run: `mvn compile -f pom.xml`
Expected: BUILD SUCCESS

**Step 6: Commit**

```bash
git add src/main/java/com/magmaguy/freeminecraftmodels/commands/AdminCommand.java
git add src/main/java/com/magmaguy/freeminecraftmodels/commands/FreeMinecraftModelsCommand.java
git add src/main/java/com/magmaguy/freeminecraftmodels/FreeMinecraftModels.java
git add src/main/resources/plugin.yml
git commit -m "feat: wire /fmm admin command, /fmm craftable menu, register listeners and permissions"
```

---

### Task 6: Fix PropRecipeConfig.getShape return type

**Files:**
- Modify: `src/main/java/com/magmaguy/freeminecraftmodels/config/recipes/PropRecipeConfig.java:94`

**Step 1: Fix the getter**

The current code at line 94:
```java
public String getShape() { return shape; }
```

This should be returning `List<String>` since `shape` is `List<String>`. Change to:
```java
public List<String> getShapeList() { return shape; }
```

Also check if `getShape()` is used anywhere else and update callers. The only caller is `registerRecipe()` at line 78 which already uses `shape` directly, not the getter. And `ModelMenuHelper.buildModelItem` will use `getShapeList()`.

**Step 2: Compile and verify**

Run: `mvn compile -f pom.xml`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add src/main/java/com/magmaguy/freeminecraftmodels/config/recipes/PropRecipeConfig.java
git commit -m "fix: PropRecipeConfig.getShape return type — rename to getShapeList returning List<String>"
```

---

### Summary of all changes

| File | Action | Purpose |
|------|--------|---------|
| `menus/ModelMenuHelper.java` | Create | Shared lore builder, model-pack mapping, slot constants |
| `menus/AdminContentMenu.java` | Create | Pack list menu (Level 1) |
| `menus/AdminModelListMenu.java` | Create | Model list per pack (Level 2) with click-to-get |
| `menus/CraftableItemsMenu.java` | Create | Player-facing craftable items browser |
| `commands/AdminCommand.java` | Create | `/fmm admin` subcommand |
| `commands/FreeMinecraftModelsCommand.java` | Modify | Open CraftableItemsMenu on bare `/fmm` |
| `FreeMinecraftModels.java` | Modify | Register AdminCommand + menu event listeners |
| `config/recipes/PropRecipeConfig.java` | Modify | Fix getShape return type |
| `plugin.yml` | Modify | Add permission nodes |
