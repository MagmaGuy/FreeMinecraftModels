package com.magmaguy.freeminecraftmodels.listeners;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.customentity.DisguiseManager;
import com.magmaguy.freeminecraftmodels.customentity.PlayerDisguiseEntity;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
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
 *
 * <p>{@link PlayerJoinEvent} / {@link PlayerChangedWorldEvent} re-send the
 * empty-equipment override to fresh viewers so a player who comes online
 * (or warps in from another world) doesn't see armor / held items clipping
 * through an in-progress disguise. {@code setVisibleByDefault(false)}
 * already hides the body for future viewers, but Minecraft renders worn
 * equipment independently of body visibility — without this re-broadcast
 * the disguise's armor would show on every newly-arrived viewer.
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

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player joining = event.getPlayer();
        // Defer one tick: PlayerJoinEvent fires before the entity tracker has
        // finished bootstrapping the new client, so an immediate equipment
        // packet can race the SpawnPlayer packet and get dropped on the
        // client side. One tick is enough for the tracker to settle.
        Bukkit.getScheduler().runTask(MetadataHandler.PLUGIN, () -> {
            if (!joining.isOnline()) return;
            for (PlayerDisguiseEntity disguise : DisguiseManager.getAll()) {
                disguise.hideEquipmentFor(joining);
            }
            // If the joining player is themselves disguised (relog while
            // disguise persists? — currently DisguiseListeners.onQuit
            // undisguises on quit, so this is defensive), re-broadcast the
            // hidden equipment for everyone else to that player.
            PlayerDisguiseEntity own = DisguiseManager.getDisguise(joining);
            if (own != null) {
                for (Player viewer : Bukkit.getOnlinePlayers()) {
                    own.hideEquipmentFor(viewer);
                }
            }
        });
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player moving = event.getPlayer();
        // Cross-world warps re-bootstrap the entity tracker for the moving
        // player: their client throws away its knowledge of entities in the
        // old world and starts fresh in the new one. Any disguised players
        // in the destination world need their equipment re-hidden for this
        // viewer; conversely, if the moving player is the one disguised,
        // everyone in the new world needs the empty-equipment override too.
        Bukkit.getScheduler().runTask(MetadataHandler.PLUGIN, () -> {
            if (!moving.isOnline()) return;
            for (PlayerDisguiseEntity disguise : DisguiseManager.getAll()) {
                disguise.hideEquipmentFor(moving);
            }
            PlayerDisguiseEntity own = DisguiseManager.getDisguise(moving);
            if (own != null) {
                for (Player viewer : Bukkit.getOnlinePlayers()) {
                    own.hideEquipmentFor(viewer);
                }
            }
        });
    }
}
