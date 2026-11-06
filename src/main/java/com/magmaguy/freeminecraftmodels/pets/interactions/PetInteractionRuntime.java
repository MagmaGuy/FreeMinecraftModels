package com.magmaguy.freeminecraftmodels.pets.interactions;

import com.magmaguy.freeminecraftmodels.pets.PetManager;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Prunes the campfire index, in a pass every two ticks. What a pet does in the world, campfire gathering, day perch,
 * rain bloom, glow, sting and drizzle among it, comes from the behavior modules its Mind program runs; wild pets'
 * touches reach the sting module through {@link PetSting#onContact}. Server thread only.
 */
public final class PetInteractionRuntime {
    private static final int PASS_TICKS = 2;
    private static BukkitTask task;
    // The pass clock in server ticks.
    private static long ticks;

    private PetInteractionRuntime() {
    }

    /** Starts the pass. Once per plugin enable, beside the pet manager. */
    public static void start(Plugin plugin) {
        stop();
        task = Bukkit.getScheduler().runTaskTimer(plugin, PetInteractionRuntime::runPass, PASS_TICKS, PASS_TICKS);
    }

    public static void stop() {
        if (task != null) task.cancel();
        task = null;
        CampfireIndex.clear();
    }

    private static void runPass() {
        ticks += PASS_TICKS;
        if (ticks % 1200L == 0L) CampfireIndex.prune(PetManager.ticks());
    }
}
