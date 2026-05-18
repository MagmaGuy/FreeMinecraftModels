package com.magmaguy.freeminecraftmodels.listeners;

import com.magmaguy.freeminecraftmodels.customentity.DisguiseManager;
import com.magmaguy.freeminecraftmodels.customentity.PlayerDisguiseEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Per design Q6 the only auto-undisguise trigger is player logout —
 * death, damage, and world change all preserve the disguise. The
 * plugin-disable path goes through DisguiseManager.shutdown() called
 * from the main plugin onDisable.
 *
 * <p>Also forwards arm-swing events to the per-disguise animation
 * controller so the disguise model plays its 'attack' animation in sync
 * with the player's swing. Sneak / walk / jump are read each tick from
 * the player's cached state and don't need event listeners.
 */
public class DisguiseListeners implements Listener {
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        DisguiseManager.undisguise(event.getPlayer());
    }

    @EventHandler
    public void onArmSwing(PlayerAnimationEvent event) {
        PlayerDisguiseEntity disguise = DisguiseManager.getDisguise(event.getPlayer());
        if (disguise == null) return;
        disguise.getAnimationController().onArmSwing();
    }
}
