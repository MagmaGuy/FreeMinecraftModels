# Voxelize & Solidify Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add grid-snapped placement and packet-based barrier blocks to FMM props, configured via YML.

**Architecture:** Two new boolean config fields (`voxelize`, `solidify`) in `PropScriptConfigFields`. When `voxelize` is true, `ModelItemListener` snaps placement to 90° rotation and block-grid alignment based on the model's hitbox dimensions. When `solidify` is also true, `PropEntity` populates its `PropBlockComponent` with `BARRIER` blocks covering the footprint after spawn.

**Tech Stack:** Java, Bukkit API, FMM config system (Magmacore `CustomConfigFields`), `PropBlockComponent` for packet-only blocks.

---

### Task 1: Add config fields

**Files:**
- Modify: `src/main/java/com/magmaguy/freeminecraftmodels/config/props/PropScriptConfigFields.java`

**Step 1: Add fields and process them**

Add two new boolean fields to `PropScriptConfigFields`:

```java
// After the enchantments field (~line 36):
@Getter
private boolean voxelize = false;
@Getter
private boolean solidify = false;
```

Add processing in `processConfigFields()`:

```java
// After the enchantments line (~line 55):
this.voxelize = processBoolean("voxelize", voxelize, false, false);
this.solidify = processBoolean("solidify", solidify, false, false);
```

**Step 2: Commit**

```
feat: add voxelize and solidify config fields for props
```

---

### Task 2: Load and expose config from PropScriptManager to PropEntity

**Files:**
- Modify: `src/main/java/com/magmaguy/freeminecraftmodels/scripting/PropScriptManager.java`
- Modify: `src/main/java/com/magmaguy/freeminecraftmodels/customentity/PropEntity.java`

The config is currently loaded in `PropScriptManager.onPropSpawn()` but not exposed to `PropEntity`. We need PropEntity to know if it's voxelized/solidified.

**Step 1: Pass config to PropEntity**

In `PropScriptManager.onPropSpawn()`, after loading the config fields (~line 119), expose voxelize/solidify to the prop. Add a method call before the script loop:

```java
// After line 119 (configFields.processConfigFields()):
prop.setVoxelizeConfig(configFields.isVoxelize(), configFields.isSolidify());
```

**Step 2: Add fields to PropEntity**

In `PropEntity.java`, add:

```java
@Getter
private boolean voxelize = false;
@Getter
private boolean solidify = false;

public void setVoxelizeConfig(boolean voxelize, boolean solidify) {
    this.voxelize = voxelize;
    this.solidify = solidify && voxelize; // solidify requires voxelize
}
```

**Step 3: Commit**

```
feat: pipe voxelize/solidify config from YML to PropEntity
```

---

### Task 3: Refactor ModelItemListener for voxelized placement

**Files:**
- Modify: `src/main/java/com/magmaguy/freeminecraftmodels/listeners/ModelItemListener.java`

This is the main logic change. When the model's config has `voxelize: true`, placement changes from free-form to grid-snapped.

**Step 1: Load the YML config at placement time**

The listener already has the `modelID` and access to `FileModelConverter`. We need to load the sibling YML to check `voxelize`. Add a helper method:

```java
/**
 * Loads the prop config for a model, or null if none exists.
 */
private PropScriptConfigFields loadPropConfig(String modelID) {
    FileModelConverter converter = FileModelConverter.getConvertedFileModels().get(modelID);
    if (converter == null || converter.getSourceFile() == null) return null;

    File modelFile = converter.getSourceFile();
    String baseName = modelFile.getName();
    if (baseName.endsWith(".fmmodel")) baseName = baseName.substring(0, baseName.length() - 8);
    else if (baseName.endsWith(".bbmodel")) baseName = baseName.substring(0, baseName.length() - 8);
    File ymlFile = new File(modelFile.getParentFile(), baseName + ".yml");

    if (!ymlFile.exists()) return null;

    PropScriptConfigFields configFields = new PropScriptConfigFields(ymlFile.getName(), true);
    FileConfiguration fileConfig = YamlConfiguration.loadConfiguration(ymlFile);
    configFields.setFileConfiguration(fileConfig);
    configFields.setFile(ymlFile);
    configFields.processConfigFields();
    return configFields;
}
```

Add required imports:

```java
import com.magmaguy.freeminecraftmodels.config.props.PropScriptConfigFields;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.freeminecraftmodels.dataconverter.HitboxBlueprint;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
```

**Step 2: Add footprint calculation**

```java
/**
 * Calculates the block footprint (x, y, z block counts) from hitbox dimensions.
 * Rounds each axis: < 1.5 → 1, < 2.5 → 2, etc.
 */
private int[] calculateFootprint(HitboxBlueprint hitbox) {
    return new int[]{
            Math.max(1, (int) Math.round(hitbox.getWidthX())),
            Math.max(1, (int) Math.round(hitbox.getHeight())),
            Math.max(1, (int) Math.round(hitbox.getWidthZ()))
    };
}
```

**Step 3: Add voxelized placement location calculation**

```java
/**
 * Calculates grid-aligned placement location for a voxelized prop.
 * Odd dimensions center on the target block; even dimensions offset toward the player.
 *
 * @param targetBlock the air block adjacent to the clicked face
 * @param footprint   {xBlocks, yBlocks, zBlocks}
 * @param playerLoc   player's location for bias direction
 * @return the placement location (bottom-center of the prop)
 */
private Location calculateVoxelizedLocation(Block targetBlock, int[] footprint, Location playerLoc) {
    // Base: bottom-center of target block
    double baseX = targetBlock.getX();
    double baseY = targetBlock.getY();
    double baseZ = targetBlock.getZ();

    // For each horizontal axis: if even, offset 0.5 blocks toward player
    double placementX;
    if (footprint[0] % 2 == 1) {
        // Odd: center on block
        placementX = baseX + 0.5;
    } else {
        // Even: offset toward player
        placementX = (playerLoc.getX() > baseX + 0.5) ? baseX + 1.0 : baseX;
    }

    double placementZ;
    if (footprint[2] % 2 == 1) {
        // Odd: center on block
        placementZ = baseZ + 0.5;
    } else {
        // Even: offset toward player
        placementZ = (playerLoc.getZ() > baseZ + 0.5) ? baseZ + 1.0 : baseZ;
    }

    // Y always anchors at bottom
    double placementY = baseY;

    return new Location(targetBlock.getWorld(), placementX, placementY, placementZ);
}
```

**Step 4: Add space validation**

```java
/**
 * Checks if all blocks in the footprint are non-solid (enough space to place).
 *
 * @param origin    the bottom-corner block of the footprint
 * @param footprint {xBlocks, yBlocks, zBlocks}
 * @return true if there is enough space
 */
private boolean hasSpaceForPlacement(Location origin, int[] footprint) {
    int startX = origin.getBlockX();
    int startY = origin.getBlockY();
    int startZ = origin.getBlockZ();

    // For odd dimensions, the origin is centered so we need to offset
    int offsetX = -(footprint[0] / 2);
    int offsetZ = -(footprint[2] / 2);

    for (int x = 0; x < footprint[0]; x++) {
        for (int y = 0; y < footprint[1]; y++) {
            for (int z = 0; z < footprint[2]; z++) {
                Block block = origin.getWorld().getBlockAt(
                        startX + offsetX + x,
                        startY + y,
                        startZ + offsetZ + z);
                if (block.getType().isSolid()) {
                    return false;
                }
            }
        }
    }
    return true;
}
```

**Step 5: Modify the main onPlayerInteract method**

Replace the placement logic section (after raycast validation, ~line 72 onward) to branch on voxelize:

```java
// After getting modelID and verifying model exists:

PropScriptConfigFields propConfig = loadPropConfig(modelID);
boolean voxelize = propConfig != null && propConfig.isVoxelize();

Block targetBlock = rayTraceResult.getHitBlock();
BlockFace hitFace = rayTraceResult.getHitBlockFace();

if (hitFace == null) {
    Logger.sendMessage(player, ChatColorConverter.convert("&cCould not determine block face!"));
    return;
}

Location placementLocation;

if (voxelize) {
    // Get the air block adjacent to the clicked face
    Block adjacentBlock = targetBlock.getRelative(hitFace);

    // Calculate footprint from hitbox
    FileModelConverter converter = FileModelConverter.getConvertedFileModels().get(modelID);
    HitboxBlueprint hitbox = converter.getSkeletonBlueprint().getHitbox();
    int[] footprint;
    if (hitbox != null) {
        footprint = calculateFootprint(hitbox);
    } else {
        footprint = new int[]{1, 1, 1};
    }

    // Check space
    placementLocation = calculateVoxelizedLocation(adjacentBlock, footprint, player.getLocation());
    if (!hasSpaceForPlacement(placementLocation, footprint)) {
        Logger.sendMessage(player, ChatColorConverter.convert("&cNot enough space to place this here!"));
        return;
    }

    // Snap yaw to 90° increments
    Vector toPlayer = player.getLocation().toVector().subtract(placementLocation.toVector()).normalize();
    float yaw = snap90(toPlayer);
    placementLocation.setYaw(yaw);
    placementLocation.setPitch(0);
} else {
    // Original free-form placement
    placementLocation = getFaceCenterLocation(targetBlock, hitFace);
    Vector toPlayer = player.getLocation().toVector().subtract(placementLocation.toVector()).normalize();
    float yaw = calculateYawFromDirection(toPlayer);
    placementLocation.setYaw(yaw);
    placementLocation.setPitch(0);
}
```

**Step 6: Add 90-degree snap helper**

```java
private float snap90(Vector direction) {
    double yaw = Math.atan2(-direction.getX(), direction.getZ()) * 180 / Math.PI;
    yaw = Math.round(yaw / 90.0) * 90.0;
    if (yaw < 0) yaw += 360;
    return (float) yaw;
}
```

**Step 7: Add ChatColorConverter import**

```java
import com.magmaguy.magmacore.util.ChatColorConverter;
```

Also update the existing error messages to use `ChatColorConverter.convert()`.

**Step 8: Commit**

```
feat: implement voxelized grid-snapped placement for props
```

---

### Task 4: Implement solidify — barrier blocks via PropBlockComponent

**Files:**
- Modify: `src/main/java/com/magmaguy/freeminecraftmodels/customentity/PropEntity.java`

After a voxelized prop spawns and `setVoxelizeConfig` is called, if solidify is enabled, generate barrier blocks for the footprint.

**Step 1: Add solidify method to PropEntity**

```java
/**
 * Populates the prop block component with barrier blocks covering the voxelized footprint.
 * Must be called after the prop is spawned and has a valid location.
 */
public void applySolidify() {
    if (!solidify || !voxelize) return;

    FileModelConverter converter = FileModelConverter.getConvertedFileModels().get(getEntityID());
    if (converter == null) return;

    HitboxBlueprint hitbox = converter.getSkeletonBlueprint().getHitbox();
    int footX, footY, footZ;
    if (hitbox != null) {
        footX = Math.max(1, (int) Math.round(hitbox.getWidthX()));
        footY = Math.max(1, (int) Math.round(hitbox.getHeight()));
        footZ = Math.max(1, (int) Math.round(hitbox.getWidthZ()));
    } else {
        footX = footY = footZ = 1;
    }

    List<PropBlocks> barriers = new ArrayList<>();
    int offsetX = -(footX / 2);
    int offsetZ = -(footZ / 2);

    for (int x = 0; x < footX; x++) {
        for (int y = 0; y < footY; y++) {
            for (int z = 0; z < footZ; z++) {
                barriers.add(new PropBlocks(
                        new org.bukkit.util.Vector(offsetX + x, y, offsetZ + z),
                        Material.BARRIER
                ));
            }
        }
    }

    setPropBlocks(barriers);
}
```

Add imports:

```java
import com.magmaguy.freeminecraftmodels.config.props.PropBlocks;
import com.magmaguy.freeminecraftmodels.dataconverter.HitboxBlueprint;
import java.util.ArrayList;
```

**Step 2: Call applySolidify at the right time**

In `PropScriptManager.onPropSpawn()`, after calling `prop.setVoxelizeConfig(...)`, call `prop.applySolidify()`:

```java
prop.setVoxelizeConfig(configFields.isVoxelize(), configFields.isSolidify());
prop.applySolidify();
```

**Step 3: Commit**

```
feat: implement solidify — packet-only barrier blocks for voxelized props
```

---

### Task 5: Handle even-dimension offset in solidify barriers

**Note:** The barrier block offsets need to match the placement offset logic from Task 3. For even dimensions, the prop's origin is at a block edge, not centered. The `Vector` offsets in `applySolidify` must account for this.

For odd dimensions: offset starts at `-(dim/2)`, e.g. for 3: offsets are -1, 0, 1.
For even dimensions: the prop origin is at a block edge. If footprint is 2 on X, and origin is at the edge, offsets are 0, 1 (or -1, 0 depending on direction). Since we don't know the player direction at solidify time, even dimensions always use offsets 0..dim-1 with a -0.5 shift... but that doesn't work for block coords.

**Resolution:** For even dimensions, the placement location already accounts for the bias (it's at a block edge). So the barrier offsets should be:
- Even: `0, 1, ..., dim-1` (extending from the origin outward)
- Odd: `-(dim/2), ..., 0, ..., +(dim/2)` (centered on origin)

This is already handled by the `offsetX = -(footX / 2)` calculation in Task 4 — for even numbers, `footX/2` gives the correct start since integer division truncates. For footX=2: offset starts at -1, covers -1 and 0. This matches the placement logic.

Review and adjust if testing reveals misalignment.

**Step 1: Commit if any adjustments needed**

```
fix: align solidify barrier offsets with voxelized placement grid
```

---

### Task 6: Test with furniture pack models

**Manual testing checklist:**

1. Add `voxelize: true` and `solidify: true` to `FMM_BFP_Closet.yml`
2. Place the closet — verify it snaps to 90° facing you and aligns to block grid
3. Verify you can't walk through it (client-side barrier collision)
4. Break it — verify barriers disappear
5. Try placing in a tight space — verify "not enough space" message
6. Test with a 1x1x1 model (chair) — verify it still works normally
7. Test without voxelize — verify original free-form placement still works

**Step 1: Commit test configs**

```
test: add voxelize/solidify to closet config for testing
```

---

### Task 7: Clean up and final commit

Remove any leftover debug logging. Verify all player-facing messages use `ChatColorConverter.convert()`.

```
chore: clean up voxelize/solidify implementation
```
