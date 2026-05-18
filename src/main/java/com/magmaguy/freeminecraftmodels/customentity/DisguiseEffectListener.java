package com.magmaguy.freeminecraftmodels.customentity;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Keeps the disguise's invisibility potion effect in place whenever something
 * tries to remove or clear it from a disguised player. Two complementary
 * handlers because no single event covers everything:
 *
 * <ul>
 *   <li>{@link EntityPotionEffectEvent} fires on every effect removal, regardless
 *       of cause — milk bucket ({@code Cause.MILK}), {@code /effect clear}
 *       ({@code Cause.COMMAND}), third-party plugins, etc. The 1-tick deferred
 *       re-apply lands while the player is still alive, so the effect sticks.</li>
 *   <li>{@link PlayerRespawnEvent} covers the death→respawn cycle. The potion
 *       event ALSO fires on death (with {@code Cause.DEATH}), but the deferred
 *       re-apply from that path tries to {@code addPotionEffect} while the
 *       player is in the dead state, where it doesn't stick across the respawn.
 *       The respawn handler re-applies after the player is fully alive again,
 *       which is when {@code addPotionEffect} actually persists.</li>
 * </ul>
 *
 * <p>Self-removal during {@code /fmm undisguise} is naturally skipped:
 * {@link DisguiseManager#undisguise} removes the player from the active-disguise
 * map BEFORE {@link PlayerDisguiseEntity#remove} strips the potion. By the time
 * the potion event handler runs, {@code DisguiseManager.getDisguise(player)}
 * returns null and we no-op — no infinite loop.
 */
public final class DisguiseEffectListener implements Listener {

    @EventHandler(ignoreCancelled = true)
    public void onPotionEffect(EntityPotionEffectEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        PlayerDisguiseEntity disguise = DisguiseManager.getDisguise(player);
        if (disguise == null) return; // not disguised (or in the middle of undisguising)

        EntityPotionEffectEvent.Action action = event.getAction();
        if (action != EntityPotionEffectEvent.Action.REMOVED
                && action != EntityPotionEffectEvent.Action.CLEARED
                && action != EntityPotionEffectEvent.Action.CHANGED) return;

        // For REMOVED/CHANGED we can inspect the old effect type. For CLEARED
        // some implementations don't populate it, so we re-apply unconditionally
        // and let reapplyInvisibilityIfOurs() decide whether anything needs
        // doing (it checks hasPotionEffect first).
        PotionEffect old = event.getOldEffect();
        if (action != EntityPotionEffectEvent.Action.CLEARED) {
            if (old == null) return;
            if (!PotionEffectType.INVISIBILITY.equals(old.getType())) return;
        }

        // Defer one tick — adding a new effect from inside an
        // EntityPotionEffectEvent handler is undefined behaviour because the
        // current removal hasn't settled yet. The next-tick scheduling lets
        // the removal complete first, then we put the effect back.
        //
        // For Cause.DEATH this deferred re-apply may not stick (player is in
        // the dead state); PlayerRespawnEvent below is the safety net for that
        // path. The two handlers are redundant on death, which is fine —
        // reapplyInvisibilityIfOurs short-circuits if the effect is already
        // present.
        Bukkit.getScheduler().runTask(MetadataHandler.PLUGIN, disguise::reapplyInvisibilityIfOurs);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        PlayerDisguiseEntity disguise = DisguiseManager.getDisguise(event.getPlayer());
        if (disguise == null) return;
        // Defer one tick: PlayerRespawnEvent fires mid-respawn before the player
        // state has fully settled. Applying potion effects in the same tick can
        // get clobbered by respawn finalization — well-known plugin gotcha.
        Bukkit.getScheduler().runTask(MetadataHandler.PLUGIN, disguise::reapplyInvisibilityIfOurs);
    }
}
