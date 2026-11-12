package com.magmaguy.freeminecraftmodels.api;

import com.magmaguy.freeminecraftmodels.mobbehaviors.MobBehaviors;
import com.magmaguy.magmacore.ai.overlay.MindOverlayRequest;
import com.magmaguy.magmacore.ai.overlay.MindOverlays;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Public entry point for mob behaviors: Lua behavior modules that take over the AI of a mob FreeMinecraftModels did
 * not spawn, for a time or until cleared, after which the mob has its own AI back.
 *
 * <p>Every parameter and result is a plain Java or Bukkit type, so a plugin that shades its own MagmaCore, or that
 * compiles against a FreeMinecraftModels older than this class, can still call these methods by looking them up by
 * name on the running jar. EliteMobs does. Their names and parameter types are therefore a contract: add methods,
 * never change these.</p>
 *
 * <p>Call them on the server thread.</p>
 */
public final class MobBehaviorAPI {
    private static final String UNAVAILABLE = "Mob behaviors are not available on this server: they need Minecraft"
            + " 1.21.4 or newer";

    private MobBehaviorAPI() {
    }

    /** Whether mob behaviors run on this server. They need Minecraft 1.21.4 or newer. */
    public static boolean isAvailable() {
        return MobBehaviors.get() != null;
    }

    /** The names of the behaviors that loaded from {@code plugins/FreeMinecraftModels/mob_behaviors}, sorted. */
    public static List<String> behaviors() {
        MindOverlays overlays = MobBehaviors.get();
        return overlays == null ? List.of() : overlays.behaviors();
    }

    /**
     * Overrides a mob's AI with the named behaviors, in place of any override it had.
     *
     * @param mob           the mob; any living entity that is not a player
     * @param behaviors     behavior names, the file names in the mob_behaviors folder without {@code .lua}
     * @param owner         the player or other living entity the mob serves and never hurts; null for nobody
     * @param durationTicks how long the override lasts; 0 or less lasts until cleared
     * @param settings      per behavior name, the settings that replace its defaults, as Double, Boolean, String or
     *                      List of String values; null or empty keeps every default
     * @return null when the mob now runs the behaviors, otherwise why it does not; the mob is then as it was
     */
    public static String overrideAI(
            LivingEntity mob,
            List<String> behaviors,
            UUID owner,
            long durationTicks,
            Map<String, Map<String, Object>> settings) {
        MindOverlays overlays = MobBehaviors.get();
        if (overlays == null) return UNAVAILABLE;
        if (mob == null) return "There is no mob to override";
        try {
            return overlays.apply(mob, new MindOverlayRequest(behaviors, owner, Math.max(0L, durationTicks), settings));
        } catch (IllegalArgumentException | NullPointerException refused) {
            return refused.getMessage() == null ? "The behaviors asked for are not valid" : refused.getMessage();
        }
    }

    /**
     * Gives a mob its own AI back and forgets its override.
     *
     * @return whether it had one
     */
    public static boolean restoreAI(LivingEntity mob) {
        MindOverlays overlays = MobBehaviors.get();
        return overlays != null && mob != null && overlays.clear(mob);
    }

    /** Whether mob behaviors override the entity's AI now. */
    public static boolean isAIOverridden(Entity entity) {
        MindOverlays overlays = MobBehaviors.get();
        return overlays != null && entity != null && overlays.overlaid(entity);
    }
}
