# Custom Display Model JSON — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Allow admins to place a Blockbench-exported `.json` model file next to a `.bbmodel` file so that the corresponding in-game item shows a custom 3D model instead of a plain paper icon, with fallback for servers <1.21.4 or missing JSON.

**Architecture:** During resource pack generation, detect sibling `.json` files, rewrite bare texture references to FMM namespace paths, copy them into the output as display models, and generate corresponding item model definitions. A new `ModelItemFactory` centralizes all item creation with a version-gated `setItemModel()` call. A `DisplayModelRegistry` tracks which models have display JSONs.

**Tech Stack:** Spigot API 1.21.4+ (`ItemMeta.setItemModel`), MagmaCore `VersionChecker`, Gson for JSON manipulation, existing `FileModelConverter` texture data.

---

### Task 1: Create DisplayModelRegistry

**Files:**
- Create: `src/main/java/com/magmaguy/freeminecraftmodels/config/DisplayModelRegistry.java`

**Step 1: Create the registry class**

A simple static `HashSet<String>` that tracks model IDs with display JSONs. Populated during resource pack generation, queried during item creation.

```java
package com.magmaguy.freeminecraftmodels.config;

import java.util.HashSet;
import java.util.Set;

/**
 * Tracks which model IDs have a sibling display .json file.
 * Populated during resource pack generation, queried when creating model ItemStacks.
 */
public final class DisplayModelRegistry {

    private static final Set<String> displayModels = new HashSet<>();

    private DisplayModelRegistry() {}

    public static void register(String modelId) {
        displayModels.add(modelId);
    }

    public static boolean hasDisplayModel(String modelId) {
        return displayModels.contains(modelId);
    }

    public static void shutdown() {
        displayModels.clear();
    }
}
```

**Step 2: Commit**

```bash
git add src/main/java/com/magmaguy/freeminecraftmodels/config/DisplayModelRegistry.java
git commit -m "feat: add DisplayModelRegistry for tracking models with display JSONs"
```

---

### Task 2: Create ModelItemFactory — centralized item creation

**Files:**
- Create: `src/main/java/com/magmaguy/freeminecraftmodels/utils/ModelItemFactory.java`

**Step 1: Create the factory class**

Moves item creation logic out of `ItemifyCommand`, `PropRecipeConfig`, and `AdminModelListMenu` into one place. Handles:
- Display name, lore, PDC (model_id)
- Version-gated `setItemModel()` when display model exists

```java
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

/**
 * Centralized factory for creating model placement items.
 * All code that needs to create an FMM model item should call this.
 */
public final class ModelItemFactory {

    private ModelItemFactory() {}

    /**
     * Creates a model placement item with the given material.
     * If server is 1.21.4+ and a display model JSON exists for this model,
     * sets the item_model component so the item renders with a custom 3D model.
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

        // Set custom item model if available and server supports it
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
```

**Step 2: Commit**

```bash
git add src/main/java/com/magmaguy/freeminecraftmodels/utils/ModelItemFactory.java
git commit -m "feat: add ModelItemFactory — centralized model item creation with display model support"
```

---

### Task 3: Migrate callers to ModelItemFactory

**Files:**
- Modify: `src/main/java/com/magmaguy/freeminecraftmodels/commands/ItemifyCommand.java`
- Modify: `src/main/java/com/magmaguy/freeminecraftmodels/config/recipes/PropRecipeConfig.java`
- Modify: `src/main/java/com/magmaguy/freeminecraftmodels/menus/AdminModelListMenu.java`
- Modify: `src/main/java/com/magmaguy/freeminecraftmodels/menus/ModelMenuHelper.java`

**Step 1: Update ItemifyCommand**

Replace the private `createModelItem` method and delegate to `ModelItemFactory`:

```java
// In execute():
ItemStack item = ModelItemFactory.createModelItem(modelID, material);
player.getInventory().addItem(item);
```

Remove the private `createModelItem` method entirely. Keep `formatModelName` as a delegate:

```java
public static String formatModelName(String modelID) {
    return ModelItemFactory.formatModelName(modelID);
}
```

Or better: update all callers of `ItemifyCommand.formatModelName()` to use `ModelItemFactory.formatModelName()` instead, then remove it from ItemifyCommand.

**Step 2: Update PropRecipeConfig.registerRecipe()**

Replace lines 62-76 (manual item creation) with:

```java
ItemStack output = ModelItemFactory.createModelItem(modelId, Material.PAPER);
```

Remove the import of `ItemifyCommand` if no longer needed.

**Step 3: Update AdminModelListMenu.handleClick()**

Replace lines 120-132 (manual item creation) with:

```java
ItemStack giveItem = ModelItemFactory.createModelItem(modelId, Material.PAPER);
player.getInventory().addItem(giveItem);
```

Remove unused imports (`ChatColorConverter`, `ItemifyCommand`, `Material`, `NamespacedKey`, `ItemMeta`, `PersistentDataType`).

**Step 4: Update ModelMenuHelper**

Change the import of `ItemifyCommand.formatModelName` to `ModelItemFactory.formatModelName` at line 129:

```java
String formattedName = ModelItemFactory.formatModelName(modelId);
```

**Step 5: Compile and verify**

Run: `mvn compile -q`
Expected: Only lombok warnings, no errors.

**Step 6: Commit**

```bash
git add src/main/java/com/magmaguy/freeminecraftmodels/commands/ItemifyCommand.java \
       src/main/java/com/magmaguy/freeminecraftmodels/config/recipes/PropRecipeConfig.java \
       src/main/java/com/magmaguy/freeminecraftmodels/menus/AdminModelListMenu.java \
       src/main/java/com/magmaguy/freeminecraftmodels/menus/ModelMenuHelper.java
git commit -m "refactor: migrate all item creation to ModelItemFactory"
```

---

### Task 4: Add display model processing to resource pack generation

**Files:**
- Modify: `src/main/java/com/magmaguy/freeminecraftmodels/config/ModelsFolder.java` (inside `newModelGeneration()`, after line 139)

**Step 1: Add display model detection and processing**

After the existing bone item definition generation loop (line 139), add a new block that:

1. For each `FileModelConverter` in `bbModelConverterList`, checks for a sibling `.json` file
2. Reads the JSON, rewrites bare texture references
3. Writes the display model to `output/.../models/display/{modelId}.json`
4. Writes the item definition to `output/.../items/display/{modelId}.json`
5. Registers in `DisplayModelRegistry`

Add this code after line 139 (after the closing `}` of the mapped models loop), before the closing `}` of `newModelGeneration()`:

```java
// --- Display model processing ---
// For each model, check for a sibling .json file (admin-provided Blockbench export)
// and generate the corresponding display model + item definition in the resource pack.
File displayModelsFolder = new File(MetadataHandler.PLUGIN.getDataFolder().getAbsolutePath() +
        File.separatorChar + "output" +
        File.separatorChar + "FreeMinecraftModels" +
        File.separatorChar + "assets" +
        File.separatorChar + "freeminecraftmodels" +
        File.separatorChar + "models" +
        File.separatorChar + "display");
if (!displayModelsFolder.exists()) displayModelsFolder.mkdirs();

File displayItemsFolder = new File(MetadataHandler.PLUGIN.getDataFolder().getAbsolutePath() +
        File.separatorChar + "output" +
        File.separatorChar + "FreeMinecraftModels" +
        File.separatorChar + "assets" +
        File.separatorChar + "freeminecraftmodels" +
        File.separatorChar + "items" +
        File.separatorChar + "display");
if (!displayItemsFolder.exists()) displayItemsFolder.mkdirs();

for (FileModelConverter converter : bbModelConverterList) {
    if (converter.getSourceFile() == null) continue;
    File sourceFile = converter.getSourceFile();
    String modelId = converter.getID();

    // Check for sibling .json
    String baseName = sourceFile.getName();
    int dotIndex = baseName.lastIndexOf('.');
    if (dotIndex > 0) baseName = baseName.substring(0, dotIndex);
    File displayJsonFile = new File(sourceFile.getParentFile(), baseName + ".json");
    if (!displayJsonFile.exists()) continue;

    try {
        // Read the admin-provided JSON
        String jsonContent = FileUtils.readFileToString(displayJsonFile, StandardCharsets.UTF_8);
        JsonObject displayModel = JsonParser.parseString(jsonContent).getAsJsonObject();

        // Rewrite bare texture references
        if (displayModel.has("textures")) {
            JsonObject textures = displayModel.getAsJsonObject("textures");
            // Build index-to-FMM-path mapping from the converter's parsed textures
            Map<String, String> textureMapping = new HashMap<>();
            if (converter.getSkeletonBlueprint() != null) {
                List<ParsedTexture> parsedTextures = converter.getSkeletonBlueprint().getParsedTextures();
                if (parsedTextures != null) {
                    for (ParsedTexture pt : parsedTextures) {
                        String fmmPath = "freeminecraftmodels:entity/" + modelId + "/" + pt.getFilename().replace(".png", "");
                        textureMapping.put("" + pt.getId(), fmmPath);
                    }
                }
            }

            for (Map.Entry<String, com.google.gson.JsonElement> entry : textures.entrySet()) {
                String value = entry.getValue().getAsString();
                // Only rewrite bare references (no namespace colon)
                if (!value.contains(":")) {
                    // Try to map by texture key (index)
                    String mapped = textureMapping.get(entry.getKey());
                    if (mapped != null) {
                        textures.addProperty(entry.getKey(), mapped);
                    } else if (!textureMapping.isEmpty()) {
                        // Fallback: use the first available texture
                        textures.addProperty(entry.getKey(), textureMapping.values().iterator().next());
                    }
                }
                // If it already contains ":", leave it alone
            }
        }

        // Write the rewritten model to output
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        FileUtils.writeStringToFile(
                new File(displayModelsFolder, modelId + ".json"),
                gson.toJson(displayModel), StandardCharsets.UTF_8);

        // Generate item model definition
        HashMap<String, Object> itemDef = new HashMap<>();
        HashMap<String, Object> modelObj = new HashMap<>();
        modelObj.put("type", "minecraft:model");
        modelObj.put("model", "freeminecraftmodels:display/" + modelId);
        itemDef.put("model", modelObj);
        FileUtils.writeStringToFile(
                new File(displayItemsFolder, modelId + ".json"),
                new Gson().toJson(itemDef), StandardCharsets.UTF_8);

        // Register
        DisplayModelRegistry.register(modelId);

    } catch (Exception e) {
        Logger.warn("Failed to process display model JSON for " + modelId + ": " + e.getMessage());
    }
}
```

**Step 2: Add required imports to ModelsFolder.java**

```java
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.magmaguy.freeminecraftmodels.dataconverter.ParsedTexture;
```

Note: `Gson`, `FileUtils`, `StandardCharsets`, `Logger` are already imported.

**Step 3: Expose parsed textures from SkeletonBlueprint**

Check if `SkeletonBlueprint` has a `getParsedTextures()` method. If not, it needs one. The textures are parsed in `FileModelConverter.generateFileTextures()` — the `parsedTextures` list exists there. We need to store it on the `FileModelConverter` or `SkeletonBlueprint` so the display model code can access it.

The simplest approach: add a `@Getter` field to `FileModelConverter`:

```java
@Getter
private List<ParsedTexture> parsedTextures;
```

And in the constructor, after `parsedTextures` is built (around line 284-287), assign it to the field:

```java
this.parsedTextures = parsedTextures;
```

Then in `ModelsFolder`, use `converter.getParsedTextures()` instead of going through `SkeletonBlueprint`.

**Step 4: Add DisplayModelRegistry.shutdown() to plugin disable**

In `FreeMinecraftModels.java`, in the shutdown sequence, add:

```java
DisplayModelRegistry.shutdown();
```

**Step 5: Compile and verify**

Run: `mvn compile -q`
Expected: Only lombok warnings, no errors.

**Step 6: Commit**

```bash
git add src/main/java/com/magmaguy/freeminecraftmodels/config/ModelsFolder.java \
       src/main/java/com/magmaguy/freeminecraftmodels/config/DisplayModelRegistry.java \
       src/main/java/com/magmaguy/freeminecraftmodels/dataconverter/FileModelConverter.java \
       src/main/java/com/magmaguy/freeminecraftmodels/FreeMinecraftModels.java
git commit -m "feat: process sibling display .json files during resource pack generation"
```

---

### Summary

| # | Task | Files | Purpose |
|---|------|-------|---------|
| 1 | DisplayModelRegistry | Create 1 | Static set tracking models with display JSONs |
| 2 | ModelItemFactory | Create 1 | Centralized item creation with version-gated `setItemModel()` |
| 3 | Migrate callers | Modify 4 | ItemifyCommand, PropRecipeConfig, AdminModelListMenu, ModelMenuHelper → use factory |
| 4 | Resource pack pipeline | Modify 3-4 | Detect sibling JSON, rewrite textures, generate display model + item def, register |

**Dependency chain:** 1 → 2 → 3 (parallel-safe with 4) → 4
