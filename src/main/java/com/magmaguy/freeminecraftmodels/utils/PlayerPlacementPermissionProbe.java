package com.magmaguy.freeminecraftmodels.utils;

import com.magmaguy.magmacore.location.LocationQueryRegistry;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;

import java.util.function.Predicate;

/** Non-mutating, player-aware bridge to MagmaCore's protection providers. */
public final class PlayerPlacementPermissionProbe {
    private PlayerPlacementPermissionProbe() {
    }

    public static boolean canPlace(Player player, Block placedAgainst, BlockFace face) {
        if (player == null || placedAgainst == null || face == null) return false;
        Location target = placedAgainst.getRelative(face).getLocation();
        if (target.getWorld() == null) return false;
        return LocationQueryRegistry.canBuild(player, target);
    }

    /**
     * Checks every block cell occupied by a voxelized prop. The origin and
     * footprint math intentionally matches ModelItemListener's placement space
     * check. Note that PropEntity.applySolidify derives its dimensions with
     * different rounding (ceil(size - 0.4) vs ModelItemListener's Math.round),
     * so the solidified barrier volume can differ from this checked volume.
     */
    public static boolean canPlaceVolume(Player player,
                                         Location origin,
                                         int width,
                                         int height,
                                         int depth) {
        if (player == null || origin == null || origin.getWorld() == null
                || width <= 0 || height <= 0 || depth <= 0) {
            return false;
        }
        return forEachFootprintCell(origin, width, height, depth,
                target -> LocationQueryRegistry.canBuild(player, target));
    }

    /**
     * Iterates every block cell of a prop footprint anchored at {@code origin},
     * applying {@code cellOk} to each cell location in x → y → z order. The
     * start offsets ({@code blockX - width / 2}, etc.) match ModelItemListener's
     * placement space check (not PropEntity.applySolidify, which computes its
     * dimensions with different rounding).
     *
     * @return true if every cell passed the predicate; false on the first failure
     */
    public static boolean forEachFootprintCell(Location origin,
                                               int width,
                                               int height,
                                               int depth,
                                               Predicate<Location> cellOk) {
        int startX = origin.getBlockX() - (width / 2);
        int startY = origin.getBlockY();
        int startZ = origin.getBlockZ() - (depth / 2);
        for (int dx = 0; dx < width; dx++) {
            for (int dy = 0; dy < height; dy++) {
                for (int dz = 0; dz < depth; dz++) {
                    Location cell = new Location(
                            origin.getWorld(),
                            startX + dx,
                            startY + dy,
                            startZ + dz);
                    if (!cellOk.test(cell)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }
}
