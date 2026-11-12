package com.magmaguy.freeminecraftmodels.mobbehaviors;

import com.magmaguy.freeminecraftmodels.pets.PetManager;
import com.magmaguy.magmacore.ai.MindFailure;
import com.magmaguy.magmacore.ai.MindHost;
import com.magmaguy.magmacore.ai.overlay.MindOverlays;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mob behaviors: Lua behavior modules laid over the AI of mobs FreeMinecraftModels did not make, such as naturally
 * spawned creepers, for a time or until cleared. MagmaCore's {@link MindOverlays} does the work on the Mind host the
 * pets run on; this gives it FreeMinecraftModels' folder, {@code plugins/FreeMinecraftModels/mob_behaviors}, the
 * modules shipped in the jar, a clock, and a place in the console for a module that fails.
 *
 * <p>A module is put on a mob with {@code /fmm mobbehavior}, with {@code mob:override_ai(...)} from any Lua script
 * FreeMinecraftModels runs, or from Java through {@link #get()}.</p>
 */
public final class MobBehaviors {
    /** The modules shipped in the jar, each written into the folder when it has no file of that name. */
    public static final List<String> DEFAULTS = List.of("coward", "guardian", "hostile", "wander");
    /** Every module's id starts with this, and so does the id of every program made of them. */
    private static final String ID_PREFIX = "fmm:mob/";

    private static final Set<String> said = new HashSet<>();
    private static MindOverlays overlays;
    private static BukkitTask clock;

    private MobBehaviors() {
    }

    /** The running mob behaviors; null when the server has no Mind host, which pets need too. */
    public static MindOverlays get() {
        return overlays;
    }

    /** Loads the modules and starts the clock. Once per plugin enable, after {@link PetManager#start}. */
    public static void start(Plugin plugin) {
        MindHost host = PetManager.mindHost();
        if (host == null) return;
        said.clear();
        overlays = new MindOverlays(plugin, host, "fmm",
                plugin.getDataFolder().toPath().resolve("mob_behaviors"), bundled(plugin), Logger::warn);
        overlays.load();
        overlays.exposeToLua();
        // Scheduled after the pet clock, which ticks the host, so each tick this runs once the Minds have.
        clock = Bukkit.getScheduler().runTaskTimer(plugin, overlays::tick, 1L, 1L);
    }

    /** Gives every mob its own AI back. Before {@link PetManager#stop}, while the host still runs. */
    public static void stop() {
        if (clock != null) clock.cancel();
        clock = null;
        if (overlays != null) overlays.close();
        overlays = null;
    }

    private static Map<String, String> bundled(Plugin plugin) {
        Map<String, String> modules = new LinkedHashMap<>();
        for (String name : DEFAULTS) {
            try (InputStream stream = plugin.getResource("mob_behaviors/" + name + ".lua")) {
                if (stream == null) {
                    Logger.warn("The jar is missing its mob behavior " + name + ".lua.");
                    continue;
                }
                modules.put(name, new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException exception) {
                Logger.warn("Could not read the mob behavior " + name + ".lua from the jar: " + exception.getMessage());
            }
        }
        return modules;
    }

    /** Whether a Mind failure came from a mob behavior rather than a pet. */
    public static boolean owns(MindFailure failure) {
        return failure.programIdentifier().startsWith(ID_PREFIX);
    }

    /** Says once, per callback and kind of failure, that a mob behavior failed and what MagmaCore does about it. */
    public static void report(MindFailure failure) {
        if (!said.add(failure.callbackIdentifier() + "\n" + failure.kind())) return;
        Throwable cause = failure.cause();
        String reason = cause == null ? "no reason was given"
                : cause.getMessage() != null ? cause.getMessage() : cause.toString();
        Logger.warn("Mob behavior callback " + failure.callbackIdentifier() + " failed: " + reason
                + ". MagmaCore stops that behavior, or does not start it, and asks it again at its next check;"
                + " the mob's other behaviors keep running. Fix the file in plugins/FreeMinecraftModels/mob_behaviors"
                + " and run /fmm reload. This is said once per callback until then.");
    }
}
