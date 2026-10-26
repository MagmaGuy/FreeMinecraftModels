package com.magmaguy.freeminecraftmodels.pets.interactions;

import com.magmaguy.freeminecraftmodels.pets.PetManager;
import com.magmaguy.freeminecraftmodels.pets.migration.PetMigrations;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Drives the night migration schedule and prunes the campfire index, in a pass every two ticks. What a pet does in the
 * world, campfire gathering, day perch, rain bloom, glow, sting and drizzle among it, comes from the behavior modules
 * its Mind program runs; wild pets' touches reach the sting module through {@link PetSting#onContact}. Server thread
 * only.
 */
public final class PetInteractionRuntime {
    private static final int PASS_TICKS = 2;
    private static BukkitTask task;
    private static PetMigrations migrations;
    // The pass clock in server ticks, the migration schedule's time base.
    private static long ticks;

    private PetInteractionRuntime() {
    }

    /** Starts the pass and the migration schedule. Once per plugin enable, beside the pet manager. */
    public static void start(Plugin plugin) {
        stop();
        migrations = new PetMigrations(plugin);
        task = Bukkit.getScheduler().runTaskTimer(plugin, PetInteractionRuntime::runPass, PASS_TICKS, PASS_TICKS);
    }

    public static void stop() {
        if (task != null) task.cancel();
        task = null;
        if (migrations != null) migrations.stopAll();
        migrations = null;
        CampfireIndex.clear();
    }

    /** The migration schedule, or null while pets are off. */
    public static PetMigrations migrations() {
        return migrations;
    }

    private static void runPass() {
        ticks += PASS_TICKS;
        if (migrations != null) migrations.tick(ticks);
        if (ticks % 1200L == 0L) CampfireIndex.prune(PetManager.ticks());
    }
}
