package com.magmaguy.freeminecraftmodels.customentity.core.components;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.api.ModeledEntityHitByProjectileEvent;
import com.magmaguy.freeminecraftmodels.api.ModeledEntityHitboxContactEvent;
import com.magmaguy.freeminecraftmodels.api.ModeledEntityLeftClickEvent;
import com.magmaguy.freeminecraftmodels.api.ModeledEntityRightClickEvent;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntityHitboxContactCallback;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntityLeftClickCallback;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntityRightClickCallback;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * This class handles left click, right click, and hitbox contact events for the entity.
 * Certain types of entities may have default behaviors for these events, which can be overriden by setting custom callbacks.
 */
public class InteractionComponent {
    private final ModeledEntity modeledEntity;
    // Callback fields
    @Setter
    private ModeledEntityLeftClickCallback leftClickCallback;
    @Setter
    private ModeledEntityRightClickCallback rightClickCallback;
    @Setter
    @Getter
    private ModeledEntityHitboxContactCallback hitboxContactCallback;
    @Setter
    @Getter
    private ModeledEntityHitByProjectileCallback projectileHitCallback;

    public InteractionComponent(ModeledEntity modeledEntity) {
        this.modeledEntity = modeledEntity;
    }

    public void callLeftClickEvent(Player player) {
        ModeledEntityLeftClickEvent event = new ModeledEntityLeftClickEvent(player, modeledEntity);
        Bukkit.getPluginManager().callEvent(event);
    }

    public void callRightClickEvent(Player player) {
        ModeledEntityRightClickEvent event = new ModeledEntityRightClickEvent(player, modeledEntity);
        Bukkit.getPluginManager().callEvent(event);
    }

    /**
     * Triggers the appropriate hitbox contact event based on entity type
     * This method should be overridden by subclasses to fire their specific event types
     */
    protected void callHitboxContactEvent(Player player) {
        //Pass back to synchronous
        new BukkitRunnable() {
            @Override
            public void run() {
                ModeledEntityHitboxContactEvent event = new ModeledEntityHitboxContactEvent(player, modeledEntity);
                Bukkit.getPluginManager().callEvent(event);
            }
        }.runTask(MetadataHandler.PLUGIN);
    }

    /**
     * Triggers the appropriate hitbox contact event based on entity type
     * This method should be overridden by subclasses to fire their specific event types
     */
    public void callModeledEntityHitByProjectileEvent(Projectile projectile) {
        //Pass back to synchronous
        new BukkitRunnable() {
            @Override
            public void run() {
                ModeledEntityHitByProjectileEvent event = new ModeledEntityHitByProjectileEvent(projectile, modeledEntity);
                Bukkit.getPluginManager().callEvent(event);
            }
        }.runTask(MetadataHandler.PLUGIN);
    }

    public void handleLeftClickEvent(Player player) {
        if (leftClickCallback == null) return;
        leftClickCallback.onLeftClick(player, modeledEntity);
    }

    public void handleRightClickEvent(Player player) {
        if (rightClickCallback == null) return;
        rightClickCallback.onRightClick(player, modeledEntity);
    }

    public void handleHitboxContactEvent(Player player) {
        if (hitboxContactCallback == null) return;
        hitboxContactCallback.onHitboxContact(player, modeledEntity);
    }

    public void handleModeledEntityHitByProjectileEvent(Projectile projectile) {
        if (projectileHitCallback == null) return;
        projectileHitCallback.onHitByProjectile(projectile, modeledEntity);
    }

    // Clear all callbacks
    public void clearCallbacks() {
        this.leftClickCallback = null;
        this.rightClickCallback = null;
        this.hitboxContactCallback = null;
    }

    public static class InteractionComponentEvents implements Listener {
        @EventHandler
        public void onLeftClick(ModeledEntityLeftClickEvent event) {
            if (event.isCancelled()) return;
            event.getEntity().getInteractionComponent().handleLeftClickEvent(event.getPlayer());
        }

        @EventHandler
        public void onRightClick(ModeledEntityRightClickEvent event) {
            if (event.isCancelled()) return;
            event.getEntity().getInteractionComponent().handleRightClickEvent(event.getPlayer());
        }

        @EventHandler
        public void onHitboxContact(ModeledEntityHitboxContactEvent event) {
            if (event.isCancelled()) return;
            event.getEntity().getInteractionComponent().handleHitboxContactEvent(event.getPlayer());
        }

        @EventHandler
        public void onProjectileHit(ModeledEntityHitByProjectileEvent event) {
            if (event.isCancelled()) return;
            event.getEntity().getInteractionComponent().handleModeledEntityHitByProjectileEvent(event.getProjectile());
        }
    }
}
