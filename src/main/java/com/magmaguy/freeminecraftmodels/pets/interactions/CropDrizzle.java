package com.magmaguy.freeminecraftmodels.pets.interactions;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.block.BlockGrowEvent;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Grazing drizzle: while the pet hovers still over a crop, a light rain falls on it, and now and then the crop grows a
 * stage. The growth goes out as a {@link BlockGrowEvent}, so protection and farming plugins can refuse it.
 */
final class CropDrizzle {
    private CropDrizzle() {
    }

    static void update(LivingEntity body, PetInteractions.Drizzle drizzle) {
        // Following or drifting fast is not hovering.
        if (body.getVelocity().lengthSquared() > 0.01D) return;
        Location at = body.getLocation();
        World world = at.getWorld();
        int x = at.getBlockX(), z = at.getBlockZ();
        for (int y = at.getBlockY(); y >= at.getBlockY() - drizzle.depth() && y > world.getMinHeight(); y--) {
            Block block = world.getBlockAt(x, y, z);
            if (block.getBlockData() instanceof Ageable crop && isCrop(block.getType())) {
                world.spawnParticle(Particle.FALLING_WATER, at, 6, 0.25D, 0.05D, 0.25D, 0D);
                if (crop.getAge() < crop.getMaximumAge() && ThreadLocalRandom.current().nextDouble() < drizzle.chance())
                    grow(block, crop);
                return;
            }
            if (!block.getType().isAir()) return;
        }
    }

    private static boolean isCrop(Material material) {
        return Tag.CROPS.isTagged(material) || material == Material.NETHER_WART || material == Material.SWEET_BERRY_BUSH;
    }

    private static void grow(Block block, Ageable crop) {
        Ageable grown = (Ageable) crop.clone();
        grown.setAge(crop.getAge() + 1);
        BlockState next = block.getState();
        next.setBlockData(grown);
        BlockGrowEvent event = new BlockGrowEvent(block, next);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return;
        event.getNewState().update(true);
        block.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, block.getLocation().add(0.5D, 0.5D, 0.5D), 4, 0.3D, 0.3D, 0.3D, 0D);
    }
}
