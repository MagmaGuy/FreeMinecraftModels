# Prop Recipe Builder Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Let admins create crafting recipes for prop placement items via an interactive in-game crafting UI, saved as YAML and registered as Bukkit recipes.

**Architecture:** A `/fmm craftify <model_id>` command opens a custom inventory mimicking a crafting table. The admin arranges ingredients, clicks the pre-filled output item to confirm. On confirm: particle effect, save to `recipes/<model_id>.yml`, register the ShapedRecipe with Bukkit. On escape/close without clicking output: discard, no save. On startup, all saved recipe YAMLs are loaded and registered.

**Tech Stack:** Bukkit Inventory API, ShapedRecipe API, Bukkit NamespacedKey, Magmacore AdvancedCommand, YAML via Bukkit FileConfiguration.

---

### Task 1: PropRecipeConfig — YAML recipe loader

**Files:**
- Create: `src/main/java/com/magmaguy/freeminecraftmodels/config/recipes/PropRecipeConfig.java`

**Step 1: Create the config class**

This class handles reading/writing individual recipe YAML files from the `recipes/` folder.

```java
package com.magmaguy.freeminecraftmodels.config.recipes;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.commands.ItemifyCommand;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class PropRecipeConfig {

    private final String modelId;
    private final List<String> shape;
    private final Map<Character, Material> ingredients;

    public PropRecipeConfig(String modelId, List<String> shape, Map<Character, Material> ingredients) {
        this.modelId = modelId;
        this.shape = shape;
        this.ingredients = ingredients;
    }

    /**
     * Saves this recipe to a YAML file in the recipes folder.
     */
    public void save(File recipesFolder) throws IOException {
        if (!recipesFolder.exists()) recipesFolder.mkdirs();
        File file = new File(recipesFolder, modelId + ".yml");
        YamlConfiguration config = new YamlConfiguration();
        config.set("model_id", modelId);
        config.set("shape", shape);
        for (Map.Entry<Character, Material> entry : ingredients.entrySet()) {
            config.set("ingredients." + entry.getKey(), entry.getValue().name());
        }
        config.save(file);
    }

    /**
     * Loads a recipe from a YAML file.
     */
    public static PropRecipeConfig load(File file) {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        String modelId = config.getString("model_id");
        if (modelId == null) return null;
        List<String> shape = config.getStringList("shape");
        if (shape.isEmpty()) return null;
        Map<Character, Material> ingredients = new LinkedHashMap<>();
        if (config.getConfigurationSection("ingredients") != null) {
            for (String key : config.getConfigurationSection("ingredients").getKeys(false)) {
                Material mat = Material.matchMaterial(config.getString("ingredients." + key));
                if (mat != null) {
                    ingredients.put(key.charAt(0), mat);
                }
            }
        }
        return new PropRecipeConfig(modelId, shape, ingredients);
    }

    /**
     * Creates and registers a Bukkit ShapedRecipe from this config.
     */
    public ShapedRecipe registerRecipe() {
        NamespacedKey recipeKey = new NamespacedKey(MetadataHandler.PLUGIN, "prop_recipe_" + modelId);

        // Build the output item (same as ItemifyCommand / LuaPropTable pickup)
        ItemStack output = new ItemStack(Material.PAPER);
        ItemMeta meta = output.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("\u00a7e\u2726 \u00a76" + ItemifyCommand.formatModelName(modelId) + " \u00a7e\u2726");
            meta.setLore(List.of(
                    "",
                    "\u00a77Right-click on a block to place",
                    "\u00a77Punch to pick back up",
                    "",
                    "\u00a78Model: " + modelId
            ));
            NamespacedKey modelKey = new NamespacedKey(MetadataHandler.PLUGIN, "model_id");
            meta.getPersistentDataContainer().set(modelKey, PersistentDataType.STRING, modelId);
            output.setItemMeta(meta);
        }

        ShapedRecipe recipe = new ShapedRecipe(recipeKey, output);
        recipe.shape(shape.toArray(new String[0]));
        for (Map.Entry<Character, Material> entry : ingredients.entrySet()) {
            recipe.setIngredient(entry.getKey(), entry.getValue());
        }

        // Remove existing recipe with same key if reloading
        Bukkit.removeRecipe(recipeKey);
        Bukkit.addRecipe(recipe);
        return recipe;
    }

    /**
     * Unregisters this recipe from Bukkit.
     */
    public void unregisterRecipe() {
        NamespacedKey recipeKey = new NamespacedKey(MetadataHandler.PLUGIN, "prop_recipe_" + modelId);
        Bukkit.removeRecipe(recipeKey);
    }

    public String getModelId() { return modelId; }
    public List<String> getShape() { return shape; }
    public Map<Character, Material> getIngredients() { return ingredients; }
}
```

**Step 2: Compile to verify**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

**Step 3: Commit**

```
feat: add PropRecipeConfig for YAML recipe persistence and Bukkit registration
```

---

### Task 2: PropRecipeManager — startup loading and lifecycle

**Files:**
- Create: `src/main/java/com/magmaguy/freeminecraftmodels/config/recipes/PropRecipeManager.java`
- Modify: `src/main/java/com/magmaguy/freeminecraftmodels/FreeMinecraftModels.java` (syncInitialization)

**Step 1: Create the manager**

```java
package com.magmaguy.freeminecraftmodels.config.recipes;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.magmacore.util.Logger;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class PropRecipeManager {

    private static final Map<String, PropRecipeConfig> loadedRecipes = new HashMap<>();

    /**
     * Loads all recipe YAML files from the recipes folder and registers them as Bukkit recipes.
     */
    public static void initialize() {
        loadedRecipes.clear();
        File recipesFolder = getRecipesFolder();
        if (!recipesFolder.exists()) {
            recipesFolder.mkdirs();
            return;
        }
        File[] files = recipesFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) return;
        for (File file : files) {
            PropRecipeConfig config = PropRecipeConfig.load(file);
            if (config == null) {
                Logger.warn("Failed to load recipe from " + file.getName());
                continue;
            }
            try {
                config.registerRecipe();
                loadedRecipes.put(config.getModelId(), config);
            } catch (Exception e) {
                Logger.warn("Failed to register recipe for " + config.getModelId() + ": " + e.getMessage());
            }
        }
        if (!loadedRecipes.isEmpty()) {
            Logger.info("Loaded " + loadedRecipes.size() + " prop recipe(s).");
        }
    }

    /**
     * Unregisters all loaded recipes and clears the cache.
     */
    public static void shutdown() {
        for (PropRecipeConfig config : loadedRecipes.values()) {
            config.unregisterRecipe();
        }
        loadedRecipes.clear();
    }

    /**
     * Saves and registers a new recipe, replacing any existing one for the same model.
     */
    public static void addRecipe(PropRecipeConfig config) {
        PropRecipeConfig existing = loadedRecipes.get(config.getModelId());
        if (existing != null) {
            existing.unregisterRecipe();
        }
        try {
            config.save(getRecipesFolder());
            config.registerRecipe();
            loadedRecipes.put(config.getModelId(), config);
        } catch (Exception e) {
            Logger.warn("Failed to save/register recipe for " + config.getModelId() + ": " + e.getMessage());
        }
    }

    public static File getRecipesFolder() {
        return new File(MetadataHandler.PLUGIN.getDataFolder(), "recipes");
    }

    public static Map<String, PropRecipeConfig> getLoadedRecipes() {
        return loadedRecipes;
    }
}
```

**Step 2: Register in FreeMinecraftModels.java**

In `syncInitialization()`, after the "Prop Scripting" step block (~line 197), add:

```java
initializationContext.step("Prop Recipes");
PropRecipeManager.initialize();
```

In `onDisable()` (~line 128), add before existing cleanup:

```java
PropRecipeManager.shutdown();
```

**Step 3: Compile to verify**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

**Step 4: Commit**

```
feat: add PropRecipeManager for loading/registering recipes on startup
```

---

### Task 3: CraftifyCommand — opens the recipe builder UI

**Files:**
- Create: `src/main/java/com/magmaguy/freeminecraftmodels/commands/CraftifyCommand.java`
- Modify: `src/main/java/com/magmaguy/freeminecraftmodels/FreeMinecraftModels.java` (register command)

**Step 1: Create the command**

```java
package com.magmaguy.freeminecraftmodels.commands;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.freeminecraftmodels.listeners.CraftifyListener;
import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.magmacore.command.SenderType;
import com.magmaguy.magmacore.command.arguments.ListStringCommandArgument;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

public class CraftifyCommand extends AdvancedCommand {

    // Inventory layout (54-slot chest = 6 rows x 9 cols):
    // Row 0: all glass border
    // Row 1: glass | slot | slot | slot | glass | glass | glass | glass | glass
    // Row 2: glass | slot | slot | slot | glass | glass | OUTPUT | glass | glass
    // Row 3: glass | slot | slot | slot | glass | glass | glass | glass | glass
    // Row 4: all glass border
    // Row 5: glass | glass | glass | glass | INSTRUCTIONS item | glass | glass | glass | glass

    // 3x3 crafting grid slot indices (row 1-3, col 1-3)
    public static final int[] GRID_SLOTS = {10, 11, 12, 19, 20, 21, 28, 29, 30};
    // Output slot (row 2, col 6)
    public static final int OUTPUT_SLOT = 24;

    public CraftifyCommand() {
        super(List.of("craftify"));
        List<String> entityIDs = new ArrayList<>();
        FileModelConverter.getConvertedFileModels().values().forEach(f -> entityIDs.add(f.getID()));
        addArgument("model", new ListStringCommandArgument(entityIDs, "<model>"));
        setDescription("Opens an interactive recipe builder for a model prop");
        setPermission("freeminecraftmodels.*");
        setUsage("/fmm craftify <model>");
        setSenderType(SenderType.PLAYER);
    }

    @Override
    public void execute(CommandData commandData) {
        Player player = commandData.getPlayerSender();
        String modelID = commandData.getStringArgument("model");

        if (!FileModelConverter.getConvertedFileModels().containsKey(modelID)) {
            Logger.sendMessage(player, "\u00a7cModel '" + modelID + "' not found!");
            return;
        }

        // Build the inventory
        String title = "\u00a78\u2726 Recipe Builder: \u00a76" + ItemifyCommand.formatModelName(modelID);
        Inventory inv = Bukkit.createInventory(null, 54, title);

        // Fill with glass panes
        ItemStack border = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta borderMeta = border.getItemMeta();
        if (borderMeta != null) {
            borderMeta.setDisplayName(" ");
            border.setItemMeta(borderMeta);
        }
        for (int i = 0; i < 54; i++) {
            inv.setItem(i, border);
        }

        // Clear the 3x3 grid slots
        for (int slot : GRID_SLOTS) {
            inv.setItem(slot, null);
        }

        // Place the output item
        ItemStack output = new ItemStack(Material.PAPER);
        ItemMeta outputMeta = output.getItemMeta();
        if (outputMeta != null) {
            outputMeta.setDisplayName("\u00a7e\u2726 \u00a76" + ItemifyCommand.formatModelName(modelID) + " \u00a7e\u2726");
            outputMeta.setLore(List.of(
                    "",
                    "\u00a7a\u25b6 Click to save recipe",
                    "",
                    "\u00a77Place ingredients in the grid,",
                    "\u00a77then click here to confirm.",
                    "",
                    "\u00a78Model: " + modelID
            ));
            NamespacedKey modelKey = new NamespacedKey(MetadataHandler.PLUGIN, "model_id");
            outputMeta.getPersistentDataContainer().set(modelKey, PersistentDataType.STRING, modelID);
            // Tag to identify this as a craftify output
            NamespacedKey craftifyKey = new NamespacedKey(MetadataHandler.PLUGIN, "craftify_output");
            outputMeta.getPersistentDataContainer().set(craftifyKey, PersistentDataType.BYTE, (byte) 1);
            output.setItemMeta(outputMeta);
        }
        inv.setItem(OUTPUT_SLOT, output);

        // Place instruction item
        ItemStack instructions = new ItemStack(Material.WRITABLE_BOOK);
        ItemMeta instrMeta = instructions.getItemMeta();
        if (instrMeta != null) {
            instrMeta.setDisplayName("\u00a7e\u00a7lRecipe Builder");
            instrMeta.setLore(List.of(
                    "",
                    "\u00a7f1. \u00a77Place ingredients in the 3\u00d73 grid",
                    "\u00a7f2. \u00a77Arrange them in the pattern you want",
                    "\u00a7f3. \u00a77Click the \u00a7aoutput item \u00a77to save",
                    "",
                    "\u00a77Press \u00a7fEsc \u00a77to cancel without saving"
            ));
            instructions.setItemMeta(instrMeta);
        }
        inv.setItem(49, instructions);

        // Track this player's craftify session
        CraftifyListener.startSession(player, modelID);

        player.openInventory(inv);
    }
}
```

**Step 2: Register the command in `FreeMinecraftModels.java`**

After the `ItemifyCommand` registration line (~line 178), add:

```java
manager.registerCommand(new CraftifyCommand());
```

**Step 3: Compile (will fail — CraftifyListener doesn't exist yet, that's Task 4)**

Skip compile until Task 4.

**Step 4: Commit (combined with Task 4)**

---

### Task 4: CraftifyListener — handles UI interaction, saving, and cleanup

**Files:**
- Create: `src/main/java/com/magmaguy/freeminecraftmodels/listeners/CraftifyListener.java`
- Modify: `src/main/java/com/magmaguy/freeminecraftmodels/FreeMinecraftModels.java` (register listener)

**Step 1: Create the listener**

```java
package com.magmaguy.freeminecraftmodels.listeners;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.commands.CraftifyCommand;
import com.magmaguy.freeminecraftmodels.config.recipes.PropRecipeConfig;
import com.magmaguy.freeminecraftmodels.config.recipes.PropRecipeManager;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

public class CraftifyListener implements Listener {

    private static final NamespacedKey CRAFTIFY_KEY = new NamespacedKey(MetadataHandler.PLUGIN, "craftify_output");
    private static final Map<UUID, String> activeSessions = new HashMap<>();

    public static void startSession(Player player, String modelId) {
        activeSessions.put(player.getUniqueId(), modelId);
    }

    private static boolean isInSession(Player player) {
        return activeSessions.containsKey(player.getUniqueId());
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!isInSession(player)) return;

        int slot = event.getRawSlot();

        // Click is in the craftify inventory (top inventory, slots 0-53)
        if (slot >= 0 && slot < 54) {
            // Allow interaction with grid slots
            boolean isGridSlot = false;
            for (int gridSlot : CraftifyCommand.GRID_SLOTS) {
                if (slot == gridSlot) {
                    isGridSlot = true;
                    break;
                }
            }

            if (isGridSlot) {
                // Allow normal item placement/pickup in grid
                return;
            }

            // Click on output slot — save the recipe
            if (slot == CraftifyCommand.OUTPUT_SLOT) {
                event.setCancelled(true);
                handleSave(player, event.getInventory());
                return;
            }

            // All other top-inventory slots are borders — cancel
            event.setCancelled(true);
            return;
        }

        // Clicks in player inventory (bottom) are fine — allow picking up items to place in grid
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!isInSession(player)) return;

        // Only allow drags that land exclusively on grid slots
        Set<Integer> gridSet = new HashSet<>();
        for (int s : CraftifyCommand.GRID_SLOTS) gridSet.add(s);

        for (int slot : event.getRawSlots()) {
            if (slot >= 0 && slot < 54 && !gridSet.contains(slot)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (!isInSession(player)) return;

        // Return any items left in the grid to the player
        Inventory inv = event.getInventory();
        for (int slot : CraftifyCommand.GRID_SLOTS) {
            ItemStack item = inv.getItem(slot);
            if (item != null && item.getType() != Material.AIR) {
                HashMap<Integer, ItemStack> overflow = player.getInventory().addItem(item);
                overflow.values().forEach(leftover ->
                        player.getWorld().dropItemNaturally(player.getLocation(), leftover));
                inv.setItem(slot, null);
            }
        }

        activeSessions.remove(player.getUniqueId());
    }

    private void handleSave(Player player, Inventory inv) {
        String modelId = activeSessions.get(player.getUniqueId());
        if (modelId == null) return;

        // Read the 3x3 grid
        ItemStack[] grid = new ItemStack[9];
        boolean hasAnyIngredient = false;
        for (int i = 0; i < 9; i++) {
            grid[i] = inv.getItem(CraftifyCommand.GRID_SLOTS[i]);
            if (grid[i] != null && grid[i].getType() != Material.AIR) {
                hasAnyIngredient = true;
            }
        }

        if (!hasAnyIngredient) {
            Logger.sendMessage(player, "\u00a7cPlace at least one ingredient in the grid first!");
            return;
        }

        // Build the shape and ingredient map
        // Assign letters to distinct materials
        Map<Material, Character> materialToChar = new LinkedHashMap<>();
        char nextChar = 'A';
        char[][] charGrid = new char[3][3];

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                ItemStack item = grid[row * 3 + col];
                if (item == null || item.getType() == Material.AIR) {
                    charGrid[row][col] = ' ';
                } else {
                    Material mat = item.getType();
                    if (!materialToChar.containsKey(mat)) {
                        materialToChar.put(mat, nextChar++);
                    }
                    charGrid[row][col] = materialToChar.get(mat);
                }
            }
        }

        // Trim the shape to the minimal bounding box
        int minRow = 3, maxRow = -1, minCol = 3, maxCol = -1;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                if (charGrid[row][col] != ' ') {
                    minRow = Math.min(minRow, row);
                    maxRow = Math.max(maxRow, row);
                    minCol = Math.min(minCol, col);
                    maxCol = Math.max(maxCol, col);
                }
            }
        }

        List<String> shape = new ArrayList<>();
        for (int row = minRow; row <= maxRow; row++) {
            StringBuilder sb = new StringBuilder();
            for (int col = minCol; col <= maxCol; col++) {
                sb.append(charGrid[row][col]);
            }
            shape.add(sb.toString());
        }

        Map<Character, Material> ingredients = new LinkedHashMap<>();
        for (Map.Entry<Material, Character> entry : materialToChar.entrySet()) {
            ingredients.put(entry.getValue(), entry.getKey());
        }

        // Save and register
        PropRecipeConfig config = new PropRecipeConfig(modelId, shape, ingredients);
        PropRecipeManager.addRecipe(config);

        // Visual feedback
        player.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, player.getLocation().add(0, 1, 0), 30, 0.5, 0.5, 0.5);
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.2f);
        Logger.sendMessage(player, "\u00a7a\u2714 Recipe saved for \u00a7e" + modelId + "\u00a7a!");

        // Clear grid items (they are consumed as the recipe cost)
        for (int slot : CraftifyCommand.GRID_SLOTS) {
            inv.setItem(slot, null);
        }

        // Close inventory
        player.closeInventory();
    }
}
```

**Step 2: Register the listener in `FreeMinecraftModels.java`**

In `syncInitialization()`, after the ModelItemListener registration (~line 158), add:

```java
Bukkit.getPluginManager().registerEvents(new CraftifyListener(), this);
```

**Step 3: Compile**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

**Step 4: Commit**

```
feat: add /fmm craftify command with interactive recipe builder UI
```

---

### Task 5: Wire up shutdown and reload support

**Files:**
- Modify: `src/main/java/com/magmaguy/freeminecraftmodels/FreeMinecraftModels.java`

**Step 1: Add shutdown call**

In `onDisable()`, before existing cleanup, add:

```java
PropRecipeManager.shutdown();
```

**Step 2: Handle reload**

Check `ReloadCommand.java` — if it calls `onDisable` + `onEnable` style logic, recipes will be covered. If not, add `PropRecipeManager.shutdown(); PropRecipeManager.initialize();` to the reload path.

**Step 3: Compile and verify**

Run: `mvn package -q`
Expected: BUILD SUCCESS

**Step 4: Commit**

```
feat: add recipe shutdown/reload support
```

---

### Task 6: Manual testing checklist

1. Start server, verify `recipes/` folder is created
2. Run `/fmm craftify <model_id>` — verify UI opens with glass borders, empty 3x3 grid, output item, and instruction book
3. Place items in grid, click output — verify particles, sound, chat message, inventory closes
4. Check `recipes/<model_id>.yml` — verify shape and ingredients are correct
5. Open a crafting table — verify the recipe works and produces the prop item
6. Right-click the crafted item on a block — verify prop places
7. Restart server — verify recipe persists and still works
8. Run `/fmm craftify` with same model — verify it overwrites the old recipe
9. Open UI, press Esc without clicking output — verify no recipe saved, items returned to inventory
10. Test with empty grid + click output — verify error message
