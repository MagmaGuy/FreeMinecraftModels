package com.magmaguy.freeminecraftmodels.listeners;

import com.magmaguy.freeminecraftmodels.config.props.PropScriptConfigFields;
import com.magmaguy.freeminecraftmodels.customentity.PropEntity;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.freeminecraftmodels.dataconverter.HitboxBlueprint;
import com.magmaguy.magmacore.util.ChatColorConverter;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.GameMode;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.io.File;

public class ModelItemListener implements Listener {

    private static final NamespacedKey MODEL_ID_KEY = new NamespacedKey(com.magmaguy.freeminecraftmodels.MetadataHandler.PLUGIN, "model_id");
    private static final NamespacedKey ITEM_ID_KEY = new NamespacedKey(com.magmaguy.freeminecraftmodels.MetadataHandler.PLUGIN, "fmm_item_id");
    private static final java.util.Map<java.util.UUID, Long> placementCooldowns = new java.util.HashMap<>();
    private static final long PLACEMENT_COOLDOWN_MS = 500; // ~10 ticks

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        if (item == null || event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_AIR) {
            return;
        }

        // Check if this is a model placement item
        if (!item.hasItemMeta() || !item.getItemMeta().getPersistentDataContainer().has(MODEL_ID_KEY, PersistentDataType.STRING)) {
            return;
        }

        // Skip custom items — they use Lua scripts for right-click, not prop placement
        if (item.getItemMeta().getPersistentDataContainer().has(ITEM_ID_KEY, PersistentDataType.STRING)) {
            return;
        }

        // Cancel the event to prevent normal item usage
        event.setCancelled(true);

        // Placement cooldown to prevent accidental double-placement
        long now = System.currentTimeMillis();
        Long lastPlace = placementCooldowns.get(player.getUniqueId());
        if (lastPlace != null && (now - lastPlace) < PLACEMENT_COOLDOWN_MS) return;
        placementCooldowns.put(player.getUniqueId(), now);

        String modelID = item.getItemMeta().getPersistentDataContainer().get(MODEL_ID_KEY, PersistentDataType.STRING);

        // Verify the model still exists
        if (!FileModelConverter.getConvertedFileModels().containsKey(modelID)) {
            Logger.sendMessage(player, ChatColorConverter.convert("&cError: Model " + modelID + " no longer exists!"));
            return;
        }

        // Raycast to find the target block
        RayTraceResult rayTraceResult = player.rayTraceBlocks(5.0);
        if (rayTraceResult == null || rayTraceResult.getHitBlock() == null) {
            Logger.sendMessage(player, ChatColorConverter.convert("&cNo block found to place the model against!"));
            return;
        }

        Block targetBlock = rayTraceResult.getHitBlock();
        BlockFace hitFace = rayTraceResult.getHitBlockFace();

        if (hitFace == null) {
            Logger.sendMessage(player, ChatColorConverter.convert("&cCould not determine block face!"));
            return;
        }

        // Load prop config to check for voxelize mode
        PropScriptConfigFields propConfig = loadPropConfig(modelID);

        if (propConfig != null && propConfig.isVoxelize()) {
            // --- Voxelized grid-snapped placement ---
            Block adjacentBlock = targetBlock.getRelative(hitFace);

            // Calculate yaw FIRST so we can rotate the footprint
            Location tempLoc = adjacentBlock.getLocation().add(0.5, 0, 0.5);
            Vector toPlayer = player.getLocation().toVector().subtract(tempLoc.toVector()).normalize();
            float yaw = snap90(toPlayer);

            // Get hitbox for footprint calculation
            HitboxBlueprint hitbox = FileModelConverter.getConvertedFileModels().get(modelID)
                    .getSkeletonBlueprint().getHitbox();
            int[] footprint = calculateFootprint(hitbox);

            // Rotate footprint to match yaw — swap X and Z for 90°/270°
            int[] rotatedFootprint = rotateFootprint(footprint, yaw);

            // Calculate grid-aligned placement location using rotated footprint
            Location placementLocation = calculateVoxelizedLocation(adjacentBlock, rotatedFootprint, player.getLocation());

            // Validate space
            if (!hasSpaceForPlacement(placementLocation, rotatedFootprint)) {
                Logger.sendMessage(player, ChatColorConverter.convert("&cNot enough space to place this model!"));
                return;
            }

            placementLocation.setYaw(yaw);
            placementLocation.setPitch(0);

            // Place the model
            PropEntity staticEntity = PropEntity.spawnPropEntity(modelID, placementLocation);
            if (staticEntity != null) {
                if (player.getGameMode() != GameMode.CREATIVE) {
                    item.setAmount(item.getAmount() - 1);
                }
            } else {
                Logger.sendMessage(player, ChatColorConverter.convert("&cFailed to place model " + modelID + "!"));
            }
        } else {
            // --- Free-form placement (original behavior) ---
            Location placementLocation = getFaceCenterLocation(targetBlock, hitFace);

            Vector toPlayer = player.getLocation().toVector().subtract(placementLocation.toVector()).normalize();
            float yaw = calculateYawFromDirection(toPlayer);
            placementLocation.setYaw(yaw);
            placementLocation.setPitch(0);

            PropEntity staticEntity = PropEntity.spawnPropEntity(modelID, placementLocation);
            if (staticEntity != null) {
                if (player.getGameMode() != GameMode.CREATIVE) {
                    item.setAmount(item.getAmount() - 1);
                }
            } else {
                Logger.sendMessage(player, ChatColorConverter.convert("&cFailed to place model " + modelID + "!"));
            }
        }
    }

    /**
     * Loads the sibling YML config for a prop model.
     * Uses the same pattern as PropScriptManager: finds the FileModelConverter,
     * locates the sibling .yml file, and loads PropScriptConfigFields.
     *
     * @param modelID the model identifier
     * @return the loaded config, or null if no config exists
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

    /**
     * Calculates the block footprint of a model based on its hitbox dimensions.
     * Each dimension is rounded to at least 1 block.
     *
     * @param hitbox the model's hitbox blueprint (can be null, defaults to 1x1x1)
     * @return int array of {xBlocks, yBlocks, zBlocks}
     */
    private int[] calculateFootprint(HitboxBlueprint hitbox) {
        if (hitbox == null) return new int[]{1, 1, 1};
        int xBlocks = Math.max(1, (int) Math.round(hitbox.getWidthX()));
        int yBlocks = Math.max(1, (int) Math.round(hitbox.getHeight()));
        int zBlocks = Math.max(1, (int) Math.round(hitbox.getWidthZ()));
        return new int[]{xBlocks, yBlocks, zBlocks};
    }

    /**
     * Calculates a grid-aligned placement location for voxelized models.
     * For odd dimensions, the model is centered on the block (+0.5 offset).
     * For even dimensions, the model is offset toward the player.
     * Y always anchors at the bottom of the target block.
     *
     * @param targetBlock the adjacent block where placement starts
     * @param footprint   the block footprint {xBlocks, yBlocks, zBlocks}
     * @param playerLoc   the player's location (used for even-dimension offset direction)
     * @return the grid-aligned placement location
     */
    private Location calculateVoxelizedLocation(Block targetBlock, int[] footprint, Location playerLoc) {
        double x;
        double z;
        double y = targetBlock.getY();

        // X axis
        if (footprint[0] % 2 == 1) {
            // Odd: center on the block
            x = targetBlock.getX() + 0.5;
        } else {
            // Even: offset toward player
            x = targetBlock.getX() + (playerLoc.getX() < targetBlock.getX() + 0.5 ? 0.0 : 1.0);
        }

        // Z axis
        if (footprint[2] % 2 == 1) {
            // Odd: center on the block
            z = targetBlock.getZ() + 0.5;
        } else {
            // Even: offset toward player
            z = targetBlock.getZ() + (playerLoc.getZ() < targetBlock.getZ() + 0.5 ? 0.0 : 1.0);
        }

        return new Location(targetBlock.getWorld(), x, y, z);
    }

    /**
     * Checks whether there is enough space (non-solid blocks) at the placement location
     * for the given footprint.
     *
     * @param origin    the placement origin location
     * @param footprint the block footprint {xBlocks, yBlocks, zBlocks}
     * @return true if all blocks in the footprint are non-solid
     */
    private boolean hasSpaceForPlacement(Location origin, int[] footprint) {
        int startX = origin.getBlockX() - (footprint[0] / 2);
        int startY = origin.getBlockY();
        int startZ = origin.getBlockZ() - (footprint[2] / 2);

        for (int dx = 0; dx < footprint[0]; dx++) {
            for (int dy = 0; dy < footprint[1]; dy++) {
                for (int dz = 0; dz < footprint[2]; dz++) {
                    Block block = origin.getWorld().getBlockAt(startX + dx, startY + dy, startZ + dz);
                    if (block.getType().isSolid()) return false;
                }
            }
        }
        return true;
    }

    /**
     * Snaps a direction vector's yaw to the nearest 90-degree increment (0, 90, 180, 270).
     *
     * @param direction the direction vector to snap
     * @return the snapped yaw in degrees
     */
    /**
     * Rotates a footprint's X and Z dimensions based on yaw.
     * At 90° or 270°, X and Z are swapped. Y is unchanged.
     */
    private int[] rotateFootprint(int[] footprint, float yaw) {
        float normalizedYaw = yaw % 360;
        if (normalizedYaw < 0) normalizedYaw += 360;
        int rotation = Math.round(normalizedYaw / 90f) % 4;
        if (rotation == 1 || rotation == 3) {
            // 90° or 270° — swap X and Z
            return new int[]{footprint[2], footprint[1], footprint[0]};
        }
        return footprint;
    }

    private float snap90(Vector direction) {
        double yaw = Math.atan2(-direction.getX(), direction.getZ()) * 180.0 / Math.PI;
        yaw = Math.round(yaw / 90.0) * 90.0;
        if (yaw < 0) yaw += 360;
        return (float) yaw;
    }

    /**
     * Returns the center of the given face of a block.
     * For UP:    block + (0.5, 1.0, 0.5) — center of the top surface
     * For DOWN:  block + (0.5, 0.0, 0.5) — center of the bottom surface
     * For NORTH: block + (0.5, 0.5, 0.0) — center of the north face
     * For SOUTH: block + (0.5, 0.5, 1.0) — center of the south face
     * For WEST:  block + (0.0, 0.5, 0.5) — center of the west face
     * For EAST:  block + (1.0, 0.5, 0.5) — center of the east face
     */
    private Location getFaceCenterLocation(Block block, BlockFace face) {
        Location loc = block.getLocation();
        return switch (face) {
            case UP -> loc.add(0.5, 1.0, 0.5);
            case DOWN -> loc.add(0.5, 0.0, 0.5);
            case NORTH -> loc.add(0.5, 0.5, 0.0);
            case SOUTH -> loc.add(0.5, 0.5, 1.0);
            case WEST -> loc.add(0.0, 0.5, 0.5);
            case EAST -> loc.add(1.0, 0.5, 0.5);
            default -> loc.add(0.5, 1.0, 0.5);
        };
    }

    private float calculateYawFromDirection(Vector direction) {
        // Calculate yaw from direction vector
        double yaw = Math.atan2(-direction.getX(), direction.getZ()) * 180 / Math.PI;

        // Snap to 10-degree increments
        yaw = Math.round(yaw / 10.0) * 10.0;

        // Ensure yaw is positive
        if (yaw < 0) yaw += 360;

        return (float) yaw;
    }
}
