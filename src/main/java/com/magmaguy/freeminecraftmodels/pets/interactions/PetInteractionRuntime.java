package com.magmaguy.freeminecraftmodels.pets.interactions;

import com.magmaguy.freeminecraftmodels.pets.PetControl;
import com.magmaguy.freeminecraftmodels.pets.PetManager;
import com.magmaguy.freeminecraftmodels.pets.migration.PetMigrations;
import com.magmaguy.magmacore.ai.MindProgram;
import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;

/**
 * Runs the world interactions. The movement ones are Mind behaviors that slot between following the owner (10) and
 * the plain drift (20): campfire gathering (15), day perch (18) and the rain bloom's drifts (19); the lowest number
 * that can start owns the body, so staying and following always win. The rest run in a pass every two ticks over the
 * live pets: the rain bloom's swelling, the night glow, owned pets' stings on mobs and the crop drizzle. Wild stings on
 * players come from FMM's hitbox contact event ({@link PetSting#onContact}). The pass also drives the night migration
 * schedule. Server thread only.
 */
public final class PetInteractionRuntime {
    private static final int PASS_TICKS = 2;
    private static BukkitTask task;
    private static PetMigrations migrations;
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

    /** The pass clock in server ticks, the time base for sting cooldowns and the campfire index. */
    public static long ticks() {
        return ticks;
    }

    /** Adds the behaviors a pet's species asks for to its Mind program. */
    public static void addBehaviors(MindProgram.Builder builder, PetControl control) {
        PetInteractions interactions = control.species().interactions();
        PetInteractionState state = PetInteractionState.of(control);
        PetInteractions.Campfire campfire = interactions.campfire();
        if (campfire != null && (control.isWild() ? campfire.wild() : campfire.pets()))
            builder.behavior(new CampfireBehavior(control, campfire));
        PetInteractions.DayPerch perch = interactions.dayPerch();
        if (perch != null && (control.isWild() ? perch.wild() : perch.pets()))
            builder.behavior(new DayPerchBehavior(control, perch, state));
        if (interactions.rainBloom() != null) builder.behavior(new RainBloomBehavior(control, interactions.rainBloom(), state));
    }

    private static void runPass() {
        ticks += PASS_TICKS;
        PetManager manager = PetManager.get();
        if (manager != null) for (PetControl control : new ArrayList<>(manager.pets())) pass(control);
        if (migrations != null) migrations.tick(ticks);
        if (ticks % 1200L == 0L) CampfireIndex.prune(ticks);
    }

    private static void pass(PetControl control) {
        LivingEntity body = control.body();
        if (body == null || !body.isValid()) return;
        PetInteractions interactions = control.species().interactions();
        PetInteractionState state = PetInteractionState.of(control);
        if (interactions.rainBloom() != null) RainBloom.update(control, body, interactions.rainBloom(), state, ticks, PASS_TICKS);
        if (interactions.glow() != null && ticks >= state.nextGlowCheck) {
            state.nextGlowCheck = ticks + 40L;
            NightGlow.update(control, body, interactions.glow(), state);
        }
        PetInteractions.Sting sting = interactions.sting();
        if (sting != null && sting.pets() && !control.isWild() && ticks >= state.nextMobSting) {
            state.nextMobSting = ticks + 10L;
            PetSting.stingMobs(control, body, sting, state);
        }
        PetInteractions.Drizzle drizzle = interactions.drizzle();
        if (drizzle != null && ticks >= state.nextDrizzle) {
            state.nextDrizzle = ticks + drizzle.intervalTicks();
            CropDrizzle.update(body, drizzle);
        }
    }
}
