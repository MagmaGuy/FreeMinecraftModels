package com.magmaguy.freeminecraftmodels.listeners;

import com.magmaguy.freeminecraftmodels.customentity.PropEntity;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.GameMode;
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

public class ModelItemListener implements Listener {

    private static final NamespacedKey MODEL_ID_KEY = new NamespacedKey(com.magmaguy.freeminecraftmodels.MetadataHandler.PLUGIN, "model_id");
    private static final NamespacedKey ITEM_ID_KEY = new NamespacedKey(com.magmaguy.freeminecraftmodels.MetadataHandler.PLUGIN, "fmm_item_id");

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

        String modelID = item.getItemMeta().getPersistentDataContainer().get(MODEL_ID_KEY, PersistentDataType.STRING);

        // Verify the model still exists
        if (!FileModelConverter.getConvertedFileModels().containsKey(modelID)) {
            Logger.sendMessage(player, "§cError: Model " + modelID + " no longer exists!");
            return;
        }

        // Raycast to find the target block
        RayTraceResult rayTraceResult = player.rayTraceBlocks(5.0);
        if (rayTraceResult == null || rayTraceResult.getHitBlock() == null) {
            Logger.sendMessage(player, "§cNo block found to place the model against!");
            return;
        }

        Block targetBlock = rayTraceResult.getHitBlock();
        BlockFace hitFace = rayTraceResult.getHitBlockFace();

        if (hitFace == null) {
            Logger.sendMessage(player, "§cCould not determine block face!");
            return;
        }

        // Calculate placement location — center of the hit face surface
        // The model's bottom-center origin is placed at this point
        Location placementLocation = getFaceCenterLocation(targetBlock, hitFace);

        // Calculate the direction from the placement location to the player
        Vector toPlayer = player.getLocation().toVector().subtract(placementLocation.toVector()).normalize();

        // Calculate yaw to face the player (clamped to 90-degree increments)
        float yaw = calculateYawFromDirection(toPlayer);
        placementLocation.setYaw(yaw);
        placementLocation.setPitch(0);

        // Place the model
        PropEntity staticEntity = PropEntity.spawnPropEntity(modelID, placementLocation);
        if (staticEntity != null) {
            // Consume the item unless in creative mode
            if (player.getGameMode() != GameMode.CREATIVE) {
                item.setAmount(item.getAmount() - 1);
            }
        } else {
            Logger.sendMessage(player, "§cFailed to place model " + modelID + "!");
        }
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
