package com.magmaguy.freeminecraftmodels.customentity.core.components;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.api.ModeledEntityHitByProjectileEvent;
import com.magmaguy.freeminecraftmodels.api.ModeledEntityHitboxContactEvent;
import com.magmaguy.freeminecraftmodels.api.ModeledEntityLeftClickEvent;
import com.magmaguy.freeminecraftmodels.api.ModeledEntityRightClickEvent;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntityHitboxContactCallback;
import com.magmaguy.freeminecraftmodels.customentity.core.MountPointManager;
import com.magmaguy.freeminecraftmodels.customentity.core.OBBHitDetection;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntityLeftClickCallback;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntityRightClickCallback;
import com.magmaguy.freeminecraftmodels.customentity.PropEntity;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.Bukkit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.LivingEntity;
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
    @Getter
    private ModeledEntityRightClickCallback rightClickCallback;
    @Setter
    @Getter
    private ModeledEntityHitboxContactCallback hitboxContactCallback;
    @Setter
    @Getter
    private ModeledEntityHitByProjectileCallback projectileHitCallback;

    // Cooldown to prevent double-firing from both packet interaction entity and OBB raytrace
    private static final long RIGHT_CLICK_COOLDOWN_MS = 100;
    private static final long LEFT_CLICK_COOLDOWN_MS = 100;
    private final Map<UUID, Long> rightClickCooldowns = new HashMap<>();
    private final Map<UUID, Long> leftClickCooldowns = new HashMap<>();

    public InteractionComponent(ModeledEntity modeledEntity) {
        this.modeledEntity = modeledEntity;
    }

    public void callLeftClickEvent(Player player) {
        if (modeledEntity.isDying()) return;
        long now = System.currentTimeMillis();
        Long last = leftClickCooldowns.get(player.getUniqueId());
        if (last != null && (now - last) < LEFT_CLICK_COOLDOWN_MS) return;
        leftClickCooldowns.put(player.getUniqueId(), now);
        ModeledEntityLeftClickEvent event = new ModeledEntityLeftClickEvent(player, modeledEntity);
        Bukkit.getPluginManager().callEvent(event);
    }

    public void callRightClickEvent(Player player) {
        if (modeledEntity.isDying()) return;
        long now = System.currentTimeMillis();
        Long last = rightClickCooldowns.get(player.getUniqueId());
        if (last != null && (now - last) < RIGHT_CLICK_COOLDOWN_MS) return;
        rightClickCooldowns.put(player.getUniqueId(), now);
        ModeledEntityRightClickEvent event = new ModeledEntityRightClickEvent(player, modeledEntity);
        Bukkit.getPluginManager().callEvent(event);
    }

    /**
     * Triggers the appropriate hitbox contact event based on entity type
     * This method should be overridden by subclasses to fire their specific event types
     */
    protected void callHitboxContactEvent(Player player) {
        if (modeledEntity.isDying()) return;
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
        if (modeledEntity.isDying()) return;
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
        if (leftClickCallback != null) {
            leftClickCallback.onLeftClick(player, modeledEntity);
            return;
        }
        // Default behavior for dynamic entities (those with an underlying
        // LivingEntity backing the model): forward the swing to the underlying
        // entity as a vanilla attack so it takes damage normally. Without this
        // default, OBB-edge clicks reach this callback path with no handler and
        // the entity stays un-attackable outside its resized vanilla bbox —
        // i.e. the custom hitbox visually exists but doesn't register hits.
        // The applyDamage flag bypasses OBBHitDetection's own
        // EntityDamageByEntityEvent cancel for this single dispatch.
        if (modeledEntity instanceof PropEntity) return;
        if (!(modeledEntity.getUnderlyingEntity() instanceof LivingEntity underlying)) return;
        OBBHitDetection.applyDamage = true;
        try {
            player.attack(underlying);
        } finally {
            OBBHitDetection.applyDamage = false;
        }
    }

    public void handleRightClickEvent(Player player) {
        if (rightClickCallback != null) {
            rightClickCallback.onRightClick(player, modeledEntity);
            return;
        }
        // Default behavior: try mounting if the entity has mount points
        MountPointManager mountPointManager = modeledEntity.getMountPointManager();
        if (mountPointManager != null && mountPointManager.hasMountPoints()) {
            mountPointManager.tryMount(player);
        }
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
            if (event.isCancelled()) {
                return;
            }
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
