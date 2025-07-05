package com.magmaguy.freeminecraftmodels.customentity.core.components;

import com.magmaguy.freeminecraftmodels.config.DefaultConfig;
import com.magmaguy.freeminecraftmodels.config.props.PropBlocks;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class PropBlockComponent {
    public List<PropBlocks> propBlocks = new ArrayList<>();
    private ModeledEntity modeledEntity;

    public PropBlockComponent(ModeledEntity modeledEntity) {
        this.modeledEntity = modeledEntity;
    }

    /**
     * Sets the prop blocks for this entity. Prop blocks are the fake blocks that are shown to players when they are near the entity.
     * These blocks are not actually placed in the world.
     * The recommended use is to replace real blocks with either air or barriers, depending on your needs.
     * Vectors are relative to the entity's spawn location, and will soon be used in configuration files.
     * Locations are the absolute locations of the blocks, only used by the API.
     *
     * @param propBlocks
     */
    public void setPropBlocks(List<PropBlocks> propBlocks) {
        this.propBlocks = propBlocks;
        Location spawnLocation = modeledEntity.getSpawnLocation();
        for (Player player : spawnLocation.getWorld().getPlayers()) {
            if (spawnLocation.distanceSquared(player.getLocation()) < Math.pow(DefaultConfig.maxModelViewDistance, 2)) {
                showFakePropBlocksToPlayer(player);
            }
        }
    }

    /**
     * Shows the fake prop blocks to a player.
     *
     * @param player Player to show the prop blocks to.
     */
    public void showFakePropBlocksToPlayer(Player player) {
        for (PropBlocks propBlock : propBlocks) {
            Location finalLocation = propBlock.getProcessedLocation(modeledEntity.getSpawnLocation());
            player.sendBlockChange(finalLocation, propBlock.getMaterial().createBlockData());
        }
    }

    /**
     * Shows the fake prop blocks to all current entity viewers.
     */
    public void showFakePropBlocksToAllPlayers() {
        for (UUID viewer : modeledEntity.getSkeleton().getSkeletonWatchers().getViewers()) {
            Player player = Bukkit.getPlayer(viewer);
            if (player != null)
                showFakePropBlocksToPlayer(player);
        }
    }

    /**
     * Shows the real blocks to a player.
     *
     * @param player Player to show the real blocks to.
     */
    public void showRealBlocksToPlayer(Player player) {
        for (PropBlocks propBlock : propBlocks) {
            Location finalLocation = propBlock.getProcessedLocation(modeledEntity.getSpawnLocation());
            player.sendBlockChange(
                    finalLocation,
                    finalLocation.getBlock().getBlockData());
        }
    }

    /**
     * Shows the real blocks to all current entity viewers.
     */
    public void showRealBlocksToAllPlayers() {
        for (UUID viewer : modeledEntity.getSkeleton().getSkeletonWatchers().getViewers()) {
            Player player = Bukkit.getPlayer(viewer);
            if (player != null)
                showRealBlocksToPlayer(player);
        }
    }
}
