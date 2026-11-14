package com.magmaguy.freeminecraftmodels.pets;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.SoundGroup;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;

import java.util.Locale;
import java.util.Map;

/**
 * A walking pet's footsteps. Every pet body is silent, so its carrier never neighs or grunts, and that took its
 * footsteps with it: a ridden penguin slid over grass, snow and ice without a sound. A pet now makes the step sound
 * of the block under it as it moves, as a player's own feet do: the same sound, at the same volume and pitch, once
 * for every {@link #STRIDE} blocks it covers on the ground. Pets that fly or float make none, nor does a pet in the
 * air or in water. A species can turn its footsteps off, and name the sound a ridden pet makes on a block in place of
 * that block's own.
 */
final class PetSteps {
    /**
     * Blocks a pet covers on the ground between two footsteps. Vanilla counts 0.6 of every block a player walks and
     * plays a step each time the count passes a whole number.
     */
    static final double STRIDE = 1D / 0.6D;
    /** The share of a block's own volume its step sound plays at, as vanilla plays a player's. */
    static final float VOLUME = 0.15F;
    /** How far under a pet's feet the block it stands on is looked for, in blocks, as vanilla looks. */
    private static final double UNDER = 0.2D;
    private static final Location PROBE = new Location(null, 0D, 0D, 0D);

    private PetSteps() {
    }

    /** Where a pet stood when it was last looked at, and how far it has walked since its last footstep. */
    static final class Walk {
        private double x = Double.NaN, z;
        private double since;
    }

    /**
     * How far along its stride a pet is after this move, and whether that is a footstep: the distance walked since
     * the last one, less a stride once it reaches one. A pet that is not walking on the ground, in the air or in
     * water, keeps what it had and adds nothing; one that covers several strides in a tick steps once and starts over,
     * since a tick plays one sound.
     */
    static boolean advance(Walk walk, double x, double z, boolean walking) {
        boolean first = Double.isNaN(walk.x);
        double moved = first ? 0D : Math.sqrt((x - walk.x) * (x - walk.x) + (z - walk.z) * (z - walk.z));
        walk.x = x;
        walk.z = z;
        if (!walking) return false;
        walk.since += moved;
        if (walk.since < STRIDE) return false;
        walk.since = walk.since >= 2D * STRIDE ? 0D : walk.since - STRIDE;
        return true;
    }

    /**
     * The block whose sound a step makes: the one at the pet's feet when it is something walked through that still
     * sounds, a layer of snow or a carpet, else the one under them.
     */
    static boolean soundsAtFeet(Material feet) {
        // By name: the carpets include ones older servers do not have.
        return feet == Material.SNOW || feet == Material.POWDER_SNOW || feet.name().endsWith("_CARPET");
    }

    /**
     * Counts a pet's move this tick and plays its footstep when one is due. {@code override} names, by block, the
     * sound played in place of that block's own step, as a ridden pet's file may give; null or an empty map leaves
     * every block its own.
     */
    static void tick(Walk walk, LivingEntity body, Map<Material, String> override) {
        // One Location for every pet's tick: the pet clock runs on the main thread, and nothing keeps it past the call.
        Location at = body.getLocation(PROBE);
        boolean walking = body.isOnGround() && !body.isInWater();
        if (!advance(walk, at.getX(), at.getZ(), walking)) return;
        Block feet = at.getBlock();
        Block block = soundsAtFeet(feet.getType()) ? feet : at.clone().subtract(0D, UNDER, 0D).getBlock();
        if (block.getType().isAir()) return;
        SoundGroup group = block.getBlockData().getSoundGroup();
        String named = override == null ? null : override.get(block.getType());
        if (named != null)
            body.getWorld().playSound(at, named.toLowerCase(Locale.ROOT), SoundCategory.NEUTRAL, group.getVolume() * VOLUME,
                    group.getPitch());
        else {
            Sound step = group.getStepSound();
            body.getWorld().playSound(at, step, SoundCategory.NEUTRAL, group.getVolume() * VOLUME, group.getPitch());
        }
    }
}
